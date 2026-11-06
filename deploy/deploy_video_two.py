"""Deploy the selected desktop project's room service to the authorized ECS.

Copies only server sources, the shared URL parser and the installed pure-JS ws
dependency. Does not modify the desktop project or copy Tunnel/Android/runtime data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import uuid
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from deploy.ecs_linux_ssh import connect
from deploy.deploy_ecs_lite import run


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--source',required=True);args=parser.parse_args()
    source=Path(args.source).resolve()
    package=json.loads((source/'package.json').read_text(encoding='utf-8'))
    if package.get('name')!='bili-together':raise ValueError('Unexpected source project')
    lock=json.loads((source/'package-lock.json').read_text(encoding='utf-8'))
    installed=json.loads((source/'node_modules/ws/package.json').read_text())
    if installed['version']!=lock['packages']['node_modules/ws']['version']:
        raise ValueError('Installed ws dependency differs from package lock')
    release=uuid.uuid4().hex[:12];work=ROOT/'runtime/video-two'/release
    files=['package.json','package-lock.json','server/index.js','server/rooms.js','extension/sites.js']
    ws=source/'node_modules/ws'
    files += [(p.relative_to(source)).as_posix() for p in ws.rglob('*') if p.is_file() and (p.suffix in ('.js','.mjs','.json') or p.name=='LICENSE')]
    for name in files:
        destination=work/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source/name,destination)
    manifest={name:hashlib.sha256((work/name).read_bytes()).hexdigest() for name in files}
    (work/'manifest.json').write_text(json.dumps({'release':release,'ws_version':installed['version'],'files':manifest},indent=2))
    archive=work.with_suffix('.tar.gz')
    with tarfile.open(archive,'w:gz') as t:t.add(work,arcname=release)
    remote='/opt/video-two/releases/'+release
    with connect() as client:
        run(client,'install -d -m 755 /opt/video-two/releases; if ! id video-two >/dev/null 2>&1; then useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin video-two; fi')
        with client.open_sftp() as sftp:sftp.put(str(archive),'/opt/video-two/releases/'+release+'.tar.gz')
        digest=hashlib.sha256(archive.read_bytes()).hexdigest()
        run(client,"printf '%s  %s\\n' '"+digest+"' '/opt/video-two/releases/"+release+".tar.gz' | sha256sum -c - && tar -xzf /opt/video-two/releases/"+release+'.tar.gz -C /opt/video-two/releases && node --check '+remote+'/server/index.js')
        with client.open_sftp() as sftp:
            sftp.put(str(ROOT/'deploy/video-two/video-two.service'),'/etc/systemd/system/video-two.service')
        run(client,'ln -sfn '+remote+' /opt/video-two/current; systemctl daemon-reload; systemctl enable --now video-two; systemctl restart video-two; systemctl is-active video-two',timeout=40)
        run(client,'for attempt in $(seq 1 20); do if curl -fsS http://127.0.0.1:8787/health 2>/dev/null; then exit 0; fi; sleep 1; done; exit 1',timeout=25)
    state={'release':release,'remote':remote,'source':str(source),'ws_version':installed['version'],'bundle_sha256':digest,'node_listen':'127.0.0.1:8787','tls_pending':True}
    (ROOT/'runtime/video-two/deployment.json').write_text(json.dumps(state,indent=2))
    print('Video Two room service deployed; trusted public TLS remains to be verified.')


if __name__=='__main__':main()
