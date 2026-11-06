"""Actual browser-facing chat API, asserting MCP execution via correlated Java audit evidence."""
import json,sys,requests
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT));sys.path.insert(0,str(ROOT/'tests'))
from p0_acceptance import Client
from p0_recovery import connection

def main():
    c=Client();a=json.loads((ROOT/'runtime/platform-admin.json').read_text());c.login(a['username'],a['password'])
    demo=json.loads((ROOT/'runtime/application-demo.json').read_text())
    session=c.call('POST','/chat/sessions',{'title':'应用部署真实链路验收'})
    query='请查询应用项目'+demo['project_id']+'的实际运行证据，并结合已发布运维手册说明重启与回滚的区别。只查询解释，不准备或执行操作。'
    response=c.session.post('http://127.0.0.1:5000/api/chat',json={'query':query,'session_id':session['id']},headers={'Authorization':'Bearer '+c.token},stream=True,timeout=180)
    assert response.status_code==200,response.status_code
    content=[];done=False;error=False
    for line in response.iter_lines():
        if line.startswith(b'data: '):
            data=json.loads(line[6:]);done=done or data.get('done',False);error=error or bool(data.get('error'))
            if data.get('content'):content.append(data['content'])
    assert done and content and not error
    request_id=response.headers['X-Request-Id']
    with connection() as db:
        with db.cursor() as cursor:
            cursor.execute("SELECT object_id FROM p0_audits WHERE action='AI_TOOL_QUERY' AND actor_id=%s AND request_id=%s",(c.user['id'],request_id))
            tools=[row['object_id'] for row in cursor.fetchall()]
    assert 'get_application_evidence' in tools,{'observed_tools':tools}
    (ROOT/'runtime/application-chat-check.json').write_text(json.dumps({'http':200,'sse_completed':done,'error':error,'chunks':len(content),'correlated_tool_calls':tools,'full_java_python_agent_mcp_chain':True},indent=2))
    (ROOT/'runtime/application-chat-answer.txt').write_text(''.join(content),encoding='utf-8')
    print('PASS /api/chat -> Java -> real model/Agent -> MCP evidence tool; correlated requestId audit and completed SSE')

if __name__=='__main__':main()
