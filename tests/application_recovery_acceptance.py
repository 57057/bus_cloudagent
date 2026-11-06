"""Expire an unexecuted test task lease; verify no blind retry and administrator reconciliation."""
import json,sys,uuid,subprocess
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tests.p0_acceptance import Client,wait
from tests.p0_recovery import connection
from deploy.vm_command import connect

def main():
    state=json.loads((ROOT/'runtime/application-acceptance-state.json').read_text());demo=json.loads((ROOT/'runtime/application-demo.json').read_text())
    user=Client();credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text());user.login(credentials['username'],credentials['password'])
    root=state['runner_root']
    with connect() as vm:
        _,out,err=vm.exec_command("pgrep -f '^python3 "+root+"/runner.py'");pids=out.read().decode().split();assert len(pids)==1
        _,out,err=vm.exec_command('kill '+str(int(pids[0])));assert out.channel.recv_exit_status()==0
        with vm.open_sftp() as sf:
            with sf.file(root+'/config.json') as f:runner=json.loads(f.read())
    try:
        plan=user.call('POST','/projects/'+demo['project_id']+'/plans',{'kind':'RESTART'})
        task=user.call('POST','/plans/'+plan['id']+'/confirm',{'planHash':plan['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=202)['data']
        headers={'Authorization':'Bearer '+runner['credential']}
        claimed=requests.post('http://127.0.0.1:5000/internal/runner/tasks/claim',json={},headers=headers,timeout=10).json()['data']['task']
        assert claimed['id']==task['id']
        with connection() as db:
            with db.cursor() as c:c.execute('UPDATE app_deployments SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=%s',(task['id'],))
            db.commit()
        r=requests.post('http://127.0.0.1:5000/internal/runner/tasks/claim',json={},headers=headers,timeout=10);assert r.json()['data']['blocked']
        stale=requests.post('http://127.0.0.1:5000/internal/runner/tasks/'+task['id']+'/complete',json={'attempt':claimed['attempt'],'success':True,'result':{'health':True}},headers=headers,timeout=10)
        assert stale.status_code==409
        user.admin=True;user.call('POST','/deployments/'+task['id']+'/reconcile',{'reason':'Controlled test Runner was stopped before claim; verified no external command executed'});user.admin=False
        assert user.call('GET','/deployments/'+task['id'])['state']=='FAILED'
        print('PASS expired lease -> MANUAL_REQUIRED; stale completion rejected; explicit administrator reconciliation',flush=True)
    finally:
        subprocess.run([sys.executable,str(ROOT/'deploy/start_application_runner.py'),'--update'],check=True)
    plan=user.call('POST','/projects/'+demo['project_id']+'/plans',{'kind':'RESTART'})
    task=user.call('POST','/plans/'+plan['id']+'/confirm',{'planHash':plan['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=202)['data']
    result=wait(lambda:user.call('GET','/deployments/'+task['id']),lambda d:d['state'] in ('SUCCEEDED','FAILED'),seconds=100);assert result['state']=='SUCCEEDED',result
    (ROOT/'runtime/application-recovery-check.json').write_text(json.dumps({'lease_expiry_blocked':True,'stale_result_rejected':True,'admin_reconciliation':True,'new_confirmed_execution_succeeded':True},indent=2))
    print('PASS new confirmed operation succeeds after safe reconciliation',flush=True)

if __name__=='__main__':main()
