"""Build an idempotent source document index using SiliconFlow Qwen embeddings."""
from pathlib import Path
import hashlib
import sys
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from agent.config import get_settings
from agent.config.providers import create_embeddings, validate_vector_schema
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pymilvus import MilvusClient, DataType

def sync():
    settings = get_settings()
    if not settings.api_key_configured:
        print("Knowledge indexing is pending: fill SILICONFLOW_API_KEY in agent/.env.")
        return
    embedding = create_embeddings()
    client_args = {"uri": f"http://{settings.milvus_host}:{settings.milvus_port}", "timeout": 30}
    if settings.milvus_api_key:
        client_args["token"] = settings.milvus_api_key
    client = MilvusClient(**client_args)
    collection = "cloud_product_docs_" + settings.embedding_collection_suffix
    splitter = RecursiveCharacterTextSplitter(chunk_size=700, chunk_overlap=100)
    docs = []
    for path in sorted((ROOT / "mock_data").glob("*.md")):
        for chunk in splitter.split_text(path.read_text(encoding="utf-8")):
            identity = hashlib.sha256(f"{path.name}|{chunk}".encode()).hexdigest()
            docs.append({"pk": identity, "text": chunk, "source": path.name})
    try:
        if not client.has_collection(collection):
            schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
            schema.add_field("pk", DataType.VARCHAR, max_length=64, is_primary=True)
            schema.add_field("text", DataType.VARCHAR, max_length=8192)
            schema.add_field("source", DataType.VARCHAR, max_length=256)
            schema.add_field("vector", DataType.FLOAT_VECTOR, dim=settings.embedding_dimensions)
            indexes = client.prepare_index_params()
            indexes.add_index("vector", index_type="AUTOINDEX", metric_type="COSINE")
            client.create_collection(collection, schema=schema, index_params=indexes)
        else:
            validate_vector_schema(client, collection, field_name="vector")
        existing = {row["pk"] for row in client.query(collection, filter="", output_fields=["pk"], limit=16384)}
        pending = [doc for doc in docs if doc["pk"] not in existing]
        for start in range(0, len(pending), 16):
            batch = pending[start:start+16]
            vectors = embedding.embed_documents([doc["text"] for doc in batch])
            if len(vectors) != len(batch) or any(len(v) != settings.embedding_dimensions for v in vectors):
                raise ValueError("Embedding API returned an unexpected vector count or dimension")
            client.upsert(collection, [{**doc, "vector": vector} for doc, vector in zip(batch, vectors)])
            print(f"Knowledge indexed {min(start+16,len(pending))}/{len(pending)} passages", flush=True)
        client.flush(collection)
        print(f"Knowledge ready: {len(docs)} passages; {len(pending)} added to {collection}.")
    finally:
        client.close()

if __name__ == "__main__":
    sync()
