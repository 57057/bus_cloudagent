"""Start the local encrypted browser transport; all application services stay on ECS."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import argparse

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--restart',action='store_true');args=parser.parse_args()
    if args.restart:
        state=ROOT/'runtime/ecs-linux/tunnel-process.json'
        if state.exists():
            pid=int(json.loads(state.read_text())['pid'])
            if os.name!='nt':raise RuntimeError('Verify the recorded tunnel process manually on this platform')
            result=subprocess.run(['powershell.exe','-NoProfile','-Command',f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').CommandLine"],capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
            command=result.stdout.strip()
            if command:
                if str(ROOT/'deploy/cloud_tunnel.py') not in command or str(Path(sys.executable)) not in command:
                    raise RuntimeError('Tunnel PID identity mismatch; no process was stopped')
                subprocess.run(['taskkill.exe','/PID',str(pid),'/T','/F'],capture_output=True,check=True,creationflags=subprocess.CREATE_NO_WINDOW)
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1',18780))==0:
            print('Port 18780 already listening; verify the existing cloud portal before using it')
            return
    folder=ROOT/'runtime/ecs-linux'
    with (folder/'tunnel.log').open('ab') as log:
        process=subprocess.Popen([sys.executable,str(ROOT/'deploy/cloud_tunnel.py')],cwd=ROOT,
            stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    (folder/'tunnel-process.json').write_text(json.dumps({'pid':process.pid,'local_port':18780,'destination':'ECS loopback 8080'}))
    for _ in range(30):
        if process.poll() is not None:raise RuntimeError('Tunnel exited; see runtime/ecs-linux/tunnel.log')
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1',18780))==0:
                print('Encrypted cloud portal: http://127.0.0.1:18780/')
                return
        time.sleep(.5)
    raise TimeoutError('Inspect the tunnel log; no cloud application service was stopped')


if __name__=='__main__':main()
