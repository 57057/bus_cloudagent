"""Build a credential-free Linux deployment bundle from allowlisted artifacts.

Does not upload, provision hosts, copy local databases, or change the live IIS site.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import uuid

ROOT = Path(__file__).resolve().parents[1]


def source_files(root):
    for folder in ('app', 'agent'):
        for path in sorted((root / folder).rglob('*.py')):
            if not {'test', 'tests', '__pycache__'} & set(path.relative_to(root).parts):
                yield path
    yield root / 'agent/config/mcp_servers.json'
    for suffix in ('*.md', '*.json'):
        yield from sorted((root / 'mock_data').glob(suffix))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--skip-build', action='store_true', help='Package previously verified build artifacts')
    args = parser.parse_args()
    if not args.skip_build:
        subprocess.run([sys.executable, str(ROOT / 'deploy/build_business.py')], check=True)
        subprocess.run(['npm.cmd' if sys.platform == 'win32' else 'npm', '--prefix', str(ROOT / 'front/platform-web'), 'run', 'build'], check=True)
    release = ROOT / 'runtime/cloud-releases' / uuid.uuid4().hex[:12]
    control = release / 'control'
    worker = release / 'worker'
    control.mkdir(parents=True)
    worker.mkdir()
    templates = ROOT / 'deploy/cloud'
    for name in ('compose.yaml', 'Caddyfile', 'Business.Dockerfile', 'Agent.Dockerfile', '.dockerignore', 'start.sh', 'prepare_config.py', 'preflight.py', 'config.example.env'):
        shutil.copyfile(templates / name, control / name)
    shutil.copyfile(ROOT / 'deploy/embedEtcd.yaml', control / 'embedEtcd.yaml')
    shutil.copyfile(ROOT / 'deploy/milvus.user.yaml', control / 'milvus.yaml')
    shutil.copyfile(ROOT / 'business-java/target/business-1.0.0.jar', control / 'business.jar')
    shutil.copytree(ROOT / 'front/platform-web/dist', control / 'site')
    (control / 'site/deployment-config.js').write_text('window.__CLOUDAGENT_DEPLOYMENT__ = {frontendOnly: false};\n')
    for path in source_files(ROOT):
        target = control / path.relative_to(ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    # The Windows freeze contains pywin32, which has no Linux distribution.
    lines = (ROOT / 'requirements.local.lock.txt').read_text().splitlines()
    (control / 'requirements.txt').write_text('\n'.join(line for line in lines if not line.lower().startswith('pywin32==')) + '\n')
    for name in ('runner.service', 'install_runner.sh'):
        shutil.copyfile(templates / name, worker / name)
    shutil.copyfile(ROOT / 'deployment-runner/runner.py', worker / 'runner.py')
    # Approved image build contexts are already produced by the sample build tool.
    images = ROOT / 'runtime/application-images'
    if not all((images / name / 'Dockerfile').is_file() for name in ('backend', 'frontend')):
        raise RuntimeError('Build approved sample contexts first: deploy/build_sample_application.py')
    for name in ('backend', 'frontend'):
        shutil.copytree(images / name, worker / name)
    (worker / 'bootstrap.example.json').write_text(json.dumps({'base_url': 'https://YOUR_PLATFORM_DOMAIN', 'registration_token': 'ADMIN_ISSUED_ONE_TIME_TOKEN', 'backend_image': 'cloudagent-demo-backend:1', 'frontend_image': 'cloudagent-demo-frontend:1', 'public_host': 'YOUR_WORKER_IP', 'application_port': 18080}, indent=2))
    shutil.copyfile(ROOT / 'docs/云端完整部署准备与资源清单.md', release / 'README.md')
    hashes = {p.relative_to(release).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(release.rglob('*')) if p.is_file()}
    (release / 'manifest.json').write_text(json.dumps({'contains_credentials': False, 'cloud_deployed': False, 'files': hashes}, indent=2))
    archive = release.with_suffix('.tar.gz')
    with tarfile.open(archive, 'w:gz') as package:
        package.add(release, arcname='cloudagent')
    print('Credential-free deployment bundle:', archive)
    print('SHA256:', hashlib.sha256(archive.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
