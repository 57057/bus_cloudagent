"""Application settings using Pydantic Settings."""

import os
from functools import cached_property, lru_cache
from pathlib import Path
from typing import Any, Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""
    
    model_config = SettingsConfigDict(
        env_file=Path(__file__).resolve().parents[1] / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )
    
    # LLM Configuration
    dashscope_api_key: str = Field(default="", alias="DASHSCOPE_API_KEY")
    deepseek_api_key: str = Field(default="", alias="DEEPSEEK_API_KEY")
    siliconflow_api_key: str = Field(default="", alias="SILICONFLOW_API_KEY")
    model: str = Field(default="deepseek-ai/DeepSeek-V3.2", alias="MODEL")
    base_url: str = Field(default="https://api.siliconflow.cn/v1", alias="BASE_URL")
    embedding_model: str = Field(default="Qwen/Qwen3-Embedding-4B", alias="EMBEDDING_MODEL")
    embedding_base_url: str = Field(default="https://api.siliconflow.cn/v1", alias="EMBEDDING_BASE_URL")
    embedding_dimensions: int = Field(default=1536, alias="EMBEDDING_DIMENSIONS", gt=0)
    
    # MCP Configuration
    mcp_servers_config: Path = Field(
        default=Path(__file__).parent / "mcp_servers.json",
        alias="MCP_SERVERS_CONFIG"
    )
    
    # Weather API
    openweather_api_key: str | None = Field(default=None, alias="OPENWEATHER_API_KEY")
    
    # Redis (short-term memory)
    redis_url: str = Field(default="redis://localhost:6379", alias="REDIS_URL")
    redis_ttl: int = Field(default=1800, alias="REDIS_TTL")  # seconds
    semantic_cache_revision: str = Field(default="reviewed-v1", alias="SEMANTIC_CACHE_REVISION")
    cache_min_requests: int = Field(default=3, ge=1, alias="CACHE_MIN_REQUESTS")
    cache_frequency_window: int = Field(default=86400, ge=1, alias="CACHE_FREQUENCY_WINDOW")
    cache_sliding_ttl: int = Field(default=86400, ge=1, alias="CACHE_SLIDING_TTL")
    cache_max_age: int = Field(default=604800, ge=1, alias="CACHE_MAX_AGE")

    mysql_host: str = Field(default="localhost", alias="MYSQL_HOST")
    mysql_port: int = Field(default=3306, alias="MYSQL_PORT")
    mysql_user: str = Field(default="root", alias="MYSQL_USER")
    mysql_password: str = Field(default="", alias="MYSQL_PASSWORD")
    mysql_database: str = Field(default="cloud_platform", alias="MYSQL_DATABASE")
    
    # Milvus (long-term memory)
    milvus_host: str = Field(default="localhost", alias="MILVUS_HOST")
    milvus_port: int = Field(default=19530, alias="MILVUS_PORT")
    milvus_api_key: str | None = Field(default=None, alias="MILVUS_API_KEY")
    
    # Neo4j (knowledge graph)
    neo4j_uri: str = Field(default="bolt://localhost:7687", alias="NEO4J_URI")
    neo4j_user: str = Field(default="neo4j", alias="NEO4J_USER")
    neo4j_password: str = Field(default="password", alias="NEO4J_PASSWORD")
    neo4j_database: str = Field(default="neo4j", alias="NEO4J_DATABASE")
    
    # Logging
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")
    # 小内存演示显式关闭重型依赖；默认保持完整模式。
    vector_backend_enabled: bool = Field(default=True, alias="VECTOR_BACKEND_ENABLED")
    graph_backend_enabled: bool = Field(default=True, alias="GRAPH_BACKEND_ENABLED")
    document_search_backend: Literal["milvus", "keywords"] = Field(default="milvus", alias="DOCUMENT_SEARCH_BACKEND")
    
    @field_validator("dashscope_api_key")
    @classmethod
    def validate_api_key(cls, v: str) -> str:
        """Validate API key is not empty."""
        return v.strip()
    
    def get_model_config(self) -> dict[str, Any]:
        """Get model configuration for LangChain."""
        config: dict[str, Any] = {
            "model": self.model,
            "api_key": self.siliconflow_api_key or "not-configured",
        }
        if self.base_url:
            config["base_url"] = self.base_url
        return config

    @property
    def api_key_configured(self) -> bool:
        return bool(self.siliconflow_api_key.strip() and not self.siliconflow_api_key.startswith("YOUR_"))

    @property
    def embedding_collection_suffix(self) -> str:
        import hashlib
        fingerprint = hashlib.sha256(
            f"{self.embedding_base_url}|{self.embedding_model}|{self.embedding_dimensions}".encode()
        ).hexdigest()[:12]
        return f"sf_{fingerprint}_{self.embedding_dimensions}"

    @cached_property
    def cache_knowledge_fingerprint(self) -> str:
        """Snapshot document contents at process startup; restart after publishing knowledge."""
        import hashlib
        digest = hashlib.sha256()
        folder = Path(__file__).resolve().parents[2] / "mock_data"
        for path in sorted(folder.glob("*.md")):
            digest.update(path.name.encode())
            digest.update(hashlib.sha256(path.read_bytes()).digest())
        return digest.hexdigest()[:16]


@lru_cache
def get_settings() -> Settings:
    """Get cached settings instance."""
    return Settings()
