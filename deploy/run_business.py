"""Launch the Java business process using existing local settings, without printing secrets."""
import os
from pathlib import Path
import subprocess
import sys
import json
import hashlib
import shutil

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


def main():
    from agent.config import get_settings
    settings = get_settings()
    env = dict(os.environ)
    from dotenv import dotenv_values
    env.update({k:v for k,v in dotenv_values(ROOT / "business-java/.env").items() if v is not None and k not in env})
    for name in ("host", "port", "user", "password", "database"):
        env["MYSQL_" + name.upper()] = str(getattr(settings, "mysql_" + name))
    token_path = ROOT / "runtime/business-service.token"
    if not token_path.exists():
        import secrets
        token_path.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    env["BUSINESS_SERVICE_TOKEN"] = token_path.read_text(encoding="utf-8").strip()
    env.setdefault("PLATFORM_DEMO", "true")  # Explicit local demonstration deployment.
    admin_path = ROOT / "runtime/platform-admin.json"
    if not admin_path.exists():
        import secrets
        admin_path.write_text(json.dumps({"username": "admin", "password": "Cloud-" + secrets.token_urlsafe(18) + "7"}), encoding="utf-8")
    admin = json.loads(admin_path.read_text(encoding="utf-8"))
    env.setdefault("PLATFORM_ADMIN_USER", admin["username"])
    env.setdefault("PLATFORM_ADMIN_PASSWORD", admin["password"])
    jar = ROOT / "business-java/target/business-1.0.0.jar"
    if not jar.exists():
        subprocess.run([sys.executable, str(ROOT / "deploy/build_business.py")], check=True)
    # Run an immutable release copy so Windows never locks Maven's build output.
    releases = ROOT / "runtime/releases"
    releases.mkdir(parents=True, exist_ok=True)
    release = releases / ("business-" + hashlib.sha256(jar.read_bytes()).hexdigest()[:16] + ".jar")
    if not release.exists():
        shutil.copy2(jar, release)
    code = subprocess.call(["D:/java/bin/java.exe", "-Duser.timezone=UTC", "-jar", str(release)], cwd=ROOT, env=env)
    sys.exit(code)


if __name__ == "__main__":
    main()
