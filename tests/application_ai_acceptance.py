"""Application MCP isolation and optional real model routing; no execution confirmation tool."""
import asyncio,base64,hashlib,hmac,json,os,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tests.p0_acceptance import Client
from tests.p0_recovery import connection
from agent.core.mcp.mcp_manager import MCPManager
from langgraph.graph import StateGraph,MessagesState,START,END
from langgraph.prebuilt import ToolNode
from langchain_core.messages import AIMessage

def identity(user):
    with connection() as db:
        with db.cursor() as c:
            c.execute('SELECT id FROM p0_sessions WHERE access_hash=%s',(hashlib.sha256(user.token.encode()).hexdigest(),));session=c.fetchone()['id']
    secret=(ROOT/'runtime/business-service.token').read_text().strip()
    payload=base64.urlsafe_b64encode(json.dumps({'user_id':user.user['id'],'auth_session':session,'session_id':'application-ai','exp':int(time.time()+600)}).encode()).decode().rstrip('=')
    context=payload+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    os.environ['BUSINESS_SERVICE_TOKEN']=secret;os.environ['BUSINESS_BASE_URL']='http://127.0.0.1:5000'
    return context

async def main(live=False):
    s=json.loads((ROOT/'runtime/application-acceptance-state.json').read_text());user=Client();user.login(s['username'],s['password']);context=identity(user)
    manager=MCPManager()
    try:
        tools=await manager.get_tools({'list_application_projects','get_application_evidence','prepare_application_action'})
        graph=StateGraph(MessagesState);graph.add_node('tools',ToolNode(tools));graph.add_edge(START,'tools');graph.add_edge('tools',END);compiled=graph.compile()
        answer=await compiled.ainvoke({'messages':[AIMessage(content='',tool_calls=[{'id':'app-test','name':'get_application_evidence','args':{'project_id':s['project_id'],'user_id':'forged','user_context':'forged'}}])]},config={'configurable':{'user_id':user.user['id'],'user_context':context}})
        content=answer['messages'][-1].content
        if isinstance(content,list):content=''.join(b.get('text','') for b in content if isinstance(b,dict))
        data=json.loads(content);assert data['status']=='success',data
        assert data['data']['project']['id']==s['project_id']
        print('PASS actual ToolNode -> stdio MCP -> signed Java project evidence; forged identity overridden',flush=True)
        report={'actual_mcp':True,'signed_identity':True,'live_model':False,'confirmation_tool_exposed':False}
        if live:
            from agent.core.workflow.graph_manager import AgentGraphManager
            graph=AgentGraphManager().build_graph()
            result=await graph.ainvoke({'messages':[('user',f"请列出我的应用项目，并查看项目{s['project_id']}当前实际部署证据，解释运行状态和证据限制。只查询，不准备或执行操作，不要猜测故障。")],
                'user_id':user.user['id'],'user_context':context,'session_id':'application-ai','memory_context':'','metadata':{},'next_agent':''},
                config={'configurable':{'user_id':user.user['id'],'user_context':context}})
            assert result['metadata'].get('operational_answer') is True,result.get('next_agent')
            assert result['messages'][-1].content
            report['live_model']=True
            (ROOT/'runtime/application-ai-answer.txt').write_text(result['messages'][-1].content,encoding='utf-8')
            print('PASS real model router -> deployment Agent -> protected tools -> evidence explanation',flush=True)
        (ROOT/'runtime/application-ai-check.json').write_text(json.dumps(report,indent=2))
    finally:await manager.close()

if __name__=='__main__':asyncio.run(main('--live-model' in sys.argv))
