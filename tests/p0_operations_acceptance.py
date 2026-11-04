"""Operational P0 checks: snapshots, refund preview/release, attachments, accounts and deadlines."""
from p0_acceptance import Client,ROOT,BASE,wait
from p0_recovery import connection
import datetime as dt
import json
import uuid


def main():
    admin=Client(True);credentials=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(credentials['username'],credentials['password'])
    a,b=Client(),Client();stamp=uuid.uuid4().hex[:10]
    for i,c in enumerate([a,b]):
        c.call('POST','/auth/register',{'username':f'ops_{stamp}_{i}','password':'TestingPass123','agreement':True});c.login(f'ops_{stamp}_{i}','TestingPass123')
    sku={'id':'test-'+stamp,'product_id':'ecs','name':'验收运营配置','region':'cn-hangzhou','zone':'cn-hangzhou-a','cpu':2,'memory_gb':4,'base_cents':1000,'disk_cents':1,'bandwidth_cents':1,'capacity':3,'active':True,'adapter':'SIMULATED','images':'ubuntu-22','min_disk':40,'max_disk':500,'max_bandwidth':100,'periods':'1,3,12','grace_days':7,'refund_days':7,'reason':'运营场景验收'}
    admin.call('POST','/skus',sku)
    cfg={'sku_id':sku['id'],'image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'退款释放验收'}
    q=a.call('POST','/quotes',cfg)
    o=a.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex)
    admin.call('POST','/skus',{**sku,'base_cents':2000,'reason':'价格版本与历史快照验收'})
    assert a.call('GET','/orders/'+o['id'])['total_cents']==1041
    assert a.call('POST','/quotes',cfg)['total_cents']==2041
    pay=a.call('POST',f"/orders/{o['id']}/payments");a.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True})
    o=wait(lambda:a.call('GET','/orders/'+o['id']),lambda r:r['state']=='COMPLETED');item=o['items'][0]
    admin.call('POST','/skus',{**sku,'capacity':0,'reason':'非法调减库存验收'},expected=409)
    preview=a.call('GET',f"/orders/{o['id']}/refund-preview?item_id={item['id']}");assert 0<preview['amount_cents']<=1041
    f=a.call('POST','/refunds',{'order_id':o['id'],'item_id':item['id'],'reason':'先验证拒绝再重新申请'})
    admin.call('POST',f"/refunds/{f['id']}/review",{'approve':False,'reason':'验收拒绝与额度释放'})
    f=a.call('POST','/refunds',{'order_id':o['id'],'item_id':item['id'],'reason':'批准后先释放模拟资源再退款'})
    admin.call('POST',f"/refunds/{f['id']}/review",{'approve':True,'reason':'验收通过'})
    wait(lambda:a.call('GET','/refunds/'+f['id']),lambda r:r['state']=='SUCCEEDED')
    assert a.call('GET','/instances/'+item['instance_id'])['state']=='RELEASED'
    print('PASS AC-13/14: preview, review rejection, quota release, refund after resource release, historical price snapshot')
    t=a.call('POST','/tickets',{'title':'附件验收','type':'RESOURCE','content':'仅验证测试附件权限'})
    headers={'Authorization':'Bearer '+a.token}
    r=a.session.post(BASE+f"/api/tickets/{t['id']}/attachments",files={'file':('test.pdf',b'%PDF-1.4\nDemo only\n%%EOF','application/pdf')},headers=headers,timeout=15);assert r.status_code==200,r.text
    file=r.json()['data']['id']
    r=b.session.get(BASE+'/api/attachments/'+file,headers={'Authorization':'Bearer '+b.token},timeout=15);assert r.status_code==404
    r=a.session.get(BASE+'/api/attachments/'+file,headers=headers,timeout=15);assert r.status_code==200 and r.content.startswith(b'%PDF-')
    r=a.session.post(BASE+f"/api/tickets/{t['id']}/attachments",files={'file':('evil.svg',b'<svg onload="alert(1)"></svg>','image/svg+xml')},headers=headers,timeout=15);assert r.status_code==400
    r=a.session.post(BASE+f"/api/tickets/{t['id']}/attachments",files={'file':('huge.pdf',b'%PDF-'+b'x'*(2*1024*1024),'application/pdf')},headers=headers,timeout=15);assert r.status_code in (400,413),r.status_code
    print('PASS AC-15: attachment ownership, file signature/type and size restrictions')
    admin.call('PATCH','/users/'+a.user['id'],{'enabled':False,'reason':'停用与会话撤销验收'})
    a.call('GET','/me',expected=401);a.call('POST','/auth/login',{'username':a.user['username'],'password':'TestingPass123'},expected=401)
    admin.call('PATCH','/users/'+a.user['id'],{'enabled':True,'reason':'恢复验收账号'})
    a.login(a.user['username'],'TestingPass123')
    # Accelerate only this test user's quote/order deadlines.
    q=a.call('POST','/quotes',cfg)
    with connection() as db, db.cursor() as cur:
        cur.execute('UPDATE p0_quotes SET expires_at=%s WHERE id=%s AND user_id=%s',(dt.datetime.utcnow()-dt.timedelta(minutes=1),q['id'],a.user['id']))
    a.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex,expected=409)
    q=a.call('POST','/quotes',cfg);o=a.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex)
    pay=a.call('POST',f"/orders/{o['id']}/payments")
    a.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True,'outcome':'FAILED'})
    wait(lambda:a.call('GET','/payments/'+pay['id']),lambda v:v['state']=='FAILED')
    assert a.call('GET','/orders/'+o['id'])['state']=='PENDING_PAYMENT'
    with connection() as db, db.cursor() as cur:
        cur.execute('UPDATE p0_orders SET payment_deadline=%s WHERE id=%s AND user_id=%s',(dt.datetime.utcnow()-dt.timedelta(minutes=1),o['id'],a.user['id']))
    wait(lambda:a.call('GET','/orders/'+o['id']),lambda r:r['state']=='CLOSED')
    actual=next(x for x in admin.call('GET','/skus?size=100')['items'] if x['id']==sku['id']);assert actual['reserved']==0 and actual['allocated']==0
    admin.call('POST','/skus',{**sku,'active':False,'reason':'停用验收配置'})
    for i in range(5):a.call('POST','/auth/login',{'username':a.user['username'],'password':'wrongPass123'},expected=401)
    a.call('POST','/auth/login',{'username':a.user['username'],'password':'TestingPass123'},expected=429)
    print('PASS AC-01/04/06/07: disabled accounts, login lock window, payment failure, expired quotes/orders and inventory release')
    (ROOT/'runtime/p0-operations-check.json').write_text(json.dumps({'snapshot_and_refund':True,'restricted_attachments':True,'account_and_deadline':True,'test_suffix':stamp},indent=2),encoding='utf-8')


if __name__=='__main__':main()
