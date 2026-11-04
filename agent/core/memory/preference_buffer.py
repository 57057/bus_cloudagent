"""Incremental, session-scoped extraction batches; stored preferences remain user-scoped."""
import re


def explicitly_requests_memory(text: str) -> bool:
    if re.search(r"(?:不要|别|不用)记住", text):
        return False
    return bool(re.search(
        r"请记住|记住(?:我|[：:，,])|(?:以后|今后)(?:都|默认|请|用|回答)|"
        r"\bremember (?:that|my|i)\b|\bfrom now on\b", text, re.I,
    ))


class PreferenceBuffer:
    """Keep only unprocessed user turns; no assistant text or old-history replay."""
    def __init__(self, interval: int = 5):
        self.interval = interval
        self._pending = {}

    def add(self, user_id, session_id, messages):
        users = [dict(m) for m in messages if m.get("role") == "user"]
        if not users:
            return []
        key = (user_id, session_id)
        pending = self._pending.setdefault(key, [])
        pending.extend(users)
        if len(pending) >= self.interval or any(explicitly_requests_memory(m["content"]) for m in users):
            return self.pop(user_id, session_id)
        return []

    def pop(self, user_id, session_id):
        return self._pending.pop((user_id, session_id), [])

    def flush(self):
        batches = list(self._pending.items())
        self._pending.clear()
        return batches
