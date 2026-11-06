"""Initialize a fresh demo environment without copying local data or credentials.

Model/payment credentials are filled locally after initialization, never printed.
Run with the Python standard library on the control-plane Linux host.
"""
import argparse
import os
from pathlib import Path
import re
import secrets


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--domain', required=True)
    parser.add_argument('--email', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'(?=.{4,253}$)[a-z0-9]+(?:[.-][a-z0-9]+)+', args.domain):
        parser.error('Supply a DNS hostname, without scheme, port or path')
    if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', args.email):
        parser.error('A certificate contact email is required')
    root = Path(__file__).resolve().parent
    env = root / '.env'
    key = root / 'secrets/application.key'
    if env.exists() or key.exists():
        raise SystemExit('Existing config/key detected; preserve them, do not regenerate encryption keys')
    os.umask(0o077)
    values = dict(PLATFORM_DOMAIN=args.domain, ACME_EMAIL=args.email)
    for name in ('MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'REDIS_PASSWORD',
                 'NEO4J_PASSWORD', 'BUSINESS_SERVICE_TOKEN'):
        values[name] = secrets.token_hex(32)
    values['PLATFORM_ADMIN_PASSWORD'] = 'Cloud-' + secrets.token_hex(20) + '7a'
    template = (root / 'config.example.env').read_text(encoding='utf-8')
    for name, value in values.items():
        template = re.sub(r'^' + name + r'=.*$', name + '=' + value, template, flags=re.M)
    # O_EXCL prevents concurrent runs from overwriting an existing config.
    with env.open('x', encoding='utf-8') as stream:
        stream.write(template)
    key.parent.mkdir(exist_ok=True, mode=0o700)
    with key.open('xb') as stream:
        stream.write(secrets.token_bytes(32))
    env.chmod(0o600)
    key.chmod(0o600)
    print('Private .env and application key created. Fill model/sandbox credentials locally before startup.')


if __name__ == '__main__':
    main()
