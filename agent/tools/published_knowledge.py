"""Published knowledge boundary: consult Java on every retrieval; never serve retired/draft content."""
import hashlib
import json
import os
import re
import threading
import requests

_lock = threading.Lock()
_indexed = set()


def managed():
    return bool(os.getenv("BUSINESS_SERVICE_TOKEN"))


def snapshot():
    response = requests.get(os.getenv("BUSINESS_BASE_URL", "http://127.0.0.1:5000") + "/internal/knowledge/snapshot",
        headers={"X-Service-Token": os.getenv("BUSINESS_SERVICE_TOKEN", "")}, timeout=4)
    response.raise_for_status()
    data = response.json()
    if not isinstance(data.get("documents"), list):
        raise ValueError("Invalid published knowledge snapshot")
    return data


def fingerprint(data):
    # IDs also change when a document expires or its product is taken off sale without a publication edit.
    values = [(d["id"], d["version"], d["valid_until"]) for d in data["documents"]]
    return "published:" + hashlib.sha256(json.dumps([data["revision"], values], sort_keys=True).encode()).hexdigest()[:20]


def chunks(data):
    return [{"id": hashlib.sha256(f"{doc['id']}:{i}".encode()).hexdigest(),
             "publication_id": doc["id"], "title": doc["title"], "version": doc["version"],
             "text": doc["content"][i:i + 1200]}
            for doc in data["documents"] for i in range(0, len(doc["content"]), 1000)]


def retrieve(query, title_prefix=None):
    data = snapshot()  # Failure is intentionally closed: no fallback to old mock documents.
    if title_prefix:
        data = {**data, "documents": [d for d in data["documents"] if d["title"].startswith(title_prefix)]}
    passages = chunks(data)
    if not passages:
        return "暂无已审核、已发布且有效的知识资料，请联系运营发布文档。"
    from agent.config import get_settings
    settings = get_settings()
    if not settings.api_key_configured or getattr(settings, "document_search_backend", "milvus") == "keywords":
        words = set(re.findall(r"[a-z0-9_-]+|[\u4e00-\u9fff]{2}", query.lower()))
        ranked = sorted(passages, key=lambda d: sum(d["text"].lower().count(w) for w in words), reverse=True)
        ranked = [d for d in ranked if any(w in d["text"].lower() for w in words)][:3]
    else:
        from pymilvus import MilvusClient
        from agent.config.providers import create_embeddings
        args = {"uri": f"http://{settings.milvus_host}:{settings.milvus_port}", "timeout": 10}
        if settings.milvus_api_key:
            args["token"] = settings.milvus_api_key
        client = MilvusClient(**args)
        embeddings = create_embeddings()
        collection = "p1_published_" + settings.embedding_collection_suffix
        revision = fingerprint(data)
        try:
            with _lock:
                if revision not in _indexed:
                    vectors = embeddings.embed_documents([d["text"] for d in passages])
                    if not client.has_collection(collection_name=collection):
                        client.create_collection(collection_name=collection, dimension=len(vectors[0]),
                            primary_field_name="id", id_type="string", max_length=64,
                            vector_field_name="vector", metric_type="COSINE", auto_id=False, enable_dynamic_field=True)
                    for start in range(0, len(passages), 100):
                        client.upsert(collection_name=collection, data=[dict(d, vector=v)
                            for d, v in zip(passages[start:start+100], vectors[start:start+100])])
                    _indexed.clear()
                    _indexed.add(revision)
            ids = [doc["id"] for doc in data["documents"]]
            hits = client.search(collection_name=collection, data=[embeddings.embed_query(query)], limit=3,
                filter="publication_id in " + json.dumps(ids), output_fields=["id", "publication_id", "text", "title", "version"],
                consistency_level="Strong")
            ranked = [hit["entity"] for hit in hits[0]]
        finally:
            client.close()
    # Recheck after embedding/search so a concurrent retirement cannot enter the returned evidence.
    current = snapshot()
    allowed = {d["id"] for d in current["documents"] if not title_prefix or d["title"].startswith(title_prefix)}
    return "\n\n".join(f"【来源: {d['title']} v{d['version']} ({d['publication_id']})】\n{d['text']}"
        for d in ranked if d["publication_id"] in allowed) or "未在当前有效的发布文档中检索到相关资料。"
