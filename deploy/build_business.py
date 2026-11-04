"""Build Java business service with a project-local Maven distribution/cache."""
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "3.9.11"


def main():
    tools = ROOT / "runtime/tools"
    maven = tools / f"apache-maven-{VERSION}/bin/mvn.cmd"
    if not maven.exists():
        tools.mkdir(parents=True, exist_ok=True)
        url = f"https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/{VERSION}/apache-maven-{VERSION}-bin.zip"
        archive = tools / "maven.zip"
        urllib.request.urlretrieve(url, archive)
        with urllib.request.urlopen(url + ".sha512", timeout=30) as response:
            expected = response.read().decode().split()[0]
        if hashlib.sha512(archive.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Maven archive checksum mismatch")
        with zipfile.ZipFile(archive) as package:
            package.extractall(tools)
    env = dict(os.environ)
    env.setdefault("JAVA_HOME", "D:/java")
    env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    subprocess.run([str(maven), f"-Dmaven.repo.local={ROOT / 'runtime/maven-repository'}",
                    "-B", "-ntp", "clean", "package"], cwd=ROOT / "business-java", env=env, check=True)


if __name__ == "__main__":
    main()
