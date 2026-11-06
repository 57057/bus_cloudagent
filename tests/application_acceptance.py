"""Actual HTTP/MySQL/Linux Docker acceptance for the platform pool.

Only touches unique test records and containers labelled with the test allocation.
The VM's existing Milvus/other applications are never cleared or pruned.
"""
from pathlib import Path
import json
import sys
import time
import uuid
import requests

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from tests.p0_acceptance import Client,wait
from deploy.vm_command import connect


def setup():
    stamp=uuid.uuid4().hex[:10]
    admin=Client(True);credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(credentials['username'],credentials['password'])
    user=Client();password='TestingPass123';username='app_'+stamp
    user.call('POST','/auth/register',{'username':username,'password':password,'agreement':True});user.login(username,password)
    sku={'id':'pool-'+stamp,'product_id':'ecs','name':'Platform Linux Pool '+stamp,'region':'local-linux','zone':'local-a','cpu':2,'memory_gb':4,'base_cents':100,'disk_cents':0,'bandwidth_cents':0,'capacity':0,'active':True,'adapter':'POOL','images':'ubuntu-22','min_disk':40,'max_disk':500,'max_bandwidth':100,'periods':'1,3,12','grace_days':7,'refund_days':7,'reason':'Application pool acceptance'}
    admin.call('POST','/skus',sku)
    host=admin.call('POST','/hosts',{'providerId':'local-acceptance-'+stamp,'skuId':sku['id'],'name':'Local Linux '+stamp,'endpoint':'127.0.0.1','procurementExpiresAt':'2030-01-01T00:00:00Z','mode':'LOCAL_LINUX'})
    registration=admin.call('POST','/hosts/'+host['id']+'/registration-tokens')
    # Preserve the credential only in local ignored files and the VM's private runner directory.
    config={'base_url':'http://10.0.2.2:5000','allow_local_http':True,'registration_token':registration['token'],
            'backend_image':'cloudagent-demo-backend:1','frontend_image':'cloudagent-demo-frontend:1','public_host':'127.0.0.1','application_port':18080}
    runner_root='/home/cloudagent/app-runner-'+stamp
    registry=ROOT/'runtime/application-runners.json'
    existing=json.loads(registry.read_text()) if registry.exists() else []
    if existing:raise RuntimeError('This VM already has a managed Runner; reuse existing acceptance state instead of assigning the same physical VM twice')
    with connect() as vm:
        _,out,err=vm.exec_command('mkdir -p '+runner_root+' && chmod 700 '+runner_root);assert out.channel.recv_exit_status()==0
        with vm.open_sftp() as sftp:
            sftp.put(str(ROOT/'deployment-runner/runner.py'),runner_root+'/runner.py')
            with sftp.file(runner_root+'/bootstrap.json','w') as f:f.write(json.dumps(config))
            sftp.chmod(runner_root+'/bootstrap.json',0o600)
        _,out,err=vm.exec_command('nohup python3 '+runner_root+'/runner.py --root '+runner_root+' --config '+runner_root+'/bootstrap.json > '+runner_root+'/runner.log 2>&1 < /dev/null &')
        assert out.channel.recv_exit_status()==0
    report={'username':username,'password':password,'host_id':host['id'],'sku':sku,'runner_root':runner_root,'registration_token':registration['token']}
    (ROOT/'runtime/application-acceptance-state.json').write_text(json.dumps(report,indent=2))
    registry.write_text(json.dumps([runner_root]))
    print('Setup complete; runner bootstrap started',flush=True)


def exercise():
    path=ROOT/'runtime/application-acceptance-state.json';state=json.loads(path.read_text())
    admin=Client(True);credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(credentials['username'],credentials['password'])
    user=Client();user.login(state['username'],state['password'])
    wait(lambda:admin.call('GET','/hosts'),lambda hosts:any(h['id']==state['host_id'] and h['pool_state']=='IDLE' for h in hosts),seconds=40)
    print('PASS actual Linux Runner registration and capabilities',flush=True)
    cfg={'sku_id':state['sku']['id'],'image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'Application pool test'}
    if not state.get('order_id'):
        quote=user.call('POST','/quotes',cfg);order=user.call('POST','/orders',{'quote_id':quote['id']},key=uuid.uuid4().hex)
        state['order_id']=order['id'];path.write_text(json.dumps(state,indent=2))
        pay=user.call('POST',f"/orders/{order['id']}/payments");user.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True})
    order=wait(lambda:user.call('GET','/orders/'+state['order_id']),lambda o:o['state']=='COMPLETED',seconds=35)
    allocations=user.call('GET','/allocations');allocation=next(a for a in allocations if a['order_id']==order['id'])
    state.update({'allocation_id':allocation['id'],'instance_id':allocation['instance_id']});path.write_text(json.dumps(state,indent=2))
    print('PASS payment -> exclusive concrete instance allocation',flush=True)
    quote=user.call('POST','/quotes',cfg)
    user.call('POST','/orders',{'quote_id':quote['id']},key=uuid.uuid4().hex,expected=409)
    print('PASS stock exhaustion rejects a second reservation',flush=True)
    if not state.get('project_id'):
        project=user.call('POST','/projects',{'name':'Spring + React acceptance','allocationId':allocation['id']})
        state['project_id']=project['id'];path.write_text(json.dumps(state,indent=2))
    project=state['project_id']
    def action(kind,target=None):
        plan=user.call('POST',f'/projects/{project}/plans',{'kind':kind,'targetDeploymentId':target})
        user.call('POST',f"/plans/{plan['id']}/confirm",{'planHash':'tampered','confirmed':True},key=uuid.uuid4().hex,expected=409)
        key=uuid.uuid4().hex
        task=user.call('POST',f"/plans/{plan['id']}/confirm",{'planHash':plan['plan_hash'],'confirmed':True},key=key,expected=202)['data']
        repeat=user.call('POST',f"/plans/{plan['id']}/confirm",{'planHash':plan['plan_hash'],'confirmed':True},key=key,expected=202)['data']
        assert repeat['id']==task['id']
        result=wait(lambda:user.call('GET','/deployments/'+task['id']),lambda t:t['state'] in ('SUCCEEDED','FAILED','MANUAL_REQUIRED'),seconds=100)
        assert result['state']=='SUCCEEDED',result
        return result
    if not state.get('deployment_id'):
        result=action('DEPLOY');state['deployment_id']=result['id'];path.write_text(json.dumps(state,indent=2))
    print('PASS immutable-image deployment and HTTP health; plan tamper/idempotency checks',flush=True)
    action('RESTART');print('PASS real container restart',flush=True)
    action('ROLLBACK',state['deployment_id']);print('PASS application release rollback',flush=True)
    action('LOGS');print('PASS bounded runtime evidence',flush=True)
    state['checks_passed']=True;path.write_text(json.dumps(state,indent=2))


def security_and_reclaim():
    path=ROOT/'runtime/application-acceptance-state.json';state=json.loads(path.read_text())
    user=Client();user.login(state['username'],state['password'])
    admin=Client(True);credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(credentials['username'],credentials['password'])
    other=Client();name='app_other_'+uuid.uuid4().hex[:10]
    other.call('POST','/auth/register',{'username':name,'password':'TestingPass123','agreement':True});other.login(name,'TestingPass123')
    for endpoint in ('/projects/'+state['project_id'],'/deployments/'+state['deployment_id'],'/projects/'+state['project_id']+'/evidence'):
        other.call('GET',endpoint,expected=404)
    other.call('POST','/admin/hosts/'+state['host_id']+'/registration-tokens',expected=403)
    response=requests.post('http://127.0.0.1:5000/internal/runner/tasks/claim',headers={'Authorization':'Bearer '+user.token},timeout=10)
    assert response.status_code==401
    print('PASS project/deployment isolation, administrator boundary and separate Runner identity',flush=True)
    diagnosis=user.call('POST','/projects/'+state['project_id']+'/diagnoses')
    assert diagnosis['conclusion']['engine']=='EVIDENCE_RULES'
    response=user.session.get('http://127.0.0.1:5000/api/deployments/'+state['deployment_id']+'/events/stream',headers={'Authorization':'Bearer '+user.token},stream=True,timeout=20)
    assert response.status_code==200
    assert next(response.iter_lines())
    response.close()
    print('PASS evidence-based diagnosis and real event SSE',flush=True)
    # Retain a plan to prove it cannot execute on the same host after reassignment.
    stale=user.call('POST','/projects/'+state['project_id']+'/plans',{'kind':'RESTART'})
    user.call('POST','/instances/'+state['instance_id']+'/actions',{'action':'RELEASE','confirmed':True},key=uuid.uuid4().hex)
    wait(lambda:admin.call('GET','/hosts'),lambda hosts:any(h['id']==state['host_id'] and h['pool_state']=='IDLE' for h in hosts),seconds=50)
    wait(lambda:user.call('GET','/instances/'+state['instance_id']),lambda i:i['state']=='RELEASED',seconds=30)
    user.call('POST','/plans/'+stale['id']+'/confirm',{'planHash':stale['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=404)
    print('PASS release -> container/network cleanup -> idle; old lease plan rejected',flush=True)
    with connect() as vm:
        _,out,err=vm.exec_command("docker ps -aq --filter label=cloudagent.allocation="+state['allocation_id']);assert not out.read().decode().strip()
        _,out,err=vm.exec_command("docker inspect milvus-standalone --format '{{.State.Health.Status}}'");assert out.read().decode().strip()=='healthy'
    cfg={'sku_id':state['sku']['id'],'image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'Reallocation test'}
    q=other.call('POST','/quotes',cfg);order=other.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex)
    pay=other.call('POST','/orders/'+order['id']+'/payments');other.call('POST','/payments/'+pay['id']+'/simulate',{'confirmed':True})
    wait(lambda:other.call('GET','/orders/'+order['id']),lambda o:o['state']=='COMPLETED',seconds=30)
    new=next(a for a in other.call('GET','/allocations') if a['order_id']==order['id'])
    old=next(a for a in user.call('GET','/allocations') if a['id']==state['allocation_id'])
    assert new['host_id']==old['host_id'] and new['generation']>old['generation']
    user.call('POST','/projects',{'name':'Forged new lease','allocationId':new['id']},expected=404)
    print('PASS same host safely reallocated with new generation; previous user cannot access it',flush=True)
    # Return the test asset to an idle state rather than leaving test customer inventory occupied.
    other.call('POST','/instances/'+new['instance_id']+'/actions',{'action':'RELEASE','confirmed':True},key=uuid.uuid4().hex)
    wait(lambda:admin.call('GET','/hosts'),lambda hosts:any(h['id']==state['host_id'] and h['pool_state']=='IDLE' for h in hosts),seconds=50)
    state['security_and_reclaim_passed']=True;path.write_text(json.dumps(state,indent=2))


if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='setup':setup()
    elif len(sys.argv)>1 and sys.argv[1]=='reclaim':security_and_reclaim()
    else:exercise()
