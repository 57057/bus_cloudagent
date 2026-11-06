"""Administrative SSH transport for the explicitly authorized existing Linux ECS.

Host key must first be pinned through the authenticated Cloud Assistant API.
Private deploy key stays in ignored runtime/ecs-linux; never exposed to Agent tools.
"""
import json
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[1]


def connect():
    target = json.loads((ROOT / 'runtime/ecs-target.json').read_text(encoding='utf-8'))
    if target.get('OSType') != 'linux' or target.get('InstanceId') != 'i-bp17py7klx59epbxm93r':
        raise ValueError('Only the explicitly authorized Linux ECS is supported')
    keyroot = ROOT / 'runtime/ecs-linux'
    client = paramiko.SSHClient()
    client.load_host_keys(str(keyroot / 'known_hosts'))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    client.connect(target['PublicIpAddress']['IpAddress'][0], username='root',
                   key_filename=str(keyroot / 'id_rsa'), allow_agent=False,
                   look_for_keys=False, timeout=15, auth_timeout=15, banner_timeout=15)
    return client
