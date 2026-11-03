"""Shared SiliconFlow model configuration for chat and all embedding consumers."""
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from .settings import get_settings

def create_chat_model(**kwargs) -> ChatOpenAI:
    return ChatOpenAI(**get_settings().get_model_config(), **kwargs)

def create_embeddings(**kwargs) -> OpenAIEmbeddings:
    settings = get_settings()
    if not settings.api_key_configured:
        raise ValueError("SILICONFLOW_API_KEY is not configured in agent/.env")
    return OpenAIEmbeddings(
        api_key=settings.siliconflow_api_key,
        base_url=settings.embedding_base_url,
        model=settings.embedding_model,
        dimensions=settings.embedding_dimensions,
        check_embedding_ctx_length=False,
        model_kwargs={"encoding_format": "float"},
        chunk_size=16,
        request_timeout=60,
        max_retries=2,
        **kwargs,
    )

def validate_vector_schema(client, collection_name: str, field_name: str = "embedding"):
    expected = get_settings().embedding_dimensions
    fields = client.describe_collection(collection_name=collection_name).get("fields", [])
    field = next((f for f in fields if f["name"] == field_name), None)
    actual = int(field.get("params", {}).get("dim", 0)) if field else 0
    if actual != expected:
        raise ValueError(f"{collection_name}: vector dimension is {actual}, expected {expected}; data was not modified")
