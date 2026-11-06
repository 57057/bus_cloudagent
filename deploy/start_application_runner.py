"""Start/update registered local application runners, preserving their private journals.

The registry contains only local VM paths, never user credentials. Registration
still requires an administrator-created one-time token through the normal API.
"""
from pathlib import Path
import argparse,json,re,sys,subprocess
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from deploy.vm_command import connect

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--update',action='store_true');args=parser.parse_args()
    registry=ROOT/'runtime/application-runners.json'
    if not registry.exists():return
    roots=json.loads(registry.read_text())
    with connect() as client:
        for root in roots:
            if not re.fullmatch(r'/home/cloudagent/app-runner-[a-f0-9]{10}',root):raise ValueError('Runner path outside managed namespace')
            _,out,err=client.exec_command("pgrep -f '^python3 "+root+"/runner.py'")
            pids=out.read().decode().split()
            if pids and not args.update:continue
            for pid in pids:
                if not pid.isdecimal():raise ValueError('Invalid runner process ID')
                _,out,err=client.exec_command('kill '+pid);out.channel.recv_exit_status()
            with client.open_sftp() as sftp:sftp.put(str(ROOT/'deployment-runner/runner.py'),root+'/runner.py')
            _,out,err=client.exec_command('nohup python3 '+root+'/runner.py --root '+root+' > '+root+'/runner.log 2>&1 < /dev/null &')
            if out.channel.recv_exit_status()!=0:raise RuntimeError('Runner could not start')
            print('Application Runner started; credential and journal preserved')
    vbox='C:/Program Files/Oracle/VirtualBox/VBoxManage.exe'
    info=subprocess.run([vbox,'showvminfo','CloudAgent-Docker','--machinereadable'],capture_output=True,text=True,check=True).stdout
    if 'application,tcp,127.0.0.1,18080' not in info:
        subprocess.run([vbox,'controlvm','CloudAgent-Docker','natpf1','application,tcp,127.0.0.1,18080,,18080'],check=True)

if __name__=='__main__':main()
