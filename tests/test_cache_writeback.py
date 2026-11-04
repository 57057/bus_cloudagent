import io
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, MagicMock, patch
from langchain_core.messages import AIMessage, ToolMessage
from app.infra.cache import SemanticCache
from app.service import chat_service
from agent.agents.product_agent import ProductAgentNode


def generated():
    return {"next_agent": "product_agent", "messages": [AIMessage(content="VPC explanation")],
            "metadata": {"cache_candidate": {"kind": "product_knowledge", "source": "guide.md"}}}


class LayeredReads(unittest.IsolatedAsyncioTestCase):
    def cache(self):
        cache = SemanticCache()
        cache._available = True
        cache._client = MagicMock()
        cache._embeddings = SimpleNamespace(aembed_query=AsyncMock(return_value=[0.1]))
        return cache

    async def test_user_exact_has_priority_over_public(self):
        cache = self.cache()
        cache._client.query.return_value = [{"question": "q", "answer": "private", "scope": "user"}]
        hit = await cache.get_cache("q", "owner")
        self.assertEqual(hit["answer"], "private")
        self.assertEqual(cache._client.query.call_count, 1)
        self.assertIn('user_id == "owner"', cache._client.query.call_args.kwargs["filter"])
        cache._embeddings.aembed_query.assert_not_awaited()

    async def test_public_exact_follows_user_miss(self):
        cache = self.cache()
        cache._client.query.side_effect = [[], [{"question": "q", "answer": "public"}]]
        self.assertEqual((await cache.get_cache("q", "owner"))["answer"], "public")
        self.assertIn('scope == "public"', cache._client.query.call_args.kwargs["filter"])
        cache._client.search.assert_not_called()

    async def test_semantic_read_is_scoped_to_public_and_current_user(self):
        cache = self.cache()
        cache._client.query.return_value = []
        cache._client.search.return_value = [[{"distance": 0.95,
            "entity": {"question": "similar", "answer": "answer"}}]]
        self.assertEqual((await cache.get_cache("q", "owner"))["level"], "L1_SEMANTIC")
        expression = cache._client.search.call_args.kwargs["filter"]
        self.assertIn('scope == "public"', expression)
        self.assertIn('scope == "user" and user_id == "owner"', expression)

    async def test_private_write_uses_owner_and_stable_upsert_key(self):
        cache = self.cache()
        await cache.set_cache("q", "a", user_id="owner", is_general=True, request_count=3, source="guide.md")
        row = cache._client.upsert.call_args.kwargs["data"][0]
        self.assertEqual(row["scope"], "user")
        self.assertEqual(row["user_id"], "owner")
        self.assertEqual(row["is_general"], 1)
        key = row["id"]
        await cache.set_cache("q", "new", user_id="owner", is_general=True, request_count=3, source="guide.md")
        self.assertEqual(key, cache._client.upsert.call_args.kwargs["data"][0]["id"])
        await cache.set_cache("q", "new", user_id="other", is_general=True, request_count=3, source="guide.md")
        self.assertNotEqual(key, cache._client.upsert.call_args.kwargs["data"][0]["id"])


class WritebackPolicy(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        counter = patch.object(chat_service.question_frequency, "record", AsyncMock(return_value=3))
        counter.start()
        self.addCleanup(counter.stop)

    async def test_source_backed_product_answer_writes_private_cache(self):
        cache = SimpleNamespace(available=True, get_cache=AsyncMock(return_value=None), set_cache=AsyncMock())
        graph = SimpleNamespace(ainvoke=AsyncMock(return_value=generated()))
        with patch.object(chat_service, "semantic_cache", cache), \
                patch.object(chat_service, "graph", graph), \
                patch.object(chat_service, "memory", None), \
                patch.object(chat_service, "_extract_memory_context", AsyncMock(return_value="")):
            _ = [part async for part in chat_service.stream_chat("VPC definition", "owner", "s")]
        cache.set_cache.assert_awaited_once_with("VPC definition", "VPC explanation",
            is_general=True, request_count=3, source="guide.md")

    async def test_cache_write_failure_does_not_lose_answer(self):
        cache = SimpleNamespace(available=True, get_cache=AsyncMock(return_value=None),
                                set_cache=AsyncMock(side_effect=RuntimeError("offline")))
        with patch.object(chat_service, "semantic_cache", cache), \
                patch.object(chat_service, "graph", SimpleNamespace(ainvoke=AsyncMock(return_value=generated()))), \
                patch.object(chat_service, "memory", None), \
                patch.object(chat_service, "_extract_memory_context", AsyncMock(return_value="")), \
                self.assertLogs(chat_service.logger, level="ERROR"):
            chunks = [part async for part in chat_service.stream_chat("VPC definition", "owner", "s")]
        self.assertIn('"done": true', chunks[-1])

    async def test_product_candidate_requires_actual_tool_source(self):
        node = ProductAgentNode.__new__(ProductAgentNode)
        node.llm, node.tools = object(), []
        for messages, expected in [([AIMessage(content="Answer source: fake.md")], False),
                ([ToolMessage(content="【来源: guide.md】\nVPC facts", tool_call_id="t", name="query_vector_db"),
                  AIMessage(content="answer")], True)]:
            agent = SimpleNamespace(ainvoke=AsyncMock(return_value={"messages": messages}))
            with patch("agent.agents.product_agent.create_react_agent", return_value=agent), \
                    patch("sys.stdout", io.StringIO()):
                result = await node({"messages": [], "metadata": {}})
            self.assertEqual("cache_candidate" in result["metadata"], expected)

    def test_context_dynamic_routes_and_missing_evidence_reject_write(self):
        for query, context, result in [
            ("q", "history", generated()), ("我的订单", "", generated()),
            ("它是什么", "", generated()),
            ("q", "", {**generated(), "next_agent": "billing_agent"}),
            ("q", "", {**generated(), "metadata": {}}),
            ("q", "", {**generated(), "messages": [AIMessage(content="未找到相关信息")]}),
        ]:
            with self.subTest(query=query, context=context):
                self.assertFalse(chat_service.cache_write_allowed(query, context, result))
