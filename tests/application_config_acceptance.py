"""Versioned config and secret delivery through an actual Linux container release."""
import json,sys,uuid
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tests.p0_acceptance import Client,wait
from tests.p0_recovery import connection

def main():
    state=json.loads((ROOT/'runtime/application-acceptance-state.json').read_text());user=Client();user.login(state['username'],state['password']);project=state['project_id']
    stale=user.call('POST',f'/projects/{project}/plans',{'kind':'DEPLOY'})
    secret='app-secret-'+uuid.uuid4().hex;message='Configured sample '+uuid.uuid4().hex[:8]
    config=user.call('POST',f'/projects/{project}/config',{'variables':{'APP_MESSAGE':message},'secrets':{'API_TOKEN':secret}})
    assert secret not in json.dumps(config)
    user.call('POST',f"/plans/{stale['id']}/confirm",{'planHash':stale['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=409)
    plan=user.call('POST',f'/projects/{project}/plans',{'kind':'DEPLOY'});assert secret not in json.dumps(plan)
    task=user.call('POST',f"/plans/{plan['id']}/confirm",{'planHash':plan['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=202)['data']
    result=wait(lambda:user.call('GET','/deployments/'+task['id']),lambda r:r['state'] in ('SUCCEEDED','FAILED','MANUAL_REQUIRED'),seconds=100)
    assert result['state']=='SUCCEEDED',result
    assert secret not in json.dumps(result)
    assert requests.get('http://127.0.0.1:18080/api/hello',timeout=10).json()['message']==message
    assert requests.get('http://127.0.0.1:18080/api/protected',timeout=10).status_code==401
    assert requests.get('http://127.0.0.1:18080/api/protected',headers={'X-App-Token':secret},timeout=10).json()['authenticated']
    with connection() as db:
        with db.cursor() as c:
            c.execute('SELECT secrets_cipher FROM app_config_versions WHERE project_id=%s ORDER BY version DESC LIMIT 1',(project,));cipher=c.fetchone()['secrets_cipher'];assert secret not in cipher
    evidence=user.call('GET',f'/projects/{project}/evidence');assert secret not in json.dumps(evidence)
    state['configuration_verified']=True;(ROOT/'runtime/application-acceptance-state.json').write_text(json.dumps(state,indent=2))
    print('PASS encrypted database config, stale plan rejection, actual container config, secret-authenticated app endpoint and no API/evidence secret disclosure')

if __name__=='__main__':main()
