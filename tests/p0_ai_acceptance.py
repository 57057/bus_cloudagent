"""Real SiliconFlow/Agent/Java tool isolation acceptance using generated demo data only."""
import base64
import hashlib
import hmac
import json
import time
import uuid
import requests
from p0_acceptance import Client,ROOT,BASE
from p0_recovery import connection


def main():
    c=Client();name='ai_check_'+uuid.uuid4().hex[:10]
    c.call('POST','/auth/register',{'username':name,'password':'TestingPass123','agreement':True});c.login(name,'TestingPass123')
    session=c.call('POST','/chat/sessions',{'title':'AI身份验收'})
    r=requests.post(BASE+'/api/chat',json={'query':'请使用工具查询我最近的订单，没有记录就明确告诉我暂无订单。','session_id':session['id'],'user_id':'user_1002'},headers={'Authorization':'Bearer '+c.token},timeout=180)
    assert r.status_code==200 and ('"done": true' in r.text or '"done":true' in r.text) and 'event: error' not in r.text,r.text[:1000]
    assert len(c.call('GET','/chat/sessions/'+session['id']))==2
    with connection() as db, db.cursor() as cur:
        cur.execute("SELECT * FROM p0_audits WHERE actor_id=%s AND action='AI_TOOL_QUERY'",(c.user['id'],))
        calls=cur.fetchall();assert any(v['object_id']=='query_user_orders' for v in calls),'Model did not use order tool'
        assert any(v['request_id']==r.headers['X-Request-Id'] for v in calls),'requestId did not reach tool'
        cur.execute('SELECT id FROM p0_sessions WHERE access_hash=%s',(hashlib.sha256(c.token.encode()).hexdigest(),));sid=cur.fetchone()['id']
    secret=(ROOT/'runtime/business-service.token').read_text().strip()
    payload=base64.urlsafe_b64encode(json.dumps({'user_id':c.user['id'],'session_id':session['id'],'auth_session':sid,'exp':int(time.time()+120)}).encode()).decode().rstrip('=')
    context=payload+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    headers={'X-Service-Token':secret,'X-User-Context':context}
    tool=BASE+'/internal/tools/query_user_orders'
    assert requests.post(tool,json={'user_id':'user_1002'},headers={'X-Service-Token':secret},timeout=15).status_code==401
    answer=requests.post(tool,json={'user_id':'user_1002'},headers=headers,timeout=15)
    assert answer.status_code==200 and answer.json()['data']==[],answer.text
    assert requests.post('http://127.0.0.1:5001/api/chat',json={'query':'test','session_id':'s','user_id':'u'},timeout=15).status_code==401
    c.call('POST','/auth/logout')
    assert requests.post(tool,json={'user_id':'user_1002'},headers=headers,timeout=15).status_code==401
    result={'live_model':True,'stream_completed':True,'content_events':r.text.count('"content"'),'tool_calls':[v['object_id'] for v in calls],'request_id_propagated':True,'forged_body_user_ignored':True,'service_token_without_user_context_rejected':True,'logout_revokes_delegated_context':True,'direct_python_rejected':True}
    (ROOT/'runtime/p0-ai-check.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS AC-02/17: real model -> protected MCP -> Java, trusted identity and requestId, logout revocation')


if __name__=='__main__':main()
