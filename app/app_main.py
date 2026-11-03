import sys
import os

# 将 agent 目录加入 sys.path
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager

from app.router import chat
from app.service.chat_service import init_agent_system

@asynccontextmanager
async def lifespan(app: FastAPI):
    # 启动时初始化
    await init_agent_system()
    try:
        yield
    finally:
        from app.service import chat_service
        from app.infra.cache import semantic_cache
        await chat_service.drain_preference_tasks()
        from app.infra.question_frequency import question_frequency
        await question_frequency.close()
        from agent.core.mcp.mcp_manager import get_mcp_manager
        await get_mcp_manager().close()
        if chat_service.memory:
            await chat_service.memory.close()
        if semantic_cache._client:
            semantic_cache._client.close()

app = FastAPI(title="Multi-Agent Cloud Service API", lifespan=lifespan)

# 配置跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(chat.router, prefix="/api")

@app.get("/health")
async def health():
    from agent.config import get_settings
    from app.service import chat_service
    settings = get_settings()
    return {
        "status": "ok",
        "model": settings.model,
        "provider": "siliconflow",
        "llm_configured": settings.api_key_configured,
        "embedding_model": settings.embedding_model,
        "embedding_dimensions": settings.embedding_dimensions,
        "short_term_memory": bool(chat_service.memory and chat_service.memory.short_term.available),
        "milvus_connected": bool(chat_service.memory and chat_service.memory.long_term.database_available),
        "long_term_memory": bool(chat_service.memory and chat_service.memory.long_term.available),
        "semantic_cache": semantic_cache_status(),
        "document_search": "milvus" if settings.api_key_configured else "local_keywords",
    }

def semantic_cache_status():
    from app.infra.cache import semantic_cache
    return semantic_cache.available

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.app_main:app", host="127.0.0.1", port=5001, reload=True)
