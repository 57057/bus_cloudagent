"""Business assistant can only prepare server-validated previews; no confirmation/payment tool exists."""
import json
from langgraph.prebuilt import create_react_agent
from agent.config.providers import create_chat_model
from agent.core.mcp.mcp_manager import get_mcp_manager


def collect_previews(messages):
    previews = []
    for message in messages:
        if getattr(message, "type", "") == "tool" and getattr(message, "name", "") == "prepare_business_action":
            try:
                content = message.content
                if isinstance(content, list):
                    content = "".join(block.get("text", "") for block in content if isinstance(block, dict) and block.get("type") == "text")
                value = json.loads(content) if isinstance(content, str) else content
                if value.get("status") == "success" and value.get("data", {}).get("id"):
                    previews.append(value["data"])
            except (ValueError, TypeError):
                pass
    return previews


class BusinessAgentNode:
    def __init__(self):
        self.llm = create_chat_model(temperature=0.1)

    async def __call__(self, state):
        tools = await get_mcp_manager().get_tools({"prepare_business_action", "search_product_catalog", "query_user_orders", "query_user_instances"})
        agent = create_react_agent(model=self.llm, tools=tools, prompt="""你是租用平台的辅助办理助手。
你只能准备 PURCHASE/RENEWAL/REFUND 操作预览，不能确认、付款或修改资源。
先用实时查询获取当前用户的资源和订单，购买时先检索可售SKU。缺少字段必须问用户，不得猜测ID。
购买 parameters 必须包含 sku_id,image(ubuntu-22或rocky-9),disk_type(ESSD),disk_gb,bandwidth,name,months,quantity。
续费 parameters 包含 instance_id,months。退款 parameters 包含 order_id,item_id,reason；不能猜子项ID，缺少时请用户从订单详情取得。
prepare_business_action 返回的编号、金额、有效期为权威预览。明确提醒用户核对后在页面确认；购买和续费确认后还需要用户付款。
即使用户在聊天里说确认，也不能调用确认接口；让用户点击结构化预览卡上的确认按钮。
工具返回没有权限或配置无效时说明原因，不虚构成功。""")
        result = await agent.ainvoke({"messages": state["messages"]}, config={"configurable": {
            "user_id": state.get("user_id"), "user_context": state.get("user_context", "")}, "recursion_limit": 12})
        metadata = dict(state.get("metadata", {}))
        metadata.pop("cache_candidate", None)
        metadata["action_previews"] = collect_previews(result["messages"])
        return {"messages": [result["messages"][-1]], "metadata": metadata}
