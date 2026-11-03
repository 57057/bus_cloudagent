"""
 * 小滴课堂,愿景：让技术不再难学
 * @Remark 有问题联系我【xdclass68】
 * 源码-笔记-技术交流群,官网 https://xdclass.net
"""
import os
import re
from agent.config.providers import create_chat_model
from langchain_core.messages import HumanMessage, SystemMessage, AIMessage
from langgraph.prebuilt import create_react_agent

# 导入已经封装好的工具
from agent.tools.vector_tool import query_vector_db
from agent.tools.graph_tool import query_knowledge_graph
from agent.core.workflow.state import AgentState
from typing import Dict, Any
from agent.core.mcp.mcp_manager import get_mcp_manager

class ProductAgentNode:
    """
    包装了 LangGraph create_react_agent 的节点类
    供主图编排时直接调用
    """
    def __init__(self):

        self.llm = create_chat_model(
            temperature=0.1, 
        )
        from agent.tools.published_knowledge import managed
        self.tools = [query_vector_db] if managed() else [query_vector_db, query_knowledge_graph]

    async def __call__(self, state: AgentState) -> Dict[str, Any]:
        """
        供主 LangGraph 调用的处理函数
        """
        memory_context = state.get("memory_context", "")
        tools = list(self.tools)
        if state.get("user_context"):
            tools.extend(await get_mcp_manager().get_tools({"search_product_catalog"}))
        system_prompt = f"""你是一个专业的云服务平台【产品咨询Agent】。
你的任务是解答用户关于云产品（如云服务器ECS、专有网络VPC等）的疑问。
你有两个强大的检索工具可供使用：

1. `query_vector_db` (向量数据库检索):
   - 适用场景: 查询大段的概念解释、操作步骤说明、详细的规则政策。
   - 特点: 擅长处理模糊的语义匹配和长文本阅读。

2. `query_knowledge_graph` (知识图谱检索):
   - 适用场景: 查询云产品的架构、实体包含关系、具体的配置数值与限制、组合查询等结构化数据。
   - 特点: 擅长处理精确的属性、关系和多跳拓扑查询。

工作要求：
- 当前价格、库存、可购买规格必须调用 `search_product_catalog` 查询 Java 实时目录；金额字段以分为单位，除以100才是元。最终成交金额须在产品页面选配并获取报价。
- 向量库和图谱中的活动、价格、退款政策仅是示例资料，不能作为本平台当前交易承诺；资料不足就说明无法确认。
- 当前模拟环境可售类型以实时SKU为准，GPU、RDS、云盘等未配置供应能力时不得声称可购买。未调用实时目录时，不提供实时价格和库存数字。
- 仔细分析用户的问题，自主决定使用哪个工具，或者结合使用两个工具（如果问题很复杂）。
- 如果问题偏结构化参数（如网卡数、带宽、实例关系），优先尝试 `query_knowledge_graph`；若图谱查询超时或失败，自动降级为 `query_vector_db` 并继续完成回答。
- 如果问题同时包含结构化参数和规则解释，建议组合使用两个工具；但以可用性优先，不强制必须调用图谱。
- 优先通过工具获取事实依据，不要凭空捏造（幻觉）。
- 获取到信息后，请以专业、清晰、友好的客服口吻组织回答。
- 如果工具返回没有找到相关信息，请诚实地告诉用户目前知识库中没有相关记录。
- 答案来源只能引用工具原始返回中明确出现的来源名；禁止编造任何文档名、版本号、白皮书名称。
- 如果某工具未调用或调用失败，不要在“答案来源”中提及该工具，也不要解释为什么失败。
- 每次最终回答的结尾，只需列出实际获取到数据的来源，不要输出“可信度”，也不要输出“未使用”的工具。
  格式示例：
  答案来源：
  - 向量检索：xxx.md
  （如果只用到了向量检索，就只输出这一行；如果只用到了图谱，就只输出图谱检索结果；都用到了就输出两行）

【系统提供的用户记忆/背景上下文】:
{memory_context if memory_context else "暂无背景上下文。"}
"""
        # 使用 create_react_agent 创建一个内部的执行器
        inner_agent = create_react_agent(
            model=self.llm,
            tools=tools,
            prompt=system_prompt
        )
        
        print("💡 [ProductAgent] 正在处理产品咨询请求...")
        
        # 传递整个对话历史给内部 agent
        result = await inner_agent.ainvoke({"messages": state["messages"]}, config={"configurable": {
            "user_id": state.get("user_id"), "user_context": state.get("user_context", "")}})
        
        # 提取最后一条 AI 消息返回给主图
        final_message = result["messages"][-1]
        
        # 为了兼容主图的消息追加，我们将返回包装在 messages 列表中
        # Evidence comes from actual tool messages, not a model-written citation list.
        sources = []
        for message in result["messages"]:
            if (getattr(message, "type", None) == "tool"
                    and getattr(message, "name", None) == "query_vector_db"
                    and getattr(message, "status", "success") != "error"
                    and isinstance(message.content, str)):
                sources.extend(source.strip() for source in re.findall(r"【来源:\s*([^】]+)】", message.content)
                               if source.strip().lower() not in {"unknown", "未知"})
        metadata = dict(state.get("metadata", {}))
        metadata.pop("cache_candidate", None)
        dynamic_used = any(getattr(m, "type", None) == "tool" and getattr(m, "name", "") == "search_product_catalog"
                           for m in result["messages"])
        if sources and not dynamic_used:
            metadata["cache_candidate"] = {
                "kind": "product_knowledge", "source": "; ".join(dict.fromkeys(sources)),
            }
        return {"messages": [final_message], "metadata": metadata}

async def main():
    """Standalone interactive demo using the same node as the web workflow."""
    import asyncio
    node = ProductAgentNode()
    print("ProductAgent ready (quit / exit to stop)")
    while True:
        query = await asyncio.to_thread(input, "User: ")
        if query.strip().lower() in {"quit", "exit"}:
            break
        if not query.strip():
            continue
        result = await node({"messages": [("user", query)], "memory_context": ""})
        print(result["messages"][-1].content)


if __name__ == "__main__":
    import asyncio
    asyncio.run(main())
