"""P1 acceptance with unique simulated records. Never calls real payment, cloud or messaging providers."""
from p0_acceptance import Client, ROOT, BASE, wait
from p0_recovery import connection
import concurrent.futures
import datetime as dt
import json
import re
import uuid
import requests


def main():
    stamp=uuid.uuid4().hex[:10]
    admin=Client(True);secret=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(secret['username'],secret['password'])
    buyer,promoter,operator=Client(),Client(),Client()
    for n,c in enumerate((buyer,promoter,operator)):
        c.call('POST','/auth/register',{'username':f'p1_{stamp}_{n}','password':'TestingPass123','agreement':True})
        c.login(f'p1_{stamp}_{n}','TestingPass123')
    admin.call('PATCH','/users/'+operator.user['id'],{'role':'OPERATOR','reason':'P1 independent publication review test'})
    operator.login(f'p1_{stamp}_2','TestingPass123'); operator.admin=True
    buyer.call('GET','/admin/coupons',expected=403)
    now=dt.datetime.now(dt.timezone.utc)
    coupon=admin.call('POST','/coupons',{'code':'P1-'+stamp,'name':'P1 test discount','minimum_cents':1000,'discount_cents':101,'capacity':3,'active':True,'starts_at':(now-dt.timedelta(minutes=1)).isoformat(),'ends_at':(now+dt.timedelta(days=3)).isoformat(),'reason':'P1 coupon lifecycle test'})
    grant=buyer.call('POST','/coupons/claim',{'code':'P1-'+stamp})
    assert buyer.call('POST','/coupons/claim',{'code':'P1-'+stamp})['id']==grant['id']
    campaign=admin.call('POST','/campaigns',{'product_id':'ecs','title':'P1 '+stamp,'rate_bps':500,'active':True,'reason':'P1 commission test'})
    link=promoter.call('POST',f"/promotion/campaigns/{campaign['id']}/links")
    sku={'id':'p1-'+stamp,'product_id':'ecs','name':'P1 acceptance '+stamp,'region':'cn-hangzhou','zone':'cn-hangzhou-a','cpu':2,'memory_gb':4,'base_cents':1000,'disk_cents':1,'bandwidth_cents':1,'capacity':10,'active':True,'adapter':'SIMULATED','images':'ubuntu-22','min_disk':40,'max_disk':500,'max_bandwidth':100,'periods':'1,3,12','grace_days':7,'refund_days':7,'reason':'P1 acceptance only'}
    admin.call('POST','/skus',sku)
    cfg={'sku_id':sku['id'],'image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':3,'name':'P1 instances','coupon_grant_id':grant['id'],'promotion_link':link['id']}
    quote=buyer.call('POST','/quotes',cfg);assert quote['total_cents']==3022
    order=buyer.call('POST','/orders',{'quote_id':quote['id']},key=uuid.uuid4().hex)
    assert sum(i['amount_cents'] for i in order['items'])==order['total_cents']
    buyer.call('POST','/orders',{'quote_id':quote['id']},key=uuid.uuid4().hex,expected=409)
    buyer.call('POST',f"/orders/{order['id']}/cancel")
    assert buyer.call('GET','/coupons')[0]['state']=='AVAILABLE'
    # Competing quotes may exist, but the same grant cannot fund two orders.
    q1=buyer.call('POST','/quotes',cfg);q2=buyer.call('POST','/quotes',cfg)
    def create(q):
        response=buyer.session.post(BASE+'/api/orders',json={'quote_id':q['id']},headers={'Authorization':'Bearer '+buyer.token,'Idempotency-Key':uuid.uuid4().hex},timeout=30)
        return response
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(create,(q1,q2)))
    assert sorted(r.status_code for r in results)==[200,409]
    order=next(r.json()['data'] for r in results if r.status_code==200)
    pay=buyer.call('POST',f"/orders/{order['id']}/payments");buyer.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True})
    order=wait(lambda:buyer.call('GET','/orders/'+order['id']),lambda o:o['state']=='COMPLETED')
    assert buyer.call('GET','/coupons')[0]['state']=='USED'
    ledger=promoter.call('GET','/promotion/commissions');assert sum(x['amount_cents'] for x in ledger if x['order_id']==order['id'])==151
    admin.call('POST',f"/commissions/{order['id']}/settle",{'action':'SETTLE','reason':'must reject frozen commission'},expected=409)
    print('PASS coupons: concurrent reservation, cancel release, paid use, cent allocation; versioned attribution and frozen commission',flush=True)
    invoice=buyer.call('POST','/invoices',{'order_id':order['id'],'title':'P1 test','tax_number':'PERSONAL'})
    promoter.call('POST','/invoices',{'order_id':order['id'],'title':'foreign','tax_number':'PERSONAL'},expected=404)
    admin.call('POST',f"/invoices/{invoice['id']}/review",{'approve':True,'reference':'SIM-'+stamp,'reason':'simulated processing'})
    item=order['items'][0]
    refund=buyer.call('POST','/refunds',{'order_id':order['id'],'item_id':item['id'],'reason':'P1 refund commission reversal'})
    admin.call('POST',f"/refunds/{refund['id']}/review",{'approve':True,'reason':'P1 approved test'})
    wait(lambda:buyer.call('GET','/refunds/'+refund['id']),lambda f:f['state']=='SUCCEEDED')
    fresh=buyer.call('GET','/orders/'+order['id']);ledger=promoter.call('GET','/promotion/commissions')
    assert sum(x['amount_cents'] for x in ledger if x['order_id']==order['id'])==(fresh['total_cents']-fresh['refunded_cents'])*500//10000
    assert buyer.call('GET','/invoices')[0]['state']=='NEEDS_RED'
    admin.call('POST',f"/invoices/{invoice['id']}/void",{'action':'VOID','reason':'P1 simulated red reversal'})
    reapplied=buyer.call('POST','/invoices',{'order_id':order['id'],'title':'P1 revised','tax_number':'PERSONAL'})
    assert reapplied['amount_cents']==fresh['total_cents']-fresh['refunded_cents']
    # Advance only this test's order/resource clocks to verify the positive settlement path.
    with connection() as db,db.cursor() as cur:
        cur.execute('UPDATE p0_orders SET created_at=%s WHERE id=%s AND user_id=%s',(dt.datetime.utcnow()-dt.timedelta(days=9),order['id'],buyer.user['id']))
        cur.execute('UPDATE p0_instances SET starts_at=%s WHERE order_id=%s AND user_id=%s',(dt.datetime.utcnow()-dt.timedelta(days=9),order['id'],buyer.user['id']))
    settled=admin.call('POST',f"/commissions/{order['id']}/settle",{'action':'SETTLE','reason':'P1 simulated settlement after frozen period'})
    assert settled['mode']=='SIMULATED' and settled['amount_cents']==(fresh['total_cents']-fresh['refunded_cents'])*500//10000
    admin.call('POST',f"/commissions/{order['id']}/settle",{'action':'SETTLE','reason':'duplicate settlement rejected'},expected=409)
    print('PASS invoices/refunds: ownership, amount, issued invoice red reversal and commission compensation',flush=True)

    doc=admin.call('POST','/knowledge',{'product_id':'ecs','title':'P1 '+stamp,'content':'P1 发布测试：VPC是隔离网络。unique-'+stamp,'valid_until':(now+dt.timedelta(days=30)).isoformat(),'reason':'P1 publication test'})
    admin.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'SUBMIT','reason':'submit'})
    admin.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'PUBLISH','reason':'self review forbidden'},expected=409)
    operator.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'PUBLISH','reason':'independent review'})
    token=(ROOT/'runtime/business-service.token').read_text().strip()
    def snapshot():return requests.get(BASE+'/internal/knowledge/snapshot',headers={'X-Service-Token':token},timeout=10).json()
    first=snapshot();assert any(d['id']==doc['id'] for d in first['documents'])
    operator.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'RETIRE','reason':'withdraw test content'})
    second=snapshot();assert second['revision']>first['revision'] and not any(d['id']==doc['id'] for d in second['documents'])
    print('PASS knowledge: draft, submission, independent review, publication revision, retirement filter',flush=True)

    contact=buyer.call('POST','/me/contact/request',{'channel':'EMAIL','destination':f'p1-{stamp}@example.com'})
    def inbox():return admin.call('GET','/deliveries/inbox')
    message=wait(inbox,lambda rows:any(x['destination']==f'p1-{stamp}@example.com' for x in rows))
    code=re.search(r'\d{6}',next(x['content'] for x in message if x['destination']==f'p1-{stamp}@example.com')).group()
    buyer.call('POST','/me/contact/verify',{'challenge_id':contact['challenge_id'],'code':'000000' if code!='000000' else '111111'},expected=400)
    buyer.call('POST','/me/contact/verify',{'challenge_id':contact['challenge_id'],'code':code})
    buyer.call('POST','/me/contact/verify',{'challenge_id':contact['challenge_id'],'code':code},expected=400)
    reset=buyer.call('POST','/auth/password-reset/request',{'channel':'EMAIL','destination':f'p1-{stamp}@example.com'})
    message=wait(inbox,lambda rows:len([x for x in rows if x['destination']==f'p1-{stamp}@example.com'])>=2)
    latest=next(x for x in message if x['destination']==f'p1-{stamp}@example.com')
    resetcode=re.search(r'\d{6}',latest['content']).group()
    buyer.call('POST','/auth/password-reset/confirm',{'challenge_id':reset['challenge_id'],'code':resetcode,'new_password':'RecoveredPass456'})
    buyer.call('GET','/me',expected=401);buyer.login(f'p1_{stamp}_0','RecoveredPass456')
    print('PASS recovery: verified contact, attempts, one-time code and password reset revokes sessions',flush=True)

    instance=order['items'][1]['instance_id']
    promoter.call('PUT',f'/instances/{instance}/auto-renewal',{'enabled':True,'months':1,'max_cents':5000,'confirmed':True},expected=404)
    buyer.call('PUT',f'/instances/{instance}/auto-renewal',{'enabled':True,'months':1,'max_cents':5000,'confirmed':False},expected=400)
    buyer.call('PUT',f'/instances/{instance}/auto-renewal',{'enabled':True,'months':1,'max_cents':1,'confirmed':True})
    with connection() as db,db.cursor() as cur:
        cur.execute('UPDATE p0_instances SET expires_at=%s WHERE id=%s AND user_id=%s',(dt.datetime.utcnow()+dt.timedelta(days=2),instance,buyer.user['id']))
        cur.execute('UPDATE p0_provider_resources p JOIN p0_instances i ON i.id=p.business_key SET p.expires_at=i.expires_at WHERE i.id=%s AND i.user_id=%s',(instance,buyer.user['id']))
    wait(lambda:buyer.call('GET','/auto-renewals'),lambda rows:rows and rows[0]['state']=='FAILED',seconds=35)
    buyer.call('PUT',f'/instances/{instance}/auto-renewal',{'enabled':True,'months':1,'max_cents':5000,'confirmed':True})
    auto=wait(lambda:buyer.call('GET','/auto-renewals'),lambda rows:rows and rows[0]['state']=='SUCCEEDED',seconds=45)[0]
    renewed=wait(lambda:buyer.call('GET','/orders/'+auto['last_order_id']),lambda o:o['state']=='COMPLETED')
    buyer.call('PUT',f'/instances/{instance}/auto-renewal',{'enabled':False,'months':1,'max_cents':5000,'confirmed':False})
    print('PASS auto-renewal: explicit consent, scope, price ceiling, notification, simulated payment and cancellation',flush=True)

    preview=buyer.call('POST','/assistant/actions/preview',{'action':'RENEWAL','parameters':{'instance_id':instance,'months':1}})
    promoter.call('POST',f"/assistant/actions/{preview['id']}/confirm",{'confirmed':True},expected=404)
    buyer.call('POST',f"/assistant/actions/{preview['id']}/confirm",{'confirmed':False},expected=400)
    result=buyer.call('POST',f"/assistant/actions/{preview['id']}/confirm",{'confirmed':True})
    assert buyer.call('POST',f"/assistant/actions/{preview['id']}/confirm",{'confirmed':True})['id']==result['id']
    assert result['state']=='PENDING_PAYMENT';buyer.call('POST',f"/orders/{result['id']}/cancel")
    bad=buyer.call('POST','/assistant/actions/preview',{'action':'SHELL','parameters':{}},expected=400)
    print('PASS assisted actions: structured preview, explicit confirmation, no auto-payment, owner and duplicate checks',flush=True)
    today=now.date().isoformat();report=admin.call('GET',f'/reports?from={today}&to={today}')
    assert report['net_cents']==report['payments']['paid_cents']-report['refunds']['refunded_cents']
    admin.call('PUT','/providers',{'provider':'ALIYUN_ECS','mode':'DRY_RUN','settings':{'AccessKeySecret':'not-allowed'},'reason':'reject secret storage'},expected=400)
    buyer.call('POST',f"/orders/{result['id']}/sandbox-payment",expected=503)
    # Keep all evidence; deactivate only this test's product configuration.
    admin.call('POST','/skus',{**sku,'active':False,'reason':'P1 acceptance finished'})
    (ROOT/'runtime/p1-acceptance.json').write_text(json.dumps({'test_suffix':stamp,'passed':True,'coupon':coupon['id'],'order':order['id'],'invoice':invoice['id'],'document':doc['id'],'auto_order':renewed['id'],'real_providers_called':False,'timestamp':now.isoformat()},indent=2),encoding='utf-8')
    print('PASS reports/provider safeguards; P1 acceptance complete',flush=True)


if __name__=='__main__':main()
