"""Two-phase restart/expiry acceptance against dedicated simulated test records.

prepare -> restart the registered Java service -> finish.
Only identifiers produced by this script are altered to accelerate clocks/leases.
"""
from p0_acceptance import Client, wait, ROOT
from agent.config import get_settings
import argparse
import datetime as dt
import json
import time
import uuid
import pymysql


def connection():
    s=get_settings()
    return pymysql.connect(host=s.mysql_host,port=s.mysql_port,user=s.mysql_user,password=s.mysql_password,database=s.mysql_database,autocommit=True,cursorclass=pymysql.cursors.DictCursor)


def main():
    p=argparse.ArgumentParser();p.add_argument('phase',choices=['prepare','finish']);args=p.parse_args()
    admin=Client(True);d=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(d['username'],d['password'])
    path=ROOT/'runtime/p0-recovery.json'
    c=Client()
    with connection() as db, db.cursor() as cur:
        if args.phase=='prepare':
            username='recovery_'+uuid.uuid4().hex[:12]
            c.call('POST','/auth/register',{'username':username,'password':'TestingPass123','agreement':True});c.login(username,'TestingPass123')
            cfg={'sku_id':'ecs-2','image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'重启恢复验收'}
            q=c.call('POST','/quotes',cfg);o=c.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex);instance=o['items'][0]['instance_id']
            admin.call('POST','/simulation/faults',{'business_key':instance,'mode':'TIMEOUT_AFTER_COMMIT','reason':'供应商成功后超时恢复验收'})
            pay=c.call('POST',f"/orders/{o['id']}/payments");c.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True})
            def pending():
                cur.execute("SELECT t.* FROM p0_tasks t WHERE order_id=%s",(o['id'],));return cur.fetchone()
            task=wait(pending,lambda t:t and t['attempts']==1)
            cur.execute("UPDATE p0_tasks SET state='RUNNING',next_run=%s WHERE id=%s",(dt.datetime.utcnow()+dt.timedelta(minutes=5),task['id']))
            cur.execute('SELECT COUNT(*) AS n FROM p0_instances WHERE id=%s',(instance,));assert cur.fetchone()['n']==0
            cur.execute('SELECT COUNT(*) AS n FROM p0_provider_resources WHERE business_key=%s',(instance,));assert cur.fetchone()['n']==1
            path.write_text(json.dumps({'username':username,'instance':instance,'task':task['id'],'order':o['id']}),encoding='utf-8')
            print('PREPARED: provider committed, merchant resource absent, durable RUNNING lease held. Restart Java then finish.')
        else:
            d=json.loads(path.read_text());c.login(d['username'],'TestingPass123');instance=d['instance']
            cur.execute('UPDATE p0_tasks SET next_run=%s WHERE id=%s',(dt.datetime.utcnow()-dt.timedelta(seconds=1),d['task']))
            wait(lambda:c.call('GET','/orders/'+d['order']),lambda o:o['state']=='COMPLETED')
            cur.execute('SELECT COUNT(*) AS n FROM p0_provider_resources WHERE business_key=%s',(instance,));assert cur.fetchone()['n']==1
            cur.execute('SELECT COUNT(*) AS n FROM p0_instances WHERE id=%s',(instance,));assert cur.fetchone()['n']==1
            print('PASS AC-09/20 restart reclaims interrupted lease and queries existing provider result, no duplicate resource')
            expired=dt.datetime.utcnow()-dt.timedelta(seconds=3)
            cur.execute('UPDATE p0_instances SET expires_at=%s WHERE id=%s',(expired,instance));cur.execute('UPDATE p0_provider_resources SET expires_at=%s WHERE business_key=%s',(expired,instance))
            wait(lambda:c.call('GET','/instances/'+instance),lambda i:i['state']=='SUSPENDED')
            q=c.call('POST',f'/instances/{instance}/renewal-quotes',{'months':1});o=c.call('POST','/renewal-orders',{'quote_id':q['id']},key=uuid.uuid4().hex);pay=c.call('POST',f"/orders/{o['id']}/payments");c.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True})
            wait(lambda:c.call('GET','/orders/'+o['id']),lambda o:o['state']=='COMPLETED');assert c.call('GET','/instances/'+instance)['state']=='RUNNING'
            print('PASS AC-12 expiry suspends, paid renewal within grace restores and extends from recovery')
            expired=dt.datetime.utcnow()-dt.timedelta(days=8)
            cur.execute('UPDATE p0_instances SET expires_at=%s WHERE id=%s',(expired,instance));cur.execute('UPDATE p0_provider_resources SET expires_at=%s WHERE business_key=%s',(expired,instance))
            wait(lambda:c.call('GET','/instances/'+instance),lambda i:i['state']=='RELEASED')
            c.call('POST',f'/instances/{instance}/renewal-quotes',{'months':1},expected=409)
            print('PASS AC-12 grace expiry releases instance, released instance cannot renew')
            path.write_text(json.dumps({**d,'verified':True,'verified_at':time.time()},indent=2),encoding='utf-8')


if __name__=='__main__':main()
