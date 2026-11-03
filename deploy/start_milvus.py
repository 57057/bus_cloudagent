"""Start the project Docker VM and its persistent Milvus container."""
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    # Do not start another VirtualBox session when the configured Milvus is already healthy.
    try:
        sys.path.insert(0, str(ROOT))
        from agent.config import get_settings
        from pymilvus import MilvusClient
        settings = get_settings()
        args = {"uri": f"http://{settings.milvus_host}:{settings.milvus_port}", "timeout": 3}
        if settings.milvus_api_key:
            args["token"] = settings.milvus_api_key
        client = MilvusClient(**args)
        try:
            client.list_collections()
        finally:
            client.close()
        print("Milvus is already responding; no VM restart needed.")
        return
    except Exception:
        pass
    subprocess.run([sys.executable, str(ROOT / "deploy/create_docker_vm.py")], check=True)
    deadline = time.monotonic() + 55
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", 2222), timeout=2) as connection:
                connection.settimeout(2)
                if connection.recv(64).startswith(b"SSH-"):
                    break
        except OSError:
            pass
        time.sleep(0.5)
    else:
        raise RuntimeError("Docker VM SSH is still starting; retry startup shortly")
    subprocess.run([sys.executable, str(ROOT / "deploy/vm_command.py"),
                    "cd /home/cloudagent/milvus && docker compose -f compose.yml up -d --wait --wait-timeout 180"],
                   check=True)

if __name__ == "__main__":
    main()
