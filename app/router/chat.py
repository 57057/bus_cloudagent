from fastapi import APIRouter, HTTPException, Depends
from fastapi.responses import StreamingResponse
from app.schemas.chat import ChatRequest, SessionEndRequest
from app.service.chat_service import stream_chat, schedule_preference_save
from app.infra.internal_identity import internal_identity
import asyncio
import json

router = APIRouter()


@router.post("/chat/session/end")
async def end_session(request: SessionEndRequest, identity=Depends(internal_identity)):
    """Flush this session's unprocessed preferences without replaying old history."""
    if request.user_id != identity["user_id"] or request.session_id != identity["session_id"]:
        raise HTTPException(status_code=403, detail="会话身份不匹配")
    if identity.get("preferences_enabled"):
        schedule_preference_save(request.user_id, request.session_id, [], force=True)
    return {"status": "accepted"}

@router.post("/chat")
async def chat_endpoint(request: ChatRequest, identity=Depends(internal_identity)):
    """
    处理多智能体聊天请求，并使用 SSE (Server-Sent Events) 返回流式响应。
    如果命中 L1 语义缓存，将直接返回缓存结果。
    否则进入 Agent 图编排流程。
    """
    from agent.config import get_settings
    settings = get_settings()
    if not settings.api_key_configured:
        raise HTTPException(status_code=503, detail="请在 agent/.env 填写硅基流动 SILICONFLOW_API_KEY，然后重启后端。")
    if request.user_id != identity["user_id"] or request.session_id != identity["session_id"]:
        raise HTTPException(status_code=403, detail="会话身份不匹配")

    async def protected_stream():
        # Heartbeats let the proxy discover disconnects and cancel downstream generation.
        generator = stream_chat(request.query, identity["user_id"], identity["session_id"],
                                user_context=identity["context"],
                                preferences_enabled=bool(identity.get("preferences_enabled")))
        task = None
        try:
            yield 'event: tool_status\ndata: {"tool_status":"processing"}\n\n'
            while True:
                task = asyncio.create_task(anext(generator))
                while not task.done():
                    await asyncio.wait({task}, timeout=5)
                    if not task.done():
                        yield ": heartbeat\n\n"
                try:
                    yield task.result()
                except StopAsyncIteration:
                    break
        except asyncio.CancelledError:
            raise
        except Exception:
            yield 'event: error\ndata: {"error":"AI服务暂时不可用，请稍后重试"}\n\n'
        finally:
            if task and not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
            await generator.aclose()

    return StreamingResponse(protected_stream(), media_type="text/event-stream")


@router.delete("/internal/memory/session/{session_id}")
async def delete_session(session_id: str, identity=Depends(internal_identity)):
    if session_id != identity["session_id"]:
        raise HTTPException(status_code=403, detail="会话身份不匹配")
    from app.service import chat_service as cs
    cs._preference_buffer.pop(identity["user_id"], session_id)
    if cs.memory:
        await cs.memory.short_term.clear(identity["user_id"], session_id)
    return {"deleted": True}


@router.get("/internal/memory/preferences")
async def list_preferences(identity=Depends(internal_identity)):
    from app.service import chat_service as cs
    from agent.core.memory.long_term import COLLECTION_NAME
    if not cs.memory or not cs.memory.long_term.database_available:
        raise HTTPException(status_code=503, detail="记忆服务不可用")
    client = cs.memory.long_term._client
    rows = await asyncio.to_thread(client.query, collection_name=COLLECTION_NAME,
        filter="user_id == " + json.dumps(identity["user_id"]), output_fields=["id", "content", "memory_type"], limit=100)
    # Milvus int64 identifiers are strings in JSON to avoid JavaScript precision loss.
    return {"items": [{**row, "id": str(row["id"])} for row in rows]}


@router.delete("/internal/memory/preferences/{preference_id}")
async def delete_preference(preference_id: str, identity=Depends(internal_identity)):
    from app.service import chat_service as cs
    from agent.core.memory.long_term import COLLECTION_NAME
    if not preference_id.isdigit() or not cs.memory or not cs.memory.long_term.database_available:
        raise HTTPException(status_code=503, detail="记忆服务不可用或编号无效")
    user = identity["user_id"]
    pending = cs._preference_tasks.get(user)
    if pending:
        await asyncio.gather(pending, return_exceptions=True)
    await asyncio.to_thread(cs.memory.long_term._client.delete, collection_name=COLLECTION_NAME,
        filter="user_id == " + json.dumps(user) + " and id == " + str(int(preference_id)))
    return {"deleted": True}
