"""Actual stdio MCP -> signed Java preview; no LLM, payment or external API calls."""
import asyncio
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from p0_acceptance import Client,ROOT
from p0_recovery import connection
from agent.core.mcp.mcp_manager import MCPManager
from langgraph.graph import StateGraph, MessagesState, START, END
from langgraph.prebuilt import ToolNode
from langchain_core.messages import AIMessage


def decode(value):
    if isinstance(value,list):value=''.join(part.get('text','') for part in value if isinstance(part,dict))
    return json.loads(value) if isinstance(value,str) else value


async def main():
    user=Client();name='mcp_p1_'+uuid.uuid4().hex[:10]
    user.call('POST','/auth/register',{'username':name,'password':'TestingPass123','agreement':True});user.login(name,'TestingPass123')
    with connection() as db,db.cursor() as cursor:
        cursor.execute('SELECT id FROM p0_sessions WHERE access_hash=%s',(hashlib.sha256(user.token.encode()).hexdigest(),));session=cursor.fetchone()['id']
    secret=(ROOT/'runtime/business-service.token').read_text().strip()
    payload=base64.urlsafe_b64encode(json.dumps({'user_id':user.user['id'],'auth_session':session,'session_id':'p1-mcp','exp':int(time.time()+180)}).encode()).decode().rstrip('=')
    context=payload+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(),payload.encode(),hashlib.sha256).digest()).decode().rstrip('=')
    os.environ['BUSINESS_SERVICE_TOKEN']=secret;os.environ['BUSINESS_BASE_URL']='http://127.0.0.1:5000'
    manager=MCPManager()
    try:
        tools=await manager.get_tools({'prepare_business_action'})
        # ToolRuntime is supplied by the actual LangGraph ToolNode, not standalone BaseTool.ainvoke.
        graph=StateGraph(MessagesState);graph.add_node('tools',ToolNode(tools));graph.add_edge(START,'tools');graph.add_edge('tools',END)
        state=await graph.compile().ainvoke({'messages':[AIMessage(content='',tool_calls=[{'id':'preview-test','name':'prepare_business_action','args':{'action':'PURCHASE','parameters':{'sku_id':'ecs-1','image':'ubuntu-22','disk_type':'ESSD','disk_gb':40,'bandwidth':1,'months':1,'quantity':1,'name':'MCP preview only'},'user_id':'forged','user_context':'forged'}}])]},config={'configurable':{'user_id':user.user['id'],'user_context':context}})
        result=decode(state['messages'][-1].content)
        assert result['status']=='success',result
        preview=result['data'];assert preview['state']=='PENDING'
        assert user.call('GET','/orders')['total']==0
        previews=user.call('GET','/assistant/actions');assert any(x['id']==preview['id'] for x in previews)
        order=user.call('POST',f"/assistant/actions/{preview['id']}/confirm",{'confirmed':True})
        assert order['state']=='PENDING_PAYMENT'
        user.call('POST',f"/orders/{order['id']}/cancel")
        (ROOT/'runtime/p1-mcp-check.json').write_text(json.dumps({'stdio_mcp':True,'signed_context':True,'forged_tool_identity_overridden':True,'preview_did_not_create_order':True,'browser_confirmation_required':True,'llm_called':False},indent=2),encoding='utf-8')
        print('PASS actual MCP: signed identity injection, preview only, explicit browser confirmation and no automatic payment')
    finally:await manager.close()


if __name__=='__main__':asyncio.run(main())
