"""Deploy the explicitly authorized 2GB single-host demo. No purchases or resizing.

Fresh cloud database; original local data untouched. Credentials use verified SSH/SFTP,
never Cloud Assistant command content or shell arguments. Public HTTP stays preview-only.
"""
import hashlib
import argparse
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tarfile
import time
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from deploy.ecs_linux_ssh import connect


def run(client, command, timeout=900):
    _, stdout, stderr = client.exec_command(command, timeout=timeout)
    channel = stdout.channel
    while not channel.exit_status_ready() or channel.recv_ready() or channel.recv_stderr_ready():
        if channel.recv_ready():
            sys.stdout.buffer.write(channel.recv(65536)); sys.stdout.buffer.flush()
        if channel.recv_stderr_ready():
            sys.stderr.buffer.write(channel.recv_stderr(65536)); sys.stderr.buffer.flush()
        time.sleep(.1)
    if channel.recv_exit_status() != 0:
        raise RuntimeError('Remote deployment stage failed; inspect stage output before retrying')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--resume',action='store_true',help='Start the staged release using already transferred images; preserve data and credentials')
    parser.add_argument('--stage-only',action='store_true',help='Upload files and private settings, without pulling/building/starting images')
    parser.add_argument('--offline-images',action='store_true',help='Use previously transferred approved images without contacting registries')
    args=parser.parse_args()
    if args.resume:
        state_path=ROOT/'runtime/ecs-linux/deployment.json'
        state=json.loads(state_path.read_text())
        remote=state['remote']
        if not re.fullmatch(r'/opt/cloudagent/releases/[a-f0-9]{12}/control',remote):
            raise ValueError('Unexpected managed release path')
        with connect() as client:
            run(client,'cd '+remote+' && docker compose config --quiet && docker compose up -d --no-build --pull never && docker compose ps',timeout=180)
        state['status']='STARTED_HEALTH_CHECK_PENDING'
        state_path.write_text(json.dumps(state,indent=2))
        return
    subprocess.run([sys.executable, str(ROOT / 'deploy/build_cloud_bundle.py'), '--skip-build'], check=True)
    base = max((ROOT / 'runtime/cloud-releases').glob('*/control'), key=lambda p: p.stat().st_mtime)
    release = base.parent.name
    for source, target in (('compose.lite.yaml', 'compose.yaml'), ('Caddyfile.lite', 'Caddyfile'), ('requirements.lite.lock.txt', 'requirements.txt')):
        shutil.copyfile(ROOT / 'deploy/cloud' / source, base / target)
    shutil.copyfile(ROOT/'deploy/cloud/compose.native.yaml',base/'compose.native.yaml')
    shutil.copyfile(ROOT/'deploy/cloud/lite-deployment-config.js',base/'site/deployment-config.js')
    archive = base.parent / 'control-lite.tar.gz'
    with tarfile.open(archive, 'w:gz') as package:
        package.add(base, arcname='control')
    private = ROOT / 'runtime/ecs-linux'
    settings_path = private / 'cloud.env'
    local_agent = dotenv_values(ROOT / 'agent/.env')
    local_business = dotenv_values(ROOT / 'business-java/.env')
    if not settings_path.exists():
        values = {key: secrets.token_hex(32) for key in ('MYSQL_PASSWORD','MYSQL_ROOT_PASSWORD','REDIS_PASSWORD','BUSINESS_SERVICE_TOKEN')}
        values.update(PLATFORM_ADMIN_USER='cloudadmin', PLATFORM_ADMIN_PASSWORD='Cloud-'+secrets.token_hex(20)+'7a',
                      SILICONFLOW_API_KEY=local_agent.get('SILICONFLOW_API_KEY',''), MODEL=local_agent.get('MODEL','deepseek-ai/DeepSeek-V3.2'),
                      ALIPAY_SANDBOX_APP_ID=local_business.get('ALIPAY_SANDBOX_APP_ID',''), ALIPAY_SANDBOX_SELLER_ID=local_business.get('ALIPAY_SANDBOX_SELLER_ID',''))
        if not values['SILICONFLOW_API_KEY']: raise ValueError('Model provider key missing')
        settings_path.write_text(''.join(k+'='+v+'\n' for k,v in values.items()), encoding='utf-8')
        (private / 'cloud-admin.json').write_text(json.dumps({'username':values['PLATFORM_ADMIN_USER'],'password':values['PLATFORM_ADMIN_PASSWORD']},indent=2))
    key = private / 'application.key'
    if not key.exists(): key.write_bytes(secrets.token_bytes(32))
    remote = '/opt/cloudagent/releases/'+release
    with connect() as client:
        run(client, 'install -d -m 700 /opt/cloudagent/releases /opt/cloudagent/private /var/lib/cloudagent; install -d -m 755 '+remote)
        with client.open_sftp() as sftp:
            sftp.put(str(archive),remote+'/control-lite.tar.gz')
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        run(client, "printf '%s  %s\\n' '"+digest+"' '"+remote+"/control-lite.tar.gz' | sha256sum -c - && tar -xzf "+remote+'/control-lite.tar.gz -C '+remote)
        with client.open_sftp() as sftp:
            sftp.put(str(settings_path), remote+'/control/.env'); sftp.chmod(remote+'/control/.env', 0o600)
        run(client, 'install -d -m 700 -o 10001 -g 10001 /opt/cloudagent/private/secrets; ln -s /opt/cloudagent/private/secrets '+remote+'/control/secrets')
        with client.open_sftp() as sftp:
            sftp.put(str(key),'/opt/cloudagent/private/secrets/application.key')
            for setting, filename in (('ALIPAY_PRIVATE_KEY_FILE','alipay-private.pem'),('ALIPAY_PUBLIC_KEY_FILE','alipay-public.pem')):
                path = Path(local_business.get(setting) or '__not_configured__')
                if path.is_file(): sftp.put(str(path), '/opt/cloudagent/private/secrets/'+filename)
        run(client, 'chown -R 10001:10001 /opt/cloudagent/private/secrets; chmod 600 /opt/cloudagent/private/secrets/*; '+
            "if ! swapon --noheadings --show=NAME | grep -q .; then test ! -e /var/lib/cloudagent/swapfile; fallocate -l 2G /var/lib/cloudagent/swapfile; chmod 600 /var/lib/cloudagent/swapfile; mkswap /var/lib/cloudagent/swapfile >/dev/null; swapon /var/lib/cloudagent/swapfile; printf '%s\\n' '/var/lib/cloudagent/swapfile none swap sw 0 0' >> /etc/fstab; fi")
        (private/'deployment.json').write_text(json.dumps({'release':release,'remote':remote+'/control','status':'STAGED','bundle_sha256':digest},indent=2))
        if args.stage_only:
            print('Files staged. Build/transfer approved images, then use --resume.');return
        if not args.offline_images:
            images='redis gateway' if dotenv_values(settings_path).get('COMPOSE_FILE') else 'mysql redis gateway'
            run(client, 'cd '+remote+'/control && docker compose config --quiet && docker compose pull '+images, timeout=900)
            run(client, 'cd '+remote+'/control && docker compose build --progress plain', timeout=1500)
        run(client, 'cd '+remote+'/control && docker compose up -d --no-build --pull never && docker compose ps', timeout=300)
        run(client, 'ln -sfn '+remote+'/control /opt/cloudagent/current; free -m')
    print('Deployment requested; health and business acceptance are still required.')


if __name__ == '__main__':
    main()
