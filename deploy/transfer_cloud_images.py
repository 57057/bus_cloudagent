"""Transfer trusted locally built/pulled images to the authorized ECS over pinned SSH."""
import argparse
import hashlib
from pathlib import Path
import shlex
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from deploy.vm_command import connect as build_vm
from deploy.ecs_linux_ssh import connect as cloud
from deploy.deploy_ecs_lite import run

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {'caddy:2.10.2-alpine', 'mysql:8.4', 'redis:7.4-alpine', 'cloudagent-business:lite',
           'cloudagent-agent:lite', 'cloudagent-demo-backend:1', 'cloudagent-demo-frontend:1', 'cloudagent-demo-frontend:lite'}


def transfer(images):
    if not images or not set(images) <= ALLOWED:
        raise ValueError('Only approved deployment images may be transferred')
    identity = hashlib.sha256('|'.join(images).encode()).hexdigest()[:12]
    remote = '/home/cloudagent/cloud-images-'+identity+'.tar.gz'
    local = ROOT / 'runtime/ecs-linux' / ('images-'+identity+'.tar.gz')
    with build_vm() as client:
        run(client, 'set -o pipefail; docker image save '+shlex.join(images)+' | gzip -1 > '+shlex.quote(remote), timeout=300)
        with client.open_sftp() as sftp:
            sftp.get(remote, str(local))
    digest = hashlib.sha256(local.read_bytes()).hexdigest()
    print('Exported approved images; compressed MiB:',round(local.stat().st_size / 1024**2,1),flush=True)
    with cloud() as client:
        target = '/opt/cloudagent/releases/images-'+identity+'.tar.gz'
        with client.open_sftp() as sftp:
            last = [0]
            def progress(done, total):
                if done - last[0] >= 32 * 1024**2:
                    last[0] = done
                    print('Image upload MiB:',round(done/1024**2),'/',round(total/1024**2),flush=True)
            sftp.put(str(local),target,callback=progress)
        run(client, "printf '%s  %s\\n' '"+digest+"' '"+target+"' | sha256sum -c - && docker image load -i "+target, timeout=300)
    print('Approved images loaded on ECS; SHA256 verified',flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('images',nargs='+');args=parser.parse_args()
    transfer(args.images)
