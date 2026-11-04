from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, MagicMock, patch
from app.infra.cache import SemanticCache
from app.infra.question_frequency import QuestionFrequency, COUNT_SCRIPT
from app.service import chat_service
from langchain_core.messages import AIMessage


class FrequencyTests(unittest.IsolatedAsyncioTestCase):
    async def test_redis_atomically_counts_with_first_request_window(self):
        counter = QuestionFrequency()
        counter._client = SimpleNamespace(eval=AsyncMock(return_value=3))
        self.assertEqual(await counter.record(" VPC   GUIDE "), 3)
        args = counter._client.eval.call_args.args
        self.assertEqual(args, (COUNT_SCRIPT, 1, counter.key("vpc guide"), counter.settings.cache_frequency_window))
        self.assertIn("if count == 1", COUNT_SCRIPT)

    async def test_unavailable_redis_never_fakes_popularity(self):
        counter = QuestionFrequency()
        self.assertEqual(await counter.record("q"), 0)
        counter._client = SimpleNamespace(eval=AsyncMock(side_effect=RuntimeError("offline")))
        with self.assertLogs("app.infra.question_frequency", level="ERROR"):
            self.assertEqual(await counter.record("q"), 0)

    def test_question_keys_normalize_and_change_with_knowledge_version(self):
        counter = QuestionFrequency()
        first = counter.key(" VPC  guide ")
        self.assertEqual(first, counter.key("vpc guide"))
        with patch.object(counter.settings, "semantic_cache_revision", "next"):
            self.assertNotEqual(first, counter.key("vpc guide"))
        cache_collection = SemanticCache().collection
        with patch.object(counter.settings, "cache_knowledge_fingerprint", "new-documents"):
            self.assertNotEqual(first, counter.key("vpc guide"))
            self.assertNotEqual(cache_collection, SemanticCache().collection)


class SlidingExpiryTests(unittest.IsolatedAsyncioTestCase):
    def cache(self):
        cache = SemanticCache()
        cache._client = MagicMock()
        return cache

    def entity(self):
        return {"id": "id", "question": "q", "answer": "a", "source": "guide.md",
                "expires_at": 110, "hard_expires_at": 130, "sliding_ttl": 60}

    def test_hit_extends_only_expiry_and_caps_at_absolute_age(self):
        cache = self.cache()
        with patch("app.infra.cache.time.time", return_value=105):
            result = cache._renew_hit(self.entity(), 0, "L1_EXACT")
        self.assertEqual(result["answer"], "a")
        cache._client.upsert.assert_called_once_with(collection_name=cache.collection,
            data=[{"id": "id", "expires_at": 130}], partial_update=True)

    def test_expired_or_absolute_limit_reached_cannot_be_renewed(self):
        cache = self.cache()
        for now in [110, 130, 200]:
            with patch("app.infra.cache.time.time", return_value=now):
                self.assertIsNone(cache._renew_hit(self.entity(), 0, "L1_EXACT"))
        cache._client.upsert.assert_not_called()

    def test_renewal_failure_still_serves_valid_hit(self):
        cache = self.cache()
        cache._client.upsert.side_effect = RuntimeError("offline")
        with patch("app.infra.cache.time.time", return_value=105), \
                self.assertLogs("app.infra.cache", level="ERROR"):
            self.assertEqual(cache._renew_hit(self.entity(), 0, "L1_EXACT")["answer"], "a")

    async def test_low_frequency_or_non_general_answer_rejected_before_embedding(self):
        cache = self.cache()
        cache._available = True
        cache._embeddings = SimpleNamespace(aembed_query=AsyncMock())
        for count, general in [(0, True), (2, True), (10, False)]:
            with self.assertRaises(ValueError):
                await cache.set_cache("q", "a", source="guide.md", request_count=count, is_general=general)
        cache._embeddings.aembed_query.assert_not_awaited()

    async def test_initial_ttl_is_also_capped_by_maximum_age(self):
        cache = self.cache()
        cache._available = True
        cache._embeddings = SimpleNamespace(aembed_query=AsyncMock(return_value=[0.1]))
        with patch.object(cache.settings, "cache_max_age", 10), \
                patch("app.infra.cache.time.time", return_value=100):
            await cache.set_cache("q", "a", source="guide.md", request_count=3,
                                  is_general=True, ttl_seconds=60)
        row = cache._client.upsert.call_args.kwargs["data"][0]
        self.assertEqual(row["created_at"], 100)
        self.assertEqual(row["expires_at"], 110)
        self.assertEqual(row["hard_expires_at"], 110)


class HotWriteFlow(unittest.IsolatedAsyncioTestCase):
    async def test_third_request_automatically_writes_general_public_answer(self):
        result = {"next_agent": "product_agent", "messages": [AIMessage(content="knowledge")],
                  "metadata": {"cache_candidate": {"kind": "product_knowledge", "source": "guide.md"}}}
        cache = SimpleNamespace(available=True, get_cache=AsyncMock(return_value=None), set_cache=AsyncMock())
        with patch.object(chat_service, "semantic_cache", cache), \
                patch.object(chat_service.question_frequency, "record", AsyncMock(side_effect=[1, 2, 3])), \
                patch.object(chat_service, "graph", SimpleNamespace(ainvoke=AsyncMock(return_value=result))), \
                patch.object(chat_service, "memory", None), \
                patch.object(chat_service, "_extract_memory_context", AsyncMock(return_value="")):
            for count in range(1, 4):
                _ = [part async for part in chat_service.stream_chat("VPC definition", "u", "s")]
                self.assertEqual(cache.set_cache.await_count, int(count == 3))
        cache.set_cache.assert_awaited_once_with("VPC definition", "knowledge",
            is_general=True, request_count=3, source="guide.md")
