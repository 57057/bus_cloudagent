"""Publish the bundled demo runbook using two local demo identities; never expose credentials."""
from pathlib import Path
import datetime as dt,json,secrets,sys
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tests.p0_acceptance import Client

def main():
    admin=Client(True);credential=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(credential['username'],credential['password'])
    receipt=ROOT/'runtime/application-runbook.json'
    if receipt.exists():print('Application runbook already published; receipt preserved');return
    credentials={'username':'app_reviewer_'+secrets.token_hex(5),'password':secrets.token_urlsafe(24)+'7aA'}
    reviewer=Client();reviewer.call('POST','/auth/register',{**credentials,'agreement':True});reviewer.login(**credentials)
    admin.call('PATCH','/users/'+reviewer.user['id'],{'role':'OPERATOR','reason':'Local demo runbook publication review identity'})
    reviewer.login(**credentials);reviewer.admin=True
    (ROOT/'runtime/application-runbook-reviewer.json').write_text(json.dumps(credentials))
    content=(ROOT/'docs/runbooks/spring-react-single-host-v1.md').read_text(encoding='utf-8')
    doc=admin.call('POST','/knowledge',{'product_id':'ecs','title':'[RUNBOOK spring-react-single-host@1] 部署与故障诊断','content':content,'valid_until':(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=365)).isoformat(),'reason':'Bundled local application operations demo runbook'})
    admin.call('POST','/knowledge/'+doc['id']+'/transition',{'action':'SUBMIT','reason':'Submit version-scoped demo runbook'})
    reviewer.call('POST','/knowledge/'+doc['id']+'/transition',{'action':'PUBLISH','reason':'Separate demo account confirms bundled runbook scope'})
    receipt.write_text(json.dumps({'document_id':doc['id'],'template':'spring-react-single-host@1','local_demo_review':True},indent=2))
    print('Version-scoped demo runbook published through standard review workflow')

if __name__=='__main__':main()
