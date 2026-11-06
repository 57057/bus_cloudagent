import os
import json
import re
from pathlib import Path
from agent.config import get_settings
from agent.config.providers import create_embeddings
from langchain_core.tools import tool

# ==============================================================================
# 修复 pymilvus 2.6.x 与 langchain-milvus 0.3.x 之间的兼容性问题
# ==============================================================================
def _patch_milvus_connections():
    # 仅完整模式加载大型SDK，轻量模式不需要安装Milvus。
    from pymilvus import connections
    if getattr(connections, '_cloudagent_compat', False):
        return
    original_fetch = connections._fetch_handler
    def patched_fetch(alias):
        try:
            return original_fetch(alias)
        except Exception:
            from pymilvus.client.connection_manager import ConnectionManager
            mgr = ConnectionManager.get_instance()
            for mc in list(mgr._registry.values()) + list(mgr._dedicated.values()):
                if f"cm-{id(mc.handler)}" == alias:
                    return mc.handler
            raise
    connections._fetch_handler = patched_fetch
    connections._cloudagent_compat = True
# ==============================================================================


_milvus_instance = None

def _query_local_documents(query: str) -> str:
    """Retrieve source passages locally when embeddings are not configured."""
    tokens = set(re.findall(r"[a-z0-9._-]+", query.lower()))
    for phrase in re.findall(r"[\u4e00-\u9fff]+", query):
        tokens.update(phrase[i:i+2] for i in range(len(phrase)-1))
    if not tokens:
        return "未在文档中检索到相关信息。"
    matches = []
    for path in (Path(__file__).resolve().parents[2] / "mock_data").glob("*.md"):
        text = path.read_text(encoding="utf-8")
        for start in range(0, len(text), 1000):
            passage = text[start:start+1400]
            score = sum(passage.lower().count(token) for token in tokens)
            if score:
                matches.append((score, path.name, passage))
    matches.sort(key=lambda item: item[0], reverse=True)
    return "\n\n".join(
        f"【来源: {source}；本地关键词检索】\n{passage}"
        for _, source, passage in matches[:3]
    ) or "未在文档中检索到相关信息。"

def _get_milvus_store():
    global _milvus_instance
    if _milvus_instance is not None:
        return _milvus_instance

    from langchain_milvus import Milvus
    _patch_milvus_connections()
    settings = get_settings()
    milvus_host = settings.milvus_host
    milvus_port = settings.milvus_port
    milvus_uri = f"http://{milvus_host}:{milvus_port}"

    print(f"🔌 [Init] 正在连接 Milvus 向量数据库: {milvus_uri}")
    embeddings = create_embeddings()
    connection_args = {"uri": milvus_uri, "timeout": 10}
    if settings.milvus_api_key:
        connection_args["token"] = settings.milvus_api_key

    _milvus_instance = Milvus(
        embedding_function=embeddings,
        connection_args=connection_args,
        collection_name="cloud_product_docs_" + settings.embedding_collection_suffix,
        auto_id=False,
        drop_old=False
    )
    return _milvus_instance

@tool
def query_vector_db(query: str) -> str:
    """
    通过语义搜索查询云产品的说明文档（RAG）。
    当用户询问大段的概念、操作步骤、详细规则（例如：退款规则、什么是专有网络VPC、如何创建实例）时，使用此工具。
    """
    from agent.tools import published_knowledge
    if published_knowledge.managed():
        try:
            return published_knowledge.retrieve(query)
        except Exception:
            return "当前发布知识库暂不可用，无法确认资料；请稍后重试。"
    if not get_settings().api_key_configured or get_settings().document_search_backend == "keywords":
        return _query_local_documents(query)
    try:
        store = _get_milvus_store()
        results = store.similarity_search_with_score(query, k=3)
        
        if not results:
            return "未在文档中检索到相关信息。"

        formatted_results = []
        for i, (doc, score) in enumerate(results):
            source = os.path.basename(doc.metadata.get('source', 'Unknown'))
            content = doc.page_content.strip()
            formatted_results.append(f"【来源: {source}】\n{content}")
            
        return "\n\n".join(formatted_results)
    except Exception as e:
        return f"查询向量数据库时发生错误: {str(e)}"
