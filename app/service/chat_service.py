import asyncio
import json
import logging

# 初始化 Agent 和 Graph
from agent.core.workflow.graph_manager import AgentGraphManager
from agent.core.memory.memory_manager import MemoryManager
from agent.core.memory.preference_buffer import PreferenceBuffer
from app.infra.cache import semantic_cache
from app.infra.question_frequency import question_frequency

# Global variables for graph and memory
graph = None
memory = None
preference_llm = None
_preference_tasks = {}
_preference_buffer = PreferenceBuffer()
logger = logging.getLogger(__name__)

async def init_agent_system():
    global graph, memory, preference_llm
    if graph is None:
        logger.info("Initializing agent workflow")
        graph_manager = AgentGraphManager()
        graph = graph_manager.build_graph()
        preference_llm = graph_manager.orchestrator.llm
        
        logger.info("Initializing memory")
        from agent.config import get_settings
        settings = get_settings()
        memory = MemoryManager(
            redis_url=settings.redis_url,
            redis_ttl=settings.redis_ttl,
            milvus_host=settings.milvus_host,
            milvus_port=settings.milvus_port,
            milvus_api_key=settings.milvus_api_key,
            embedding_api_key=settings.siliconflow_api_key,
        )
        await memory.initialize()
        await semantic_cache.initialize()
        await question_frequency.initialize()
        logger.info("Agent system ready")

def schedule_preference_save(user_id, session_id, messages, *, force=False):
    """Serialize extraction per user; retain tasks until application shutdown."""
    if not memory or not memory.long_term.available or preference_llm is None:
        return
    if force:
        snapshot = _preference_buffer.pop(user_id, session_id)
        snapshot.extend(dict(m) for m in messages if m.get("role") == "user")
    else:
        snapshot = _preference_buffer.add(user_id, session_id, messages)
    if not snapshot:
        return
    previous = _preference_tasks.get(user_id)
    manager, model = memory, preference_llm

    async def save():
        try:
            if previous is not None:
                await previous
            await asyncio.wait_for(
                manager.background_extract(user_id, session_id, model, messages=snapshot),
                timeout=60,
            )
        except Exception:
            logger.exception("Preference extraction failed for user %s", user_id)
        finally:
            if _preference_tasks.get(user_id) is asyncio.current_task():
                _preference_tasks.pop(user_id, None)

    _preference_tasks[user_id] = asyncio.create_task(save())


async def drain_preference_tasks():
    """Finish pending writes before closing memory clients."""
    for (user_id, session_id), messages in _preference_buffer.flush():
        schedule_preference_save(user_id, session_id, messages, force=True)
    if _preference_tasks:
        await asyncio.gather(*list(_preference_tasks.values()), return_exceptions=True)


async def _extract_memory_context(user_id: str, session_id: str, query: str, preferences_enabled=True) -> str:
    context_parts = []
    if memory and memory.short_term.available:
        history = await memory.get_recent_messages(user_id, session_id)
        if history:
            recent_history = history[-10:] if len(history) > 10 else history
            context_parts.append("【近期对话历史】:")
            for msg in recent_history:
                role = "User" if msg["role"] == "user" else "Assistant"
                context_parts.append(f"{role}: {msg['content']}")
    
    if preferences_enabled and memory and memory.long_term.available:
        prefs = await memory.load_preferences(user_id, query)
        if prefs:
            context_parts.append("\n【用户长期偏好/背景】:")
            for p in prefs:
                context_parts.append(f"- {p}")
                
    return "\n".join(context_parts)

def cache_write_allowed(query, context, result):
    """Only standalone source-backed product knowledge is eligible for a public cache."""
    if context or result.get("next_agent") != "product_agent":
        return False
    if any(term in query.lower() for term in (
        "我的", "我买", "订单", "账单", "监控", "状态", "现在", "今天", "余额", "偏好", "记住",
        "这个", "那个", "它", "上面", "刚才", "my ", "order", "usage", "balance",
        "我", "预算", "推荐", "选型", "优惠", "活动", "价格", "多少钱", "实时", "i am", "i need", "our ",
    )):
        return False
    candidate = result.get("metadata", {}).get("cache_candidate", {})
    answer = result["messages"][-1].content
    return (candidate.get("kind") == "product_knowledge" and bool(candidate.get("source"))
            and isinstance(answer, str) and bool(answer.strip())
            and not any(term in answer.lower() for term in (
                "未找到", "没有相关", "暂无相关", "发生错误", "稍后重试", "无法确定", "不知道",
                "no relevant", "not found", "error", "unavailable")))


async def stream_chat(query: str, user_id: str, session_id: str, *, user_context="", preferences_enabled=True):
    request_count = await question_frequency.record(query)
    from agent.tools import published_knowledge
    knowledge_tag = ""
    if published_knowledge.managed():
        try:
            knowledge_tag = published_knowledge.fingerprint(await asyncio.to_thread(published_knowledge.snapshot))
        except Exception:
            knowledge_tag = None
    cache_hit = await semantic_cache.get_cache(query, user_id)
    if knowledge_tag is None or (knowledge_tag and cache_hit and not cache_hit.get("source", "").startswith(knowledge_tag + "|")):
        cache_hit = None
    action_previews = []
    if cache_hit:
        response_text = cache_hit["answer"]
        logger.info("Cache hit: %s distance=%.4f", cache_hit['level'], cache_hit['distance'])
    else:
        logger.info("Invoking agent workflow")
        mem_context = await _extract_memory_context(user_id, session_id, query, preferences_enabled)
        state = {
            "messages": [("user", query)],
            "user_id": user_id,
            "user_context": user_context,
            "session_id": session_id,
            "memory_context": mem_context,
            "next_agent": "",
            "metadata": {}
        }
        config = {"configurable": {"user_id": user_id, "user_context": user_context}}
        result = await asyncio.to_thread(asyncio.run, graph.ainvoke(state, config=config)) if not asyncio.iscoroutinefunction(graph.ainvoke) else await graph.ainvoke(state, config=config)
        response_text = result["messages"][-1].content
        action_previews = result.get("metadata", {}).get("action_previews", [])
        if (knowledge_tag is not None and cache_write_allowed(query, mem_context, result) and semantic_cache.available
                and request_count >= question_frequency.settings.cache_min_requests):
            try:
                await asyncio.wait_for(semantic_cache.set_cache(
                    query, response_text, is_general=True, request_count=request_count,
                    source=(knowledge_tag + "|" if knowledge_tag else "") + result["metadata"]["cache_candidate"]["source"],
                ), timeout=10)
            except Exception:
                logger.exception("High-frequency answer cache write failed; returning generated answer")
    
    # Snapshot extraction works even if Redis is unavailable.
    turn = [
        {"role": "user", "content": query},
        {"role": "assistant", "content": response_text},
    ]
    if memory and memory.short_term.available:
        await memory.save_conversation(user_id, session_id, turn)
    if preferences_enabled:
        schedule_preference_save(user_id, session_id, turn)
        
    for preview in action_previews:
        yield f"data: {json.dumps({'action_preview': preview}, ensure_ascii=False)}\n\n"

    # 流式返回大模型结果
    chunk_size = 5
    for i in range(0, len(response_text), chunk_size):
        chunk = response_text[i:i+chunk_size]
        yield f"data: {json.dumps({'content': chunk})}\n\n"
        await asyncio.sleep(0.02)
        
    yield f"data: {json.dumps({'done': True})}\n\n"
