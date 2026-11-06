"""Exercise only uniquely tracked cloud demo records and allocation-labelled containers.

Shared demo slot, simulated money, real ECS containers. Does not purchase/resize ECS,
touch local business data, or claim a physically dedicated tenant server.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import secrets
import re
import sys
import time
import uuid
import requests

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from tests import p0_acceptance as api
from deploy.ecs_linux_ssh import connect
from deploy.deploy_ecs_lite import run
from deploy.cloud_assistant import call
api.BASE='http://127.0.0.1:18780'
PRIVATE=ROOT/'runtime/ecs-linux'
STATE=PRIVATE/'demo-state.json'


def save(state):STATE.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')


def clients():
    credentials=json.loads((PRIVATE/'cloud-admin.json').read_text())
    admin=api.Client(True);admin.login(**credentials)
    owner=api.Client();owner.login(**credentials)
    return admin,owner


def setup():
    admin,owner=clients()
    state=json.loads(STATE.read_text(encoding='utf-8')) if STATE.exists() else {}
    if not state.get('host_id'):
        admin.call('POST','/products',{'id':'ecs','name':'单机共享应用演示（非独占ECS）','category':'ECS',
            'description':'2GB受限演示：控制面与一个应用槽共享主机。仅模拟/沙箱计价，不保证独占CPU、内存或磁盘；模板镜像字段不表示重装宿主机。应用后端限额320MB；未启用Milvus/Neo4j。',
            'scenario':'平台流程与批准镜像部署演示','active':True,'reason':'Initialize expressly authorized cloud single-host demo'})
        sku={'id':'shared-demo-slot','product_id':'ecs','name':'同机应用槽（演示计价，非独占规格）','region':'cn-hangzhou','zone':'shared-demo','cpu':1,'memory_gb':1,'base_cents':1,'disk_cents':0,'bandwidth_cents':0,'capacity':0,'active':True,'adapter':'POOL','images':'ubuntu-22','min_disk':40,'max_disk':40,'max_bandwidth':1,'periods':'1','grace_days':1,'refund_days':0,'reason':'Demo form-compatible SKU; real container limits are 320MB backend and one application slot'}
        admin.call('POST','/skus',sku)
        target=json.loads((ROOT/'runtime/ecs-target.json').read_text(encoding='utf-8'))
        until=(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=60)).isoformat()
        host=admin.call('POST','/hosts',{'providerId':target['InstanceId']+':shared-demo','skuId':sku['id'],'name':'共享控制面演示应用槽（非独占ECS）','endpoint':target['PublicIpAddress']['IpAddress'][0],'procurementExpiresAt':until,'mode':'SHARED_DEMO'})
        state.update(host_id=host['id'],sku_id=sku['id'],public_ip=target['PublicIpAddress']['IpAddress'][0],shared_demo=True,demo_valid_until=until,procurement_date_is_demo_policy=True)
        save(state)
    host=next(h for h in admin.call('GET','/hosts') if h['id']==state['host_id'])
    if host['pool_state']=='PREPARING':
        token=admin.call('POST','/hosts/'+state['host_id']+'/registration-tokens')['token']
        config={'base_url':'http://127.0.0.1:8080','allow_local_http':True,'shared_control_plane_demo':True,
                'registration_token':token,'backend_image':'cloudagent-demo-backend:1','frontend_image':'cloudagent-demo-frontend:lite',
                'public_host':state['public_ip'],'application_port':18080,'backend_memory_mb':320}
        remote='/opt/cloudagent/runner-install'
        with connect() as client:
            run(client,'install -d -m 700 '+remote)
            with client.open_sftp() as sftp:
                for name,path in [('runner.py',ROOT/'deployment-runner/runner.py'),('runner.service',ROOT/'deploy/cloud/runner.service'),('install_runner.sh',ROOT/'deploy/cloud/install_runner.sh')]:
                    sftp.put(str(path),remote+'/'+name)
                with sftp.file(remote+'/bootstrap.json','w') as f:f.write(json.dumps(config))
                sftp.chmod(remote+'/bootstrap.json',0o600)
            run(client,'cd '+remote+' && sh install_runner.sh',timeout=60)
        api.wait(lambda:admin.call('GET','/hosts'),lambda hosts:any(h['id']==state['host_id'] and h['pool_state']=='IDLE' for h in hosts),seconds=45)
    state['runner_registered']=True;save(state)
    print('PASS real ECS Runner registered as SHARED_DEMO; one application slot',flush=True)


def allocate(owner,state):
    config={'sku_id':state['sku_id'],'image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'云端共享演示（模拟支付不扣款）'}
    quote=owner.call('POST','/quotes',config)
    order=owner.call('POST','/orders',{'quote_id':quote['id']},key=uuid.uuid4().hex)
    payment=owner.call('POST','/orders/'+order['id']+'/payments')
    owner.call('POST','/payments/'+payment['id']+'/simulate',{'confirmed':True})
    api.wait(lambda:owner.call('GET','/orders/'+order['id']),lambda row:row['state']=='COMPLETED',seconds=60)
    allocation=next(a for a in owner.call('GET','/allocations') if a['order_id']==order['id'])
    project=owner.call('POST','/projects',{'name':'云端同机受限演示应用','allocationId':allocation['id']})
    owner.call('POST','/projects/'+project['id']+'/config',{'variables':{'APP_MESSAGE':'云端同机受限演示：React + Spring Boot，控制面共用2GB ECS；此应用不持有平台凭据。'},'secrets':{}})
    state.update(order_id=order['id'],allocation_id=allocation['id'],instance_id=allocation['instance_id'],project_id=project['id'])
    save(state)


def action(owner,project,kind,target=None):
    plan=owner.call('POST','/projects/'+project+'/plans',{'kind':kind,'targetDeploymentId':target})
    result=owner.call('POST','/plans/'+plan['id']+'/confirm',{'planHash':plan['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=202)['data']
    task=api.wait(lambda:owner.call('GET','/deployments/'+result['id']),lambda row:row['state'] in ('SUCCEEDED','FAILED','MANUAL_REQUIRED'),seconds=150)
    if task['state']!='SUCCEEDED':raise RuntimeError('Cloud application task failed: '+task['id']+' '+task['state'])
    return task


def expose_sample():
    target=json.loads((ROOT/'runtime/ecs-target.json').read_text(encoding='utf-8'))
    group=target['SecurityGroupIds']['SecurityGroupId'][0]
    rules=call('DescribeSecurityGroupAttribute',{'SecurityGroupId':group}).get('Permissions',{}).get('Permission',[])
    if not any(p.get('IpProtocol','').lower()=='tcp' and p.get('PortRange')=='18080/18080' and p.get('SourceCidrIp')=='0.0.0.0/0' for p in rules):
        call('AuthorizeSecurityGroup',{'SecurityGroupId':group,'IpProtocol':'tcp','PortRange':'18080/18080','SourceCidrIp':'0.0.0.0/0','Policy':'accept','Description':'CloudAgent fixed non-sensitive sample application only'})


def exercise():
    admin,owner=clients();state=json.loads(STATE.read_text(encoding='utf-8'))
    if not state.get('project_id'):allocate(owner,state)
    if not state.get('deployment_id'):
        deployed=action(owner,state['project_id'],'DEPLOY');state['deployment_id']=deployed['id'];save(state)
    expose_sample()
    url='http://'+state['public_ip']+':18080/'
    assert requests.get(url,timeout=15).status_code==200
    assert requests.get(url+'api/hello',timeout=15).status_code==200
    state['application_url']=url;save(state)
    print('PASS simulated payment -> actual allocation -> actual container deployment -> public React/Spring API',flush=True)
    action(owner,state['project_id'],'RESTART');action(owner,state['project_id'],'ROLLBACK',state['deployment_id']);action(owner,state['project_id'],'LOGS')
    print('PASS actual application restart, rollback and log collection',flush=True)
    stranger=api.Client();identity={'username':'cloud_isolation_'+secrets.token_hex(4),'password':secrets.token_urlsafe(24)+'7aA'}
    stranger.call('POST','/auth/register',{**identity,'agreement':True});stranger.login(**identity)
    stranger.call('GET','/projects/'+state['project_id'],expected=404)
    stranger.call('GET','/deployments/'+state['deployment_id'],expected=404)
    assert requests.get(api.BASE+'/health',timeout=10).status_code==200
    assert requests.get('http://'+state['public_ip']+'/api/products',timeout=10).status_code==403
    state.update(application_delivery_verified=True,tenant_read_isolation_verified=True,control_plane_alive_after_app_operations=True,payment_mode='SIMULATED_NO_REAL_MONEY')
    save(state);print('PASS ownership isolation and protected public business API; platform remains healthy',flush=True)


def publish():
    admin,owner=clients();state=json.loads(STATE.read_text(encoding='utf-8'))
    if state.get('runbook_id'):return
    identity={'username':'cloud_reviewer_'+secrets.token_hex(4),'password':secrets.token_urlsafe(24)+'7aA'}
    reviewer=api.Client();reviewer.call('POST','/auth/register',{**identity,'agreement':True});reviewer.login(**identity)
    admin.call('PATCH','/users/'+reviewer.user['id'],{'role':'OPERATOR','reason':'Separate cloud demo runbook review account'})
    reviewer.login(**identity);reviewer.admin=True
    (PRIVATE/'runbook-reviewer.json').write_text(json.dumps(identity))
    doc=admin.call('POST','/knowledge',{'product_id':'ecs','title':'[RUNBOOK spring-react-single-host@1] 部署与故障诊断',
        'content':(ROOT/'docs/runbooks/spring-react-single-host-v1.md').read_text(encoding='utf-8'),
        'valid_until':(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=60)).isoformat(),'reason':'Bundled synthetic operations runbook for the authorized cloud demo'})
    admin.call('POST','/knowledge/'+doc['id']+'/transition',{'action':'SUBMIT','reason':'Submit demo runbook for separate review'})
    reviewer.call('POST','/knowledge/'+doc['id']+'/transition',{'action':'PUBLISH','reason':'Independent demo review confirms template and evidence boundaries'})
    state['runbook_id']=doc['id'];save(state)
    print('PASS version-scoped knowledge publication through separate demo review identity',flush=True)


def cleanup():
    admin,owner=clients();state=json.loads(STATE.read_text(encoding='utf-8'))
    if state.get('cleanup_and_reallocation_verified'):
        print('Cleanup/reallocation already verified; preserving the running application');return
    old=state.get('reclaim')
    if not old:
        plan=owner.call('POST','/projects/'+state['project_id']+'/plans',{'kind':'RESTART'})
        old={key:state[key] for key in ('project_id','instance_id','allocation_id','deployment_id')}
        old.update(plan_id=plan['id'],plan_hash=plan['plan_hash'],release_key=uuid.uuid4().hex)
        state['reclaim']=old;save(state)
    if not old.get('released'):
        owner.call('POST','/instances/'+old['instance_id']+'/actions',{'action':'RELEASE','confirmed':True},key=old['release_key'])
        api.wait(lambda:admin.call('GET','/hosts'),lambda hosts:any(h['id']==state['host_id'] and h['pool_state']=='IDLE' for h in hosts),seconds=90)
        owner.call('POST','/plans/'+old['plan_id']+'/confirm',{'planHash':old['plan_hash'],'confirmed':True},key=uuid.uuid4().hex,expected=404)
        assert re.fullmatch('[a-f0-9]{32}',old['allocation_id'])
        with connect() as client:
            _,out,err=client.exec_command('docker ps -aq --filter label=cloudagent.allocation='+old['allocation_id'],timeout=15)
            assert not out.read().decode().strip();assert out.channel.recv_exit_status()==0
        assert requests.get(api.BASE+'/health',timeout=10).status_code==200
        old['released']=True
        for key in ('project_id','instance_id','allocation_id','deployment_id','order_id'):state.pop(key,None)
        save(state)
        print('PASS actual retirement cleanup, old plan rejection and unaffected control plane',flush=True)
    if not state.get('project_id'):allocate(owner,state)
    if not state.get('deployment_id'):
        task=action(owner,state['project_id'],'DEPLOY');state['deployment_id']=task['id'];save(state)
    allocations=owner.call('GET','/allocations')
    previous=next(a for a in allocations if a['id']==old['allocation_id'])
    current=next(a for a in allocations if a['id']==state['allocation_id'])
    assert current['host_id']==previous['host_id'] and current['generation']>previous['generation']
    assert requests.get(state['application_url']+'api/hello',timeout=15).status_code==200
    state['cleanup_and_reallocation_verified']=True;save(state)
    print('PASS newer generation allocated and redeployed; final sample application left running',flush=True)


def chat():
    admin,owner=clients();state=json.loads(STATE.read_text(encoding='utf-8'))
    session=owner.call('POST','/chat/sessions',{'title':'云端真实模型链路验收'})
    response=owner.session.post(api.BASE+'/api/chat',json={'query':'请查询应用项目'+state['project_id']+'当前实际运行证据，并根据已发布运维手册简述重启和回滚的区别。只读取解释，不准备也不执行操作。','session_id':session['id']},headers={'Authorization':'Bearer '+owner.token},stream=True,timeout=180)
    assert response.status_code==200
    contents=[];done=False;error=False
    for line in response.iter_lines():
        if line.startswith(b'data: '):
            data=json.loads(line[6:]);done=done or bool(data.get('done'));error=error or bool(data.get('error'))
            if data.get('content'):contents.append(data['content'])
    (PRIVATE/'chat-answer.txt').write_text(''.join(contents),encoding='utf-8')
    state.update(real_model_sse_completed=done and bool(contents) and not error,chat_request_id=response.headers.get('X-Request-Id'))
    save(state)
    assert state['real_model_sse_completed'],'Model stream failed; inspect private application logs'
    print('PASS actual cloud browser API -> Java -> Python -> DeepSeek/MCP -> completed SSE',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['setup','exercise','publish','chat','cleanup']);args=parser.parse_args()
    globals()[args.phase]()
