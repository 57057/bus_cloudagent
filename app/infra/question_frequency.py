"""Atomic Redis frequency windows; no frequency proof means no automatic write."""
import hashlib
import logging
from agent.config import get_settings

logger = logging.getLogger(__name__)
COUNT_SCRIPT = """
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return count
"""


class QuestionFrequency:
    def __init__(self):
        self._client = None
        self.settings = get_settings()

    async def initialize(self):
        try:
            import redis.asyncio as redis
            self._client = redis.from_url(self.settings.redis_url, decode_responses=True, protocol=2,
                                         socket_connect_timeout=2, socket_timeout=2)
            await self._client.ping()
        except Exception:
            logger.warning("Question frequency unavailable; automatic cache writes disabled")
            await self.close()

    def key(self, query):
        normalized = " ".join(query.strip().lower().split())
        identity = hashlib.sha256((self.settings.semantic_cache_revision + "|" +
            self.settings.cache_knowledge_fingerprint + "|" +
            self.settings.embedding_collection_suffix + "|" + normalized).encode()).hexdigest()
        return "cache:frequency:" + identity

    async def record(self, query):
        if self._client is None:
            return 0
        try:
            return int(await self._client.eval(COUNT_SCRIPT, 1, self.key(query),
                                               self.settings.cache_frequency_window))
        except Exception:
            logger.exception("Frequency counting failed; skipping automatic cache write")
            return 0

    async def close(self):
        if self._client is not None:
            await self._client.aclose()
            self._client = None


question_frequency = QuestionFrequency()
