"""Layered reads with high-frequency general-answer admission and sliding expiration."""
import hashlib
import json
import logging
import time
from agent.config import get_settings
from agent.config.providers import create_embeddings, validate_vector_schema

logger = logging.getLogger(__name__)
L1_SEMANTIC_DISTANCE_THRESHOLD = 0.08


class SemanticCache:
    def __init__(self):
        self._client = None
        self._embeddings = None
        self._available = False
        self.database_available = False
        self.settings = get_settings()
        revision = hashlib.sha256((self.settings.semantic_cache_revision + "|" +
            self.settings.cache_knowledge_fingerprint).encode()).hexdigest()[:12]
        self.collection = "qa_hot_v2_" + self.settings.embedding_collection_suffix + "_" + revision

    async def initialize(self):
        try:
            from pymilvus import MilvusClient
            args = {"uri": f"http://{self.settings.milvus_host}:{self.settings.milvus_port}", "timeout": 10}
            if self.settings.milvus_api_key:
                args["token"] = self.settings.milvus_api_key
            self._client = MilvusClient(**args)
            self._ensure_collection()
            self.database_available = True
            if self.settings.api_key_configured:
                self._embeddings = create_embeddings()
                self._available = True
        except Exception:
            logger.exception("Semantic cache initialization failed")
            self._available = False

    async def set_cache(self, query, response, user_id=None, scope="public", *,
                        source="", ttl_seconds=None, request_count=0, is_general=False):
        owner = user_id or ""
        cache_scope = "user" if owner else scope
        if cache_scope not in {"user", "public"} or (cache_scope == "user" and not owner):
            raise ValueError("Invalid cache scope or owner")
        if not is_general or request_count < self.settings.cache_min_requests:
            raise ValueError("Only high-frequency general answers may be cached")
        if ttl_seconds is None:
            ttl_seconds = self.settings.cache_sliding_ttl
        if not query.strip() or not response.strip() or not source.strip() or ttl_seconds <= 0:
            raise ValueError("Non-empty question, answer, source and positive TTL required")
        if not self._available:
            raise RuntimeError("Semantic cache unavailable; answer was not cached")
        normalized = self._normalize(query)
        identity = hashlib.sha256(json.dumps([cache_scope, owner, normalized]).encode()).hexdigest()
        embedding = await self._embeddings.aembed_query(normalized)
        now = int(time.time())
        hard_expires_at = now + self.settings.cache_max_age
        self._client.upsert(collection_name=self.collection, data=[{
            "id": identity, "question": query.strip(), "question_norm": normalized,
            "answer": response, "source": source, "scope": cache_scope, "user_id": owner,
            "is_general": 1, "enabled": 1,
            "created_at": now, "hard_expires_at": hard_expires_at,
            "expires_at": min(now + ttl_seconds, hard_expires_at),
            "sliding_ttl": ttl_seconds, "embedding": embedding,
        }])

    async def get_cache(self, query, user_id=None, *, allow_semantic=True):
        if not self._available:
            return None
        normalized = self._normalize(query)
        valid = f"enabled == 1 and expires_at > {int(time.time())}"
        public = 'scope == "public" and is_general == 1'
        private = f'scope == "user" and user_id == {json.dumps(user_id)}' if user_id else None
        fields = ["id", "question", "answer", "source", "scope", "user_id",
                  "expires_at", "hard_expires_at", "sliding_ttl"]
        try:
            # Preserve the original precedence: user exact, public exact, scoped semantic.
            for scope_filter in ([private, public] if private else [public]):
                rows = self._client.query(collection_name=self.collection,
                    filter=f"{valid} and ({scope_filter}) and question_norm == {json.dumps(normalized)}",
                    output_fields=fields, limit=1)
                if rows:
                    return self._renew_hit(rows[0], 0.0, "L1_EXACT")
            if not allow_semantic:
                return None
            vector = await self._embeddings.aembed_query(normalized)
            scopes = f"({public}) or ({private})" if private else public
            results = self._client.search(collection_name=self.collection,
                data=[vector], filter=f"{valid} and ({scopes})", limit=1, output_fields=fields)
            if not results or not results[0]:
                return None
            hit = results[0][0]
            distance = 1.0 - float(hit.get("distance", 0.0))
            if distance > L1_SEMANTIC_DISTANCE_THRESHOLD:
                return None
            entity = dict(hit.get("entity", {}))
            entity.setdefault("id", hit.get("id"))
            return self._renew_hit(entity, distance, "L1_SEMANTIC")
        except Exception:
            logger.exception("Semantic cache lookup failed; falling back to workflow")
            return None

    def _renew_hit(self, entity, distance, level):
        now = int(time.time())
        expires_at = entity.get("expires_at")
        hard_limit = entity.get("hard_expires_at")
        if expires_at is not None and expires_at <= now:
            return None
        if hard_limit is not None and hard_limit <= now:
            return None
        if entity.get("id") and hard_limit is not None:
            new_expiry = min(now + entity["sliding_ttl"], hard_limit)
            if new_expiry > expires_at:
                try:
                    # Milvus 2.6 partial upsert: keep vector, content and original age unchanged.
                    self._client.upsert(collection_name=self.collection,
                        data=[{"id": entity["id"], "expires_at": new_expiry}], partial_update=True)
                except Exception:
                    logger.exception("Cache renewal failed; serving the still-valid answer")
        return self._format_hit(entity, distance, level)

    @staticmethod
    def _format_hit(entity, distance, level):
        return {"answer": entity["answer"], "matched_question": entity["question"],
                "source": entity.get("source", ""), "scope": entity.get("scope", "public"),
                "distance": distance, "level": level}

    @property
    def available(self):
        return self._available

    @staticmethod
    def _normalize(text):
        return " ".join(text.strip().lower().split())

    def _ensure_collection(self):
        from pymilvus import DataType
        if self._client.has_collection(self.collection):
            validate_vector_schema(self._client, self.collection)
            return
        schema = self._client.create_schema()
        schema.add_field("id", DataType.VARCHAR, max_length=64, is_primary=True)
        for name, size in [("question", 2048), ("question_norm", 2048), ("answer", 8192), ("source", 1024), ("scope", 16), ("user_id", 128)]:
            schema.add_field(name, DataType.VARCHAR, max_length=size)
        schema.add_field("enabled", DataType.INT8)
        schema.add_field("is_general", DataType.INT8)
        for name in ("created_at", "hard_expires_at", "sliding_ttl"):
            schema.add_field(name, DataType.INT64)
        schema.add_field("expires_at", DataType.INT64)
        schema.add_field("embedding", DataType.FLOAT_VECTOR, dim=self.settings.embedding_dimensions)
        indexes = self._client.prepare_index_params()
        indexes.add_index(field_name="embedding", index_type="AUTOINDEX", metric_type="COSINE")
        self._client.create_collection(collection_name=self.collection, schema=schema, index_params=indexes)


semantic_cache = SemanticCache()
