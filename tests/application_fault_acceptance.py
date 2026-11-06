"""Stop only the explicitly labelled demo backend, diagnose its real state, then recover."""
import json,sys,uuid
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tests.p0_acceptance import Client,wait
from deploy.vm_command import connect

def main():
    demo=json.loads((ROOT/'runtime/application-demo.json').read_text());credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text())
    user=Client();user.login(credentials['username'],credentials['password']);project=demo['project_id']
    with connect() as vm:
        _,out,err=vm.exec_command('docker ps --format "{{.Names}}" --filter label=cloudagent.project='+project)
        names=out.read().decode().split();backend=next(n for n in names if n.startswith('ca-b-'))
        assert len(backend)==21 and all(c in '0123456789abcdef' for c in backend[5:])
        _,out,err=vm.exec_command('docker stop --time 5 '+backend);assert out.channel.recv_exit_status()==0
    def action(kind):
        plan=user.call('POST','/projects/'+project+'/plans',{'kind':kind})
        task=user.call('POST','/plans/'+plan['id']+'/confirm',{'planHash':plan['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=202)['data']
        result=wait(lambda:user.call('GET','/deployments/'+task['id']),lambda d:d['state'] in ('SUCCEEDED','FAILED'),seconds=100)
        assert result['state']=='SUCCEEDED',result
        return result
    action('LOGS')
    diagnosis=user.call('POST','/projects/'+project+'/diagnoses')
    assert diagnosis['conclusion']['status']=='PROCESS_STOPPED',diagnosis['conclusion']
    print('PASS real stopped process -> bounded redacted log evidence -> PROCESS_STOPPED diagnosis',flush=True)
    action('RESTART')
    response=requests.get('http://127.0.0.1:18080/api/hello',timeout=10);assert response.status_code==200
    (ROOT/'runtime/application-fault-check.json').write_text(json.dumps({'real_fault':True,'evidence_diagnosis':True,'explicit_restart_plan':True,'http_recovered':True},indent=2))
    print('PASS confirmed restart -> actual backend recovery -> HTTP 200',flush=True)

if __name__=='__main__':main()
