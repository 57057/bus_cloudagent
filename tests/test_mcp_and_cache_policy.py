import asyncio
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from agent.core.mcp.mcp_manager import MCPManager
from agent.core.mcp.interceptors import UserIdInjector
from app.infra.cache import SemanticCache
from app.service import chat_service


class MCPDiscovery(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / "mcp.json"
        self.path.write_text(json.dumps({"mcpServers": {"test": {
            "transport": "stdio", "command": "{python}", "cwd": "{project_root}", "args": [],
        }}}), encoding="utf-8")

    async def test_concurrent_discovery_once_and_allowlists(self):
        client = SimpleNamespace(get_tools=AsyncMock(return_value=[
            SimpleNamespace(name="query_user_orders"), SimpleNamespace(name="generate_ai_poster")]))
        with patch("agent.core.mcp.mcp_manager.MultiServerMCPClient", return_value=client) as factory:
            manager = MCPManager(self.path)
            results = await asyncio.gather(*[
                manager.get_tools({"query_user_orders"}) for _ in range(3)])
            client.get_tools.assert_awaited_once()
            self.assertEqual([tool.name for tool in results[0]], ["query_user_orders"])
            self.assertEqual(factory.call_args.kwargs["connections"]["test"]["command"], sys.executable)
            with self.assertRaises(RuntimeError):
                await manager.get_tools({"missing"})
            await manager.close()
            await manager.get_tools()
            self.assertEqual(client.get_tools.await_count, 2)

    async def test_failed_discovery_can_retry(self):
        client = SimpleNamespace(get_tools=AsyncMock(side_effect=[RuntimeError("offline"), []]))
        with patch("agent.core.mcp.mcp_manager.MultiServerMCPClient", return_value=client):
            manager = MCPManager(self.path)
            with self.assertRaises(RuntimeError):
                await manager.connect()
            await manager.connect()
            self.assertEqual(client.get_tools.await_count, 2)

    async def test_identity_missing_is_rejected_and_unprotected_tool_unchanged(self):
        handler = AsyncMock()
        request = SimpleNamespace(name="query_user_orders", runtime=SimpleNamespace(config={}))
        with self.assertRaises(PermissionError):
            await UserIdInjector()(request, handler)
        handler.assert_not_awaited()
        request.name = "generate_ai_poster"
        await UserIdInjector()(request, handler)
        handler.assert_awaited_once_with(request)

    async def test_runtime_identity_overrides_model_identity(self):
        request = SimpleNamespace(name="query_user_orders", args={"user_id": "other", "limit": 5},
            runtime=SimpleNamespace(config={"configurable": {"user_id": "owner", "user_context": "signed-context"}}),
            override=MagicMock(return_value="overridden"))
        handler = AsyncMock()
        await UserIdInjector()(request, handler)
        request.override.assert_called_once_with(args={"user_id": "owner", "limit": 5, "user_context": "signed-context"})
        handler.assert_awaited_once_with("overridden")


class ReviewedCache(unittest.IsolatedAsyncioTestCase):
    async def test_chat_uses_layered_cache_before_loading_context(self):
        cache = SimpleNamespace(get_cache=AsyncMock(return_value={
            "answer": "cached", "level": "L1_EXACT", "distance": 0.0}))
        graph = SimpleNamespace(ainvoke=AsyncMock())
        context = AsyncMock()
        with patch.object(chat_service, "semantic_cache", cache), \
                patch.object(chat_service, "graph", graph), \
                patch.object(chat_service, "memory", None), \
                patch.object(chat_service, "_extract_memory_context", context):
            chunks = [part async for part in chat_service.stream_chat("q", "u", "s")]
        cache.get_cache.assert_awaited_once_with("q", "u")
        context.assert_not_awaited()
        graph.ainvoke.assert_not_awaited()
        self.assertIn('"done": true', chunks[-1])

    async def test_unreviewed_public_or_missing_source_cannot_be_written(self):
        cache = SemanticCache()
        for kwargs in ({}, {"is_general": True}, {"is_general": True, "request_count": 3}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                await cache.set_cache("q", "answer", **kwargs)

    async def test_reviewed_answer_has_source_and_expiry(self):
        cache = SemanticCache()
        cache._available = True
        cache._embeddings = SimpleNamespace(aembed_query=AsyncMock(return_value=[0.1]))
        cache._client = MagicMock()
        with patch("app.infra.cache.time.time", return_value=100):
            await cache.set_cache("q", "answer", is_general=True, request_count=3, source="guide.md", ttl_seconds=10)
        row = cache._client.upsert.call_args.kwargs["data"][0]
        self.assertEqual(row["expires_at"], 110)
        self.assertEqual(row["source"], "guide.md")
        self.assertTrue(cache.collection.startswith("qa_hot_v2_"))

    async def test_exact_only_lookup_filters_expiry_without_embedding(self):
        cache = SemanticCache()
        cache._available = True
        cache._client = MagicMock()
        cache._client.query.return_value = []
        cache._embeddings = SimpleNamespace(aembed_query=AsyncMock())
        with patch("app.infra.cache.time.time", return_value=100):
            self.assertIsNone(await cache.get_cache("q", allow_semantic=False))
        self.assertIn("expires_at > 100", cache._client.query.call_args.kwargs["filter"])
        cache._embeddings.aembed_query.assert_not_awaited()
