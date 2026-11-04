"""CLI regression tests using fake graph/memory; no network or model calls."""
import importlib.util
import io
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
spec = importlib.util.spec_from_file_location("cloudagent_cli", ROOT / "agent/main.py")
cli = importlib.util.module_from_spec(spec)
with patch("sys.stdout", io.StringIO()), patch("sys.stdin", io.StringIO()):
    spec.loader.exec_module(cli)


class CliLifecycle(unittest.IsolatedAsyncioTestCase):
    async def test_five_turns_extract_then_finalize_with_identity(self):
        async def invoke(state, **kwargs):
            return {"messages": list(state["messages"]) + [SimpleNamespace(content="answer")]}

        graph = SimpleNamespace(ainvoke=AsyncMock(side_effect=invoke))
        model = object()
        manager = SimpleNamespace(
            build_graph=lambda: graph,
            orchestrator=SimpleNamespace(llm=model),
        )
        memory = SimpleNamespace(
            short_term=SimpleNamespace(available=True),
            long_term=SimpleNamespace(available=True),
            get_recent_messages=AsyncMock(return_value=[]),
            load_preferences=AsyncMock(return_value=[]),
            save_conversation=AsyncMock(),
            background_extract=AsyncMock(),
            finalize_session=AsyncMock(),
        )
        with patch("builtins.input", side_effect=["question"] * 5 + ["quit"]), \
                patch("sys.stdout", io.StringIO()):
            await cli.run_interactive_mode(manager, "user_test", "session_test", memory)
        self.assertEqual(graph.ainvoke.await_count, 5)
        for call in graph.ainvoke.await_args_list:
            self.assertEqual(call.kwargs["config"]["configurable"]["user_id"], "user_test")
        self.assertEqual(memory.save_conversation.await_count, 5)
        memory.background_extract.assert_awaited_once_with(
            "user_test", "session_test", model,
            messages=[{"role": "user", "content": "question"}] * 5)
        memory.finalize_session.assert_awaited_once_with("user_test", "session_test", model, messages=[])

    async def test_graph_failure_still_finalizes_session(self):
        model = object()
        graph = SimpleNamespace(ainvoke=AsyncMock(side_effect=RuntimeError("test failure")))
        manager = SimpleNamespace(build_graph=lambda: graph,
                                  orchestrator=SimpleNamespace(llm=model))
        memory = SimpleNamespace(
            short_term=SimpleNamespace(available=False),
            long_term=SimpleNamespace(available=False),
            finalize_session=AsyncMock(),
        )
        with patch("builtins.input", return_value="question"), \
                patch("sys.stdout", io.StringIO()), patch.object(cli.logging, "exception"):
            await cli.run_interactive_mode(manager, "u", "s", memory)
        memory.finalize_session.assert_awaited_once_with("u", "s", model, messages=[])


if __name__ == "__main__":
    unittest.main()
