import asyncio
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from agent.core.memory.memory_manager import MemoryManager
from agent.core.memory.long_term import LongTermMemory
from agent.core.memory.preference_buffer import PreferenceBuffer
from app.service import chat_service


class PreferenceFlow(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        chat_service._preference_buffer = PreferenceBuffer()

    async def test_normal_turns_do_not_call_extractor_until_fifth(self):
        manager = SimpleNamespace(long_term=SimpleNamespace(available=True), background_extract=AsyncMock())
        with patch.object(chat_service, "memory", manager), \
                patch.object(chat_service, "preference_llm", object()):
            for n in range(4):
                chat_service.schedule_preference_save("u", "s", [{"role": "user", "content": str(n)}])
            await asyncio.sleep(0)
            manager.background_extract.assert_not_awaited()
            chat_service.schedule_preference_save("u", "s", [{"role": "user", "content": "4"}])
            await chat_service.drain_preference_tasks()
        self.assertEqual(len(manager.background_extract.call_args.kwargs["messages"]), 5)
        manager.background_extract.assert_awaited_once()

    async def test_explicit_session_end_flushes_only_its_unprocessed_messages(self):
        manager = SimpleNamespace(long_term=SimpleNamespace(available=True), background_extract=AsyncMock())
        with patch.object(chat_service, "memory", manager), \
                patch.object(chat_service, "preference_llm", object()):
            chat_service.schedule_preference_save("u", "s", [{"role": "user", "content": "first"}])
            chat_service.schedule_preference_save("u", "s", [], force=True)
            chat_service.schedule_preference_save("u", "s", [], force=True)
            await chat_service.drain_preference_tasks()
        manager.background_extract.assert_awaited_once()

    async def test_single_turn_extracts_user_only_and_writes_memory(self):
        manager = MemoryManager()
        manager.long_term = SimpleNamespace(available=True, save_memory=AsyncMock())
        manager.load_preferences = AsyncMock(return_value=[])
        model = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(content="语言：中文")))
        result = await manager.background_extract("u", "s", model, messages=[
            {"role": "user", "content": "以后请用中文"},
            {"role": "assistant", "content": "ASSISTANT_ONLY_SENTINEL"},
        ])
        self.assertEqual(result, ["语言:中文"])
        manager.long_term.save_memory.assert_awaited_once_with(
            user_id="u", content="语言:中文", memory_type="preference")
        self.assertNotIn("ASSISTANT_ONLY_SENTINEL", model.ainvoke.call_args.args[0][0]["content"])

    async def test_existing_exact_memory_is_not_embedded_or_inserted(self):
        memory = LongTermMemory()
        memory._available = True
        memory._client = MagicMock()
        memory._client.query.return_value = [{"content": "语言: 中文"}]
        memory._embeddings = SimpleNamespace(aembed_query=AsyncMock())
        await memory.save_memory("u", "语言: 中文", "preference")
        memory._embeddings.aembed_query.assert_not_awaited()
        memory._client.insert.assert_not_called()

    async def test_new_preference_is_embedded_and_inserted_with_owner(self):
        memory = LongTermMemory()
        memory._available = True
        memory._client = MagicMock()
        memory._client.query.return_value = []
        memory._embeddings = SimpleNamespace(aembed_query=AsyncMock(return_value=[0.1, 0.2]))
        await memory.save_memory("u", "language: Chinese", "preference")
        memory._embeddings.aembed_query.assert_awaited_once_with("language: Chinese")
        row = memory._client.insert.call_args.kwargs["data"][0]
        self.assertEqual(row["user_id"], "u")
        self.assertEqual(row["memory_type"], "preference")
        self.assertEqual(row["embedding"], [0.1, 0.2])

    async def test_same_user_extractions_are_serialized(self):
        events = []

        async def extract(user, session, model, *, messages):
            events.append("start:" + session)
            await asyncio.sleep(0)
            events.append("end:" + session)

        manager = SimpleNamespace(long_term=SimpleNamespace(available=True), background_extract=extract)
        with patch.object(chat_service, "memory", manager), \
                patch.object(chat_service, "preference_llm", object()):
            chat_service.schedule_preference_save("u", "s1", [{"role": "user", "content": "请记住我喜欢中文"}])
            chat_service.schedule_preference_save("u", "s2", [{"role": "user", "content": "以后默认简短回答"}])
            await chat_service.drain_preference_tasks()
        self.assertEqual(events, ["start:s1", "end:s1", "start:s2", "end:s2"])

    async def test_chat_schedules_save_without_redis_and_shutdown_drains(self):
        manager = SimpleNamespace(
            short_term=SimpleNamespace(available=False),
            long_term=SimpleNamespace(available=True), background_extract=AsyncMock(),
            load_preferences=AsyncMock(return_value=[]),
        )
        cache = SimpleNamespace(get_cache=AsyncMock(return_value={
            "answer": "ok", "level": "exact", "distance": 0.0, "matched_question": "q"}))
        with patch.object(chat_service, "memory", manager), \
                patch.object(chat_service, "semantic_cache", cache), \
                patch.object(chat_service, "preference_llm", object()):
            chunks = [chunk async for chunk in chat_service.stream_chat("用中文", "u", "s")]
            await chat_service.drain_preference_tasks()
        manager.background_extract.assert_awaited_once()
        self.assertEqual(manager.background_extract.call_args.kwargs["messages"][0]["content"], "用中文")
        self.assertIn('"done": true', chunks[-1])
        self.assertFalse(chat_service._preference_tasks)


class InstalledImports(unittest.TestCase):
    def test_source_packages_resolve_without_site_packages_or_editable_install(self):
        root = Path(__file__).resolve().parents[1]
        code = (
            f"import sys; sys.path.insert(0, {str(root)!r}); "
            "import agent, agent.core, app; "
            "assert agent.__file__ and app.__file__"
        )
        result = subprocess.run([sys.executable, "-I", "-S", "-c", code],
                                cwd=root.parent, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_imports_from_unrelated_working_directory(self):
        root = Path(__file__).resolve().parents[1]
        modules = ["agent.agents.product_agent", "agent.agents.billing_agent", "agent.core.workflow.graph_manager",
                   "agent.core.graph.parser", "agent.core.memory.memory_manager", "app.service.chat_service"]
        for module in modules:
            with self.subTest(module=module):
                result = subprocess.run([sys.executable, "-c", f"import {module}"],
                                        cwd=root.parent, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_direct_script_imports_without_executing_demos(self):
        root = Path(__file__).resolve().parents[1]
        for path in ["agent/agents/product_agent.py", "agent/agents/billing_agent.py",
                     "agent/core/graph/parser.py", "agent/core/workflow/graph_manager.py"]:
            with self.subTest(path=path):
                code = f"import runpy; runpy.run_path({str(root / path)!r}, run_name='import_check')"
                result = subprocess.run([sys.executable, "-c", code], cwd=root.parent,
                                        capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
