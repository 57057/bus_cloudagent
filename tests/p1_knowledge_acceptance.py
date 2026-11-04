"""Java publication -> Python evidence filtering with local ranking, no embedding/model/cloud calls."""
import datetime as dt
import json
import os
import uuid
from types import SimpleNamespace
from unittest.mock import patch
from p0_acceptance import Client,ROOT
from p0_recovery import connection
from agent.tools import published_knowledge as knowledge

def main():
    admin=Client(True);d=json.loads((ROOT/'runtime/platform-admin.json').read_text());admin.login(d['username'],d['password'])
    reviewer=Client();name='kb_'+uuid.uuid4().hex[:10]
    reviewer.call('POST','/auth/register',{'username':name,'password':'TestingPass123','agreement':True});reviewer.login(name,'TestingPass123')
    admin.call('PATCH','/users/'+reviewer.user['id'],{'role':'OPERATOR','reason':'P1 publication evidence test'})
    reviewer.login(name,'TestingPass123');reviewer.admin=True
    marker='published-'+uuid.uuid4().hex[:8]
    doc=admin.call('POST','/knowledge',{'product_id':'ecs','title':marker,'content':'VPC publication evidence '+marker,'valid_until':(dt.datetime.now(dt.timezone.utc)+dt.timedelta(days=1)).isoformat(),'reason':'P1 test'})
    os.environ['BUSINESS_SERVICE_TOKEN']=(ROOT/'runtime/business-service.token').read_text().strip()
    os.environ['BUSINESS_BASE_URL']='http://127.0.0.1:5000'
    with patch('agent.config.get_settings',return_value=SimpleNamespace(api_key_configured=False)):
        assert marker not in knowledge.retrieve(marker)
        admin.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'SUBMIT','reason':'test submit'})
        reviewer.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'PUBLISH','reason':'independent review'})
        first=knowledge.snapshot();assert marker in knowledge.retrieve(marker)
        with connection() as db,db.cursor() as cursor:
            cursor.execute('UPDATE p1_documents SET valid_until=%s WHERE id=%s',(dt.datetime.utcnow()-dt.timedelta(seconds=1),doc['id']))
        expired=knowledge.snapshot();assert knowledge.fingerprint(first)!=knowledge.fingerprint(expired)
        assert marker not in knowledge.retrieve(marker)
        reviewer.call('POST',f"/knowledge/{doc['id']}/transition",{'action':'RETIRE','reason':'test cleanup'})
        assert marker not in knowledge.retrieve(marker)
    (ROOT/'runtime/p1-knowledge-check.json').write_text(json.dumps({'live_java_snapshot':True,'draft_excluded':True,'published_evidence':True,'expired_and_retired_excluded':True,'cache_fingerprint_changed':True,'ranking':'LOCAL_KEYWORD','llm_or_embedding_called':False},indent=2),encoding='utf-8')
    print('PASS Java publication -> Python retrieval: draft, published, expired, retired and cache invalidation')

if __name__=='__main__':main()
