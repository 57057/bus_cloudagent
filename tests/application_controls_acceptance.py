"""Authorized cloud demo verification: simulated money, real labelled containers.

Only the dedicated verification lease is changed. Leave its final app running.
Video Two and all control-plane containers remain in service throughout.
"""
import json
from pathlib import Path
import sys
import time
import uuid
import requests
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from deploy.cloud_demo_acceptance import clients,api
from deploy.ecs_linux_ssh import connect
STATE=ROOT/'runtime/ecs-linux/application-controls-acceptance.json'

def remote(client,command):
    _,out,err=client.exec_command(command,timeout=30)
    value=out.read().decode();error=err.read().decode()
    if out.channel.recv_exit_status()!=0:raise RuntimeError('Controlled Docker/service command failed: '+error[:180])
    return value.strip()

def main():
    admin,owner=clients()
    state=json.loads(STATE.read_text(encoding='utf-8')) if STATE.exists() else {}
    if state.get('project_id') and not any(a['id']==state.get('allocation_id') and a['state']=='ACTIVE' for a in owner.call('GET','/allocations')):
        state={}
    checks=[]
    def check(name):checks.append(name);print('PASS '+name,flush=True)
    def save():STATE.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8')
    if not state.get('project_id'):
        hosts=admin.call('GET','/hosts')
        if not any(h['pool_state']=='IDLE' and h['mode']=='SHARED_DEMO' for h in hosts):raise RuntimeError('Dedicated shared-demo slot is not free; do not displace another lease')
        quote=owner.call('POST','/quotes',{'sku_id':'shared-demo-slot','image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'新手引导验收应用槽（模拟支付）'})
        order=owner.call('POST','/orders',{'quote_id':quote['id']},key=uuid.uuid4().hex)
        payment=owner.call('POST','/orders/'+order['id']+'/payments')
        owner.call('POST','/payments/'+payment['id']+'/simulate',{'confirmed':True})
        api.wait(lambda:owner.call('GET','/orders/'+order['id']),lambda o:o['state']=='COMPLETED',seconds=90)
        allocation=next(a for a in owner.call('GET','/allocations') if a['order_id']==order['id'])
        p=owner.call('POST','/projects',{'name':'我的第一个云端网站','allocationId':allocation['id']})
        state.update(project_id=p['id'],allocation_id=allocation['id'],order_id=order['id'],instance_id=allocation['instance_id'],payment_mode='SIMULATED_NO_REAL_MONEY');save()
    project=state['project_id']
    def detail():return owner.call('GET','/projects/'+project)
    def task(id):return owner.call('GET','/deployments/'+id)
    def submit(kind):
        plan=owner.call('POST','/projects/'+project+'/plans',{'kind':kind})
        return owner.call('POST','/plans/'+plan['id']+'/confirm',{'confirmed':True,'planHash':plan['plan_hash']},key=uuid.uuid4().hex,expected=202)['data']
    def done(t):
        t=api.wait(lambda:task(t['id']),lambda t:t['state'] in ('SUCCEEDED','FAILED','CANCELLED','MANUAL_REQUIRED'),seconds=180)
        assert t['state']=='SUCCEEDED',(t['state'],t.get('result'));return t
    welcome='你好，欢迎来到我的第一个云端网站！这是真实部署的 React + Spring Boot 应用。'
    owner.call('PATCH','/projects/'+project+'/welcome',{'message':welcome})
    if not detail().get('current_deployment_id'):
        released=done(submit('DEPLOY'));state['release_id']=released['id'];save()
    d=detail();url=d['current_release']['url'].rstrip('/')+'/'
    assert requests.get(url,timeout=15).status_code==200
    assert requests.get(url+'api/hello',timeout=15).json()['message']==welcome
    check('real release URL and backend show configured welcome message')
    with connect() as c:
        old=remote(c,'docker ps -q --filter label=cloudagent.allocation='+state['allocation_id'])
        # No active task at this point: stopping this worker only makes queue cancellation deterministic.
        remote(c,'systemctl stop cloudagent-runner')
        try:
            queued=submit('DEPLOY');assert queued['state']=='QUEUED'
            result=owner.call('POST','/deployments/'+queued['id']+'/cancel',{'confirmed':True})
            assert result['state']=='CANCELLED'
            assert not remote(c,'docker ps -aq --filter label=cloudagent.deployment='+queued['id'])
        finally:remote(c,'systemctl start cloudagent-runner')
        check('queued deployment cancels before any Docker side effect')
        running=submit('DEPLOY');candidate=None
        deadline=time.monotonic()+75
        while time.monotonic()<deadline:
            candidate=remote(c,'docker ps -q --filter label=cloudagent.deployment='+running['id'])
            if candidate:
                # Pause only this new candidate, never the existing published release.
                remote(c,'docker pause '+candidate);break
            if task(running['id'])['state'] not in ('QUEUED','RUNNING'):raise AssertionError('Candidate finished before controlled cancellation')
            time.sleep(.15)
        assert candidate,'No candidate appeared'
        try:
            cancellation=owner.call('POST','/deployments/'+running['id']+'/cancel',{'confirmed':True})
            assert cancellation['cancel_requested'] and cancellation['state']=='RUNNING'
            result=api.wait(lambda:task(running['id']),lambda t:t['state'] in ('CANCELLED','FAILED','MANUAL_REQUIRED'),seconds=75)
            assert result['state']=='CANCELLED',result
            assert not remote(c,'docker ps -aq --filter label=cloudagent.deployment='+running['id'])
            assert not remote(c,'docker network ls -q --filter label=cloudagent.deployment='+running['id'])
        finally:
            # If a test assertion fails early, unblock the controlled health probe.
            if remote(c,'docker ps -q --filter status=paused --filter label=cloudagent.deployment='+running['id']):remote(c,'docker unpause '+candidate)
        assert remote(c,'docker ps -q --filter label=cloudagent.allocation='+state['allocation_id'])==old
        assert detail()['current_deployment_id']==d['current_deployment_id']
        assert requests.get(url+'api/hello',timeout=15).json()['message']==welcome
        check('running cancellation removes only candidates; previous release and HTTP endpoint remain intact')
        stopped=done(submit('STOP'));assert detail()['runtime_state']=='STOPPED'
        assert not remote(c,'docker ps -q --filter label=cloudagent.allocation='+state['allocation_id'])
        assert remote(c,'docker ps -aq --filter label=cloudagent.allocation='+state['allocation_id'])
        check('stop really stops both containers while preserving release and configuration')
        started=done(submit('START'));assert detail()['runtime_state']=='RUNNING'
        assert remote(c,'docker ps -q --filter label=cloudagent.allocation='+state['allocation_id'])==old
        assert requests.get(url+'api/hello',timeout=15).json()['message']==welcome
        check('start restores the same containers and same welcome page')
        assert remote(c,'systemctl is-active video-two')=='active'
        assert requests.get(api.BASE+'/health',timeout=15).status_code==200
        check('control plane and Video Two remain available')
    state.update(checks=checks,application_url=url,final_runtime='RUNNING');save()

if __name__=='__main__':main()
