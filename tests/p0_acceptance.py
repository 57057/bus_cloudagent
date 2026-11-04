"""Real HTTP + MySQL P0 acceptance. Creates uniquely named demo records, no table cleanup.

Run with Java/MySQL listening; Python/LLM can be offline. Admin credentials are read
from the local ignored bootstrap file and are never printed or written to reports.
"""
import concurrent.futures
import hashlib
import hmac
import base64
import json
from pathlib import Path
import sys
import time
import uuid
import requests

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
BASE = 'http://127.0.0.1:5000'
checks = []


class Client:
    def __init__(self, admin=False):
        self.session = requests.Session()
        self.token = ''
        self.user = None
        self.admin = admin

    def call(self, method, path, data=None, expected=200, key=None):
        if self.admin and not path.startswith('/auth/'):
            path = '/admin' + path
        headers = {'Authorization': 'Bearer ' + self.token, 'X-CSRF-Token': self.session.cookies.get('XSRF-TOKEN', '')}
        if key:
            headers['Idempotency-Key'] = key
        r = self.session.request(method, BASE + '/api' + path, json=data, headers=headers, timeout=40)
        assert r.status_code == expected, (method, path, r.status_code, r.text[:500])
        result = r.json()
        if expected == 200:
            return result['data']
        return result

    def login(self, username, password):
        r = self.call('POST', '/auth/login', {'username': username, 'password': password})
        self.token, self.user = r['access_token'], r['user']


def check(name):
    checks.append(name)
    print('PASS', name, flush=True)


def wait(fn, predicate, seconds=45):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        value = fn()
        if predicate(value):
            return value
        time.sleep(.6)
    raise AssertionError('Timed out waiting for durable task: ' + str(value))


def main():
    admin = Client(True)
    credentials = json.loads((ROOT / 'runtime/platform-admin.json').read_text())
    admin.login(credentials['username'], credentials['password'])
    clients = [Client(), Client()]
    stamp = uuid.uuid4().hex[:12]
    for i, c in enumerate(clients):
        username = f'test_{stamp}_{i}'
        c.call('POST', '/auth/register', {'username':username, 'password':'TestingPass123', 'agreement': True})
        c.login(username, 'TestingPass123')
    a, b = clients
    check('AC-01 register/login and deployment admin')
    assert a.call('GET', '/products')['total'] >= 3
    a.call('GET', '/admin/orders', expected=403)
    check('AC-03 public products and admin authorization')
    cfg = dict(sku_id='ecs-1', image='ubuntu-22', disk_type='ESSD', disk_gb=40,
               bandwidth=1, months=1, quantity=2, name='验收模拟实例')
    quote = a.call('POST', '/quotes', cfg)
    assert quote['total_cents'] == 8800
    a.call('POST', '/quotes', {**cfg,'image':'invented-image'}, expected=400)
    a.call('POST', '/quotes', {**cfg,'sku_id':'gpu'}, expected=404)
    order_key = uuid.uuid4().hex
    order = a.call('POST', '/orders', {'quote_id':quote['id'], 'total_cents':1, 'user_id':b.user['id']},key=order_key)
    assert order['total_cents'] == quote['total_cents'] and order['user_id'] == a.user['id']
    assert a.call('POST', '/orders', {'quote_id':quote['id']},key=order_key)['id'] == order['id']
    another = a.call('POST', '/quotes', cfg)
    a.call('POST', '/orders', {'quote_id':another['id']}, key=order_key, expected=409)
    for path in ['/orders/'+order['id'], '/quotes/'+quote['id']]:
        b.call('GET', path, expected=404)
    check('AC-02/04/05 identity, price snapshots and idempotency')
    failed_instance = order['items'][1]['instance_id']
    admin.call('POST','/simulation/faults',{'business_key':failed_instance,'mode':'FAIL','reason':'验收部分履约失败'})
    payment = a.call('POST', f"/orders/{order['id']}/payments")
    assert a.call('POST', f"/orders/{order['id']}/payments")['id'] == payment['id']
    a.call('POST', f"/payments/{payment['id']}/simulate", {'confirmed':True})
    order = wait(lambda:a.call('GET','/orders/'+order['id']), lambda o:o['state']=='PARTIALLY_FULFILLED')
    successful = next(i for i in order['items'] if i['state']=='SUCCEEDED')
    failed = next(i for i in order['items'] if i['state']=='MANUAL_REQUIRED')
    check('AC-09/10 durable per-instance fulfillment and partial failure')
    payment = a.call('GET','/payments/'+payment['id'])
    token=(ROOT/'runtime/business-service.token').read_text().strip()
    callback={'payment_id':payment['id'],'provider_id':payment['provider_id'],'amount_cents':payment['amount_cents'],'currency':'CNY'}
    def signature(d):
        message=f"{d['payment_id']}|{d['provider_id']}|{d['amount_cents']}|{d['currency']}|SIMULATED"
        return base64.urlsafe_b64encode(hmac.new(token.encode(),message.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    a.call('POST','/payments/callback/SIMULATED',callback,expected=401)
    for d in [callback,{**callback,'amount_cents':1}]:
        r=requests.post(BASE+'/api/payments/callback/SIMULATED',json=d,headers={'X-Payment-Signature':signature(d)},timeout=15)
        assert r.status_code==(200 if d['amount_cents']==payment['amount_cents'] else 400),r.text
    assert len(a.call('GET','/orders/'+order['id'])['tasks'])==2
    check('AC-07 verified callbacks, amount check and duplicate delivery')
    refund=a.call('POST','/refunds',{'order_id':order['id'],'item_id':failed['id'],'reason':'失败子项验收退款'})
    assert refund['amount_cents']==failed['amount_cents']
    a.call('POST','/refunds',{'order_id':order['id'],'item_id':failed['id'],'reason':'重复退款'},expected=409)
    b.call('GET','/refunds/'+refund['id'],expected=404)
    admin.call('POST',f"/refunds/{refund['id']}/review",{'approve':True,'reason':'失败子项全额退回'})
    wait(lambda:a.call('GET','/refunds/'+refund['id']),lambda f:f['state']=='SUCCEEDED')
    check('AC-13 refund hold/review/provider result and duplicate rejection')
    instance=successful['instance_id']
    b.call('GET','/instances/'+instance,expected=404)
    for action,target in [('STOP','STOPPED'),('START','RUNNING'),('RESTART','RUNNING')]:
        operation_key=uuid.uuid4().hex
        task=a.call('POST',f'/instances/{instance}/actions',{'action':action},key=operation_key)
        assert a.call('POST',f'/instances/{instance}/actions',{'action':action},key=operation_key)['id']==task['id']
        wait(lambda:a.call('GET','/instances/'+instance),lambda i:i['state']==target)
    before=a.call('GET','/instances/'+instance)['expires_at']
    renewal=a.call('POST',f'/instances/{instance}/renewal-quotes',{'months':1})
    ro=a.call('POST','/renewal-orders',{'quote_id':renewal['id']},key=uuid.uuid4().hex)
    rp=a.call('POST',f"/orders/{ro['id']}/payments")
    a.call('POST',f"/payments/{rp['id']}/simulate",{'confirmed':True})
    wait(lambda:a.call('GET','/orders/'+ro['id']),lambda o:o['state']=='COMPLETED')
    resource=a.call('GET','/instances/'+instance)
    assert resource['expires_at']>before and len(resource['renewals'])==1
    check('AC-11/12 operations and idempotent renewal')
    a.call('POST',f'/instances/{instance}/actions',{'action':'RELEASE'},key=uuid.uuid4().hex,expected=400)
    a.call('POST',f'/instances/{instance}/actions',{'action':'RELEASE','confirmed':True},key=uuid.uuid4().hex)
    wait(lambda:a.call('GET','/instances/'+instance),lambda i:i['state']=='RELEASED')
    a.call('POST',f'/instances/{instance}/actions',{'action':'START'},key=uuid.uuid4().hex,expected=409)
    a.call('POST',f'/instances/{instance}/renewal-quotes',{'months':1},expected=409)
    check('AC-11 release confirmation and irreversibility')
    ticket=a.call('POST','/tickets',{'type':'PAYMENT','title':'验收工单','content':'模拟付款问题','order_id':order['id']})
    b.call('GET','/tickets/'+ticket['id'],expected=404)
    admin.call('POST',f"/tickets/{ticket['id']}/replies",{'content':'验收回复','state':'RESOLVED'})
    assert len(a.call('GET','/tickets/'+ticket['id'])['replies'])==2
    a.call('PATCH','/tickets/'+ticket['id'],{'close':True})
    wait(lambda:a.call('GET','/notifications'),lambda n:n['total']>0)
    chat_session=a.call('POST','/chat/sessions',{'title':'隔离验收'})
    b.call('GET','/chat/sessions/'+chat_session['id'],expected=404)
    b.call('DELETE','/chat/sessions/'+chat_session['id'],expected=404)
    a.call('DELETE','/chat/sessions/'+chat_session['id'])
    a.call('GET','/chat/sessions/'+chat_session['id'],expected=404)
    check('AC-02/15 tickets, notifications and chat session ownership')
    q=a.call('POST','/quotes',{**cfg,'quantity':1})
    late=a.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex)
    lp=a.call('POST',f"/orders/{late['id']}/payments")
    a.call('POST',f"/orders/{late['id']}/cancel")
    a.call('POST',f"/payments/{lp['id']}/simulate",{'confirmed':True})
    wait(lambda:a.call('GET','/payments/'+lp['id']),lambda p:p['state']=='SUCCEEDED')
    late=a.call('GET','/orders/'+late['id']);assert late['state']=='CANCELLED' and len(late['tasks'])==0
    check('AC-08 late payment retained without provisioning')
    # 100 simultaneous order requests compete for exactly 10 units of a dedicated test SKU.
    sku={'id':'test-'+stamp,'product_id':'ecs','name':'验收库存','region':'cn-hangzhou','zone':'cn-hangzhou-a','cpu':2,'memory_gb':4,'base_cents':100,'disk_cents':1,'bandwidth_cents':1,'capacity':10,'active':True,'adapter':'SIMULATED','images':'ubuntu-22','min_disk':40,'max_disk':500,'max_bandwidth':100,'periods':'1,3,12','grace_days':7,'refund_days':7,'reason':'100并发库存验收'}
    admin.call('POST','/skus',sku)
    buyers=[b]
    for i in range(9):
        buyer=Client();username=f'load_{stamp}_{i}'
        buyer.call('POST','/auth/register',{'username':username,'password':'TestingPass123','agreement':True});buyer.login(username,'TestingPass123');buyers.append(buyer)
    concurrent_quotes=[buyer.call('POST','/quotes',{**cfg,'sku_id':sku['id'],'quantity':1}) for buyer in buyers]
    def buy(i):
        index=i%len(buyers)
        return requests.post(BASE+'/api/orders',json={'quote_id':concurrent_quotes[index]['id']},headers={'Authorization':'Bearer '+buyers[index].token,'Idempotency-Key':uuid.uuid4().hex},timeout=60)
    with concurrent.futures.ThreadPoolExecutor(max_workers=100) as pool:
        results=list(pool.map(buy,range(100)))
    successes=[r.json()['data'] for r in results if r.status_code==200]
    assert len(successes)==10,[(r.status_code,r.text[:50]) for r in results]
    assert all(r.status_code in (200,409) for r in results)
    actual=next(v for v in admin.call('GET','/skus?size=100')['items'] if v['id']==sku['id'])
    assert actual['reserved']==10 and actual['allocated']==0
    for o in successes:next(buyer for buyer in buyers if buyer.user['id']==o['user_id']).call('POST',f"/orders/{o['id']}/cancel")
    actual=next(v for v in admin.call('GET','/skus?size=100')['items'] if v['id']==sku['id'])
    assert actual['reserved']==0
    admin.call('POST','/skus',{**sku,'active':False,'reason':'验收完成，停用专用测试SKU'})
    check('AC-06 10 users/100 concurrent requests/10 stock, cancellation restores reservations')
    previous=a.token
    fresh=a.call('POST','/auth/refresh');a.token=fresh['access_token']
    assert requests.get(BASE+'/api/me',headers={'Authorization':'Bearer '+previous},timeout=15).status_code==401
    a.call('POST','/me/password',{'old_password':'TestingPass123','new_password':'ChangedPass123'})
    a.call('GET','/me',expected=401)
    a.call('POST','/auth/refresh',expected=401)
    a.login(a.user['username'],'ChangedPass123')
    a.call('POST','/auth/logout')
    a.call('GET','/me',expected=401)
    check('AC-01 refresh rotation/password change/logout revoke old sessions')
    (ROOT/'runtime/p0-acceptance.json').write_text(json.dumps({'checks':checks,'test_suffix':stamp,'timestamp':time.time()},ensure_ascii=False,indent=2),encoding='utf-8')
    print('All checks passed:',len(checks))


if __name__=='__main__':
    main()
