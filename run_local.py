"""Start the project's local services with logs and no visible terminal windows."""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
RUNTIME = ROOT / "runtime"
LOGS = RUNTIME / "logs"
STATE = RUNTIME / "processes.json"
NEO = RUNTIME / "neo4j-community-5.26.0"
PYTHON = ROOT / ".venv/Scripts/python.exe"

def listening(port):
    with socket.socket() as client:
        client.settimeout(0.5)
        return client.connect_ex(("127.0.0.1", port)) == 0

def launch(name, command, port, cwd=ROOT, env=None):
    if listening(port):
        print(f"{name}: already running on {port}")
        return
    LOGS.mkdir(parents=True, exist_ok=True)
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    with (LOGS / f"{name}.log").open("ab") as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
    state[name] = process.pid
    STATE.write_text(json.dumps(state, indent=2))
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"{name} exited; see {LOGS / (name + '.log')}")
        if listening(port):
            print(f"{name}: ready on {port}")
            return
        time.sleep(0.5)
    raise RuntimeError(f"{name} is still starting; see {LOGS / (name + '.log')}")

def restart_backend():
    state = json.loads(STATE.read_text()) if STATE.exists() else {}
    for name, marker in (("backend", "uvicorn"), ("business-java", "run_business.py")):
        pid = state.get(name)
        if not pid:
            continue
        check = subprocess.run(["powershell.exe", "-NoProfile", "-Command",
                                f"(Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}').CommandLine"],
                               capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
        if not check.stdout.strip():
            continue
        if marker not in check.stdout or str(PYTHON) not in check.stdout:
            raise RuntimeError(f"{name} process identity mismatch; nothing was stopped")
        subprocess.run(["taskkill.exe", "/PID", str(pid), "/T", "/F"], check=True,
                       capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
    for _ in range(40):
        if not listening(5000) and not listening(5001):
            return
        time.sleep(0.25)
    if listening(5000) or listening(5001):
        raise RuntimeError("API ports remain occupied; no unknown process was terminated")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--restart-backend", action="store_true")
    parser.add_argument("--skip-knowledge-sync", action="store_true")
    parser.add_argument("--business-only", action="store_true", help="Start MySQL, Java and React without AI infrastructure")
    args = parser.parse_args()
    if not (ROOT / "business-java/target/business-1.0.0.jar").exists():
        subprocess.run([str(PYTHON), str(ROOT / "deploy/build_business.py")], check=True)
    if not args.business_only:
        milvus = subprocess.run([str(PYTHON), str(ROOT / "deploy/start_milvus.py")])
        if milvus.returncode:
            print("Milvus startup pending; normal business remains available.")
        elif not args.skip_knowledge_sync:
            subprocess.run([str(PYTHON), str(ROOT / "sync_knowledge.py")], check=True)
    if args.restart_backend:
        restart_backend()
    env = dict(os.environ)
    env.update(PYTHONUTF8="1", JAVA_HOME="D:/java", NEO4J_HOME=str(NEO))
    token_path = RUNTIME / "business-service.token"
    if not token_path.exists():
        import secrets
        token_path.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    env["BUSINESS_SERVICE_TOKEN"] = token_path.read_text(encoding="utf-8").strip()
    env["BUSINESS_BASE_URL"] = "http://127.0.0.1:5000"
    launch("mysql", ["D:/mysql-5.7.24-winx64/bin/mysqld.exe", "--no-defaults",
                     "--basedir=D:/mysql-5.7.24-winx64", f"--datadir={RUNTIME / 'mysql-data'}",
                     "--bind-address=127.0.0.1", "--port=3307", "--log_syslog=0",
                     "--explicit_defaults_for_timestamp", "--console"], 3307)
    # Java 商品目录也使用 Redis，因此仅业务模式同样启动它。
    launch("redis", [str(RUNTIME / "redis/redis-server.exe"), str(RUNTIME / "redis/local.conf")], 6379)
    launch("business-java", [str(PYTHON), str(ROOT / "deploy/run_business.py")], 5000, env=env)
    if (RUNTIME / "application-runners.json").exists():
        try:
            if not listening(2222):
                subprocess.run([str(PYTHON),str(ROOT / "deploy/create_docker_vm.py")],check=True,timeout=45)
            subprocess.run([str(PYTHON),str(ROOT / "deploy/start_application_runner.py")],check=True,timeout=30)
        except (subprocess.SubprocessError,OSError):
            print("Application Runner pending; transactions remain available. Retry deploy/start_application_runner.py.")
    node = shutil.which("node.exe")
    if not node:
        raise RuntimeError("node.exe not found")
    front = ROOT / "front/platform-web"
    launch("frontend", [node, str(front / "node_modules/vite/bin/vite.js"), "--host",
                        "127.0.0.1", "--port", "5173", "--strictPort"], 5173, cwd=front)
    if args.business_only:
        print("React: http://127.0.0.1:5173/ ; Business API: http://127.0.0.1:5000/")
        return
    launch("neo4j", ["D:/java/bin/java.exe", "-cp", str(NEO / "lib/*"),
                     "-Dbasedir=" + str(NEO), "org.neo4j.server.startup.Neo4jCommand", "console"],
           7687, env=env)
    try:
        launch("backend", [str(PYTHON), "-m", "uvicorn", "app.app_main:app",
                           "--host", "127.0.0.1", "--port", "5001"], 5001, env=env)
    except RuntimeError as exc:
        print(f"Agent startup pending: {exc}; business and React remain running.")
    print("Frontend: http://127.0.0.1:5173/")
    print("Python Agent docs: http://127.0.0.1:5001/docs")
    try:
        with urllib.request.urlopen("http://127.0.0.1:5000/health", timeout=5) as response:
            print("Health: " + response.read().decode())
    except Exception:
        print("Health check pending; inspect runtime/logs/backend.log")

if __name__ == "__main__":
    main()
