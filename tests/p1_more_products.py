"""Smoke-test the explicitly simulated GPU, RDS and disk lifecycle; never allocates cloud resources."""
from p0_acceptance import Client,ROOT,wait
import json
import uuid

def main():
    c=Client();name='types_'+uuid.uuid4().hex[:10]
    c.call('POST','/auth/register',{'username':name,'password':'TestingPass123','agreement':True});c.login(name,'TestingPass123')
    for product in ('gpu','rds','disk'):
        skus=c.call('GET',f'/products/{product}/skus');assert any(s['id']=='p1-demo-'+product for s in skus)
        cfg={'sku_id':'p1-demo-'+product,'image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'simulated-'+product}
        q=c.call('POST','/quotes',cfg);order=c.call('POST','/orders',{'quote_id':q['id']},key=uuid.uuid4().hex)
        pay=c.call('POST',f"/orders/{order['id']}/payments");c.call('POST',f"/payments/{pay['id']}/simulate",{'confirmed':True})
        order=wait(lambda:c.call('GET','/orders/'+order['id']),lambda o:o['state']=='COMPLETED')
        instance=order['items'][0]['instance_id'];assert c.call('GET','/instances/'+instance)['simulated']
        c.call('POST',f'/instances/{instance}/actions',{'action':'RELEASE','confirmed':True},key=uuid.uuid4().hex)
        wait(lambda:c.call('GET','/instances/'+instance),lambda r:r['state']=='RELEASED')
        print('PASS simulated product lifecycle:',product,flush=True)
    (ROOT/'runtime/p1-product-check.json').write_text(json.dumps({'types':['GPU','RDS','DISK'],'paid_provision_and_release':True,'real_resources_created':False},indent=2),encoding='utf-8')

if __name__=='__main__':main()
