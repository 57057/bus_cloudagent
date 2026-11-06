"""Pool lease renewal and actual application stop/start using the original resource APIs."""
import json,sys,uuid
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT));sys.path.insert(0,str(ROOT/'tests'))
from p0_acceptance import Client,wait

def main():
    demo=json.loads((ROOT/'runtime/application-demo.json').read_text());credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text())
    c=Client();c.login(credentials['username'],credentials['password']);instance=demo['instance_id']
    original=c.call('GET','/instances/'+instance)
    q=c.call('POST','/instances/'+instance+'/renewal-quotes',{'months':1})
    o=c.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex)
    p=c.call('POST','/orders/'+o['id']+'/payments');c.call('POST','/payments/'+p['id']+'/simulate',{'confirmed':True})
    wait(lambda:c.call('GET','/orders/'+o['id']),lambda x:x['state']=='COMPLETED',seconds=40)
    updated=c.call('GET','/instances/'+instance);assert updated['expires_at']>original['expires_at']
    for action,state in [('STOP','STOPPED'),('START','RUNNING')]:
        c.call('POST','/instances/'+instance+'/actions',{'action':action},key=uuid.uuid4().hex)
        wait(lambda:c.call('GET','/instances/'+instance),lambda x:x['state']==state,seconds=60)
        assert c.call('GET','/projects/'+demo['project_id'])['id']==demo['project_id']
        print('PASS real pool '+action+' and read-only project visibility',flush=True)
    assert requests.get('http://127.0.0.1:18080/api/hello',timeout=10).status_code==200
    (ROOT/'runtime/application-resource-check.json').write_text(json.dumps({'renewed':True,'actual_stop_start':True,'stopped_project_readable':True,'http_healthy':True},indent=2))

if __name__=='__main__':main()
