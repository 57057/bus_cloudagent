import unittest

from agent.core.memory.preference_buffer import PreferenceBuffer, explicitly_requests_memory


def turn(text):
    return [{"role": "user", "content": text}, {"role": "assistant", "content": "answer"}]


class PreferenceBufferTests(unittest.TestCase):
    def test_only_fifth_turn_triggers_and_next_batch_contains_only_new_turns(self):
        buffer = PreferenceBuffer()
        for number in range(4):
            self.assertEqual(buffer.add("u", "s", turn(str(number))), [])
        batch = buffer.add("u", "s", turn("4"))
        self.assertEqual([m["content"] for m in batch], ["0", "1", "2", "3", "4"])
        self.assertEqual(buffer.add("u", "s", turn("new")), [])
        self.assertEqual(buffer.pop("u", "s"), [{"role": "user", "content": "new"}])

    def test_explicit_request_flushes_incremental_batch_immediately(self):
        buffer = PreferenceBuffer()
        buffer.add("u", "s", turn("普通问题"))
        batch = buffer.add("u", "s", turn("以后默认用中文"))
        self.assertEqual(len(batch), 2)
        self.assertEqual(buffer.pop("u", "s"), [])

    def test_temporary_or_negative_request_does_not_trigger_immediately(self):
        for text in ["这次解释详细一点", "这个项目用 MySQL", "不要记住这个", "以后再说"]:
            with self.subTest(text=text):
                self.assertFalse(explicitly_requests_memory(text))
                self.assertEqual(PreferenceBuffer().add("u", "s", turn(text)), [])

    def test_users_and_sessions_do_not_share_pending_context(self):
        buffer = PreferenceBuffer()
        buffer.add("u1", "s1", turn("a"))
        buffer.add("u1", "s2", turn("b"))
        buffer.add("u2", "s1", turn("c"))
        batch = buffer.add("u1", "s1", turn("请记住我喜欢简洁"))
        self.assertEqual([m["content"] for m in batch], ["a", "请记住我喜欢简洁"])
        self.assertEqual(len(buffer.flush()), 2)
        self.assertEqual(buffer.flush(), [])

    def test_assistant_messages_cannot_trigger_extraction(self):
        buffer = PreferenceBuffer()
        self.assertEqual(buffer.add("u", "s", [{"role": "assistant", "content": "请记住我"}]), [])
        self.assertEqual(buffer.flush(), [])
