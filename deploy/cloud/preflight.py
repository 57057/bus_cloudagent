"""Read-only Linux prerequisites. Never prints environment values or invokes purchases."""
import json
from pathlib import Path
import platform
import shutil
import socket
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parent
    failures = []
    if platform.system() != 'Linux' or platform.machine() != 'x86_64':
        failures.append('Linux x86_64 host required')
    else:
        memory = next(int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemTotal:'))
        if memory < 15_000_000:
            failures.append('Combined control-plane demo requires >=16GB RAM')
        if shutil.disk_usage(root).free < 40 * 1024**3:
            failures.append('At least 40GiB free disk required for images and initial data')
    env = root / '.env'
    values = {}
    if env.exists():
        for line in env.read_text().splitlines():
            if line and not line.startswith('#') and '=' in line:
                key, value = line.split('=', 1)
                values[key] = value
    for name in ('PLATFORM_DOMAIN', 'ACME_EMAIL', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD',
                 'REDIS_PASSWORD', 'NEO4J_PASSWORD', 'BUSINESS_SERVICE_TOKEN',
                 'PLATFORM_ADMIN_USER', 'PLATFORM_ADMIN_PASSWORD', 'SILICONFLOW_API_KEY'):
        if not values.get(name) or values[name].startswith('YOUR_'):
            failures.append('Missing required setting: ' + name)
    if values.get('PLATFORM_DOMAIN'):
        try:
            socket.getaddrinfo(values['PLATFORM_DOMAIN'], 443)
        except OSError:
            failures.append('Domain DNS does not resolve; point it at the chosen control-plane host')
    for file in ('business.jar', 'site/index.html', 'secrets/application.key'):
        if not (root / file).is_file():
            failures.append('Missing artifact: ' + file)
    if (root / 'secrets/application.key').exists() and len((root / 'secrets/application.key').read_bytes()) != 32:
        failures.append('Application encryption key must contain exactly 32 bytes')
    if not shutil.which('docker'):
        failures.append('Docker Engine and Compose plugin are required')
    else:
        result = subprocess.run(['docker', 'compose', '--env-file', str(env), '-f', str(root / 'compose.yaml'), 'config', '--quiet'], capture_output=True)
        if result.returncode:
            failures.append('Docker Compose config validation failed; inspect configuration locally')
    print(json.dumps({'ready': not failures, 'failures': failures, 'checks_exclude': ['public TLS issuance', 'actual model request', 'payment', 'application delivery']}, indent=2))
    return bool(failures)


if __name__ == '__main__':
    sys.exit(main())
