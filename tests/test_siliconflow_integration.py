import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from agent.config import get_settings
from agent.config.providers import create_chat_model, create_embeddings
from app.infra.cache import SemanticCache

class SiliconFlowRequests(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {"SILICONFLOW_API_KEY": "test-key", "BASE_URL": "https://api.siliconflow.cn/v1",
                                                   "MODEL": "deepseek-ai/DeepSeek-V3.2", "EMBEDDING_MODEL": "Qwen/Qwen3-Embedding-4B",
                                                   "EMBEDDING_BASE_URL": "https://api.siliconflow.cn/v1", "EMBEDDING_DIMENSIONS": "1536"})
        self.environment.start()
        get_settings.cache_clear()

    def tearDown(self):
        self.environment.stop()
        get_settings.cache_clear()

    def test_chat_uses_siliconflow_model_and_authorization(self):
        def respond(request):
            self.assertEqual(str(request.url), "https://api.siliconflow.cn/v1/chat/completions")
            self.assertEqual(request.headers["authorization"], "Bearer test-key")
            self.assertEqual(json.loads(request.content)["model"], "deepseek-ai/DeepSeek-V3.2")
            return httpx.Response(200, json={"id": "test", "object": "chat.completion", "created": 0,
                                            "model": "deepseek-ai/DeepSeek-V3.2", "choices": [{"index": 0,
                                            "finish_reason": "stop", "message": {"role": "assistant", "content": "ok"}}]})
        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            model = create_chat_model(http_client=client)
            self.assertEqual(model.invoke("hello").content, "ok")

    def test_qwen_embeddings_send_plain_text_and_dimensions(self):
        def respond(request):
            self.assertEqual(str(request.url), "https://api.siliconflow.cn/v1/embeddings")
            body = json.loads(request.content)
            self.assertEqual(body["model"], "Qwen/Qwen3-Embedding-4B")
            self.assertEqual(body["dimensions"], 1536)
            self.assertEqual(body["encoding_format"], "float")
            self.assertEqual(body["input"], ["VPC source document"])
            return httpx.Response(200, json={"object": "list", "model": body["model"], "data": [
                {"object": "embedding", "index": 0, "embedding": [0.1] * 1536}], "usage": {"prompt_tokens": 3, "total_tokens": 3}})
        with httpx.Client(transport=httpx.MockTransport(respond)) as client:
            embeddings = create_embeddings(http_client=client)
            self.assertEqual(len(embeddings.embed_query("VPC source document")), 1536)

class SemanticCacheThreshold(unittest.IsolatedAsyncioTestCase):
    async def test_cosine_similarity_accepts_close_and_rejects_distant_questions(self):
        class Embeddings:
            async def aembed_query(self, query):
                return [0.1] * 1536
        class Client:
            score = 0.95
            def query(self, **kwargs):
                return []
            def search(self, **kwargs):
                return [[{"distance": self.score, "entity": {"question": "VPC", "answer": "source answer"}}]]
        cache = SemanticCache()
        cache._available = True
        cache._embeddings = Embeddings()
        cache._client = Client()
        result = await cache.get_cache("VPC details", "user_1001")
        self.assertEqual(result["answer"], "source answer")
        self.assertAlmostEqual(result["distance"], 0.05)
        cache._client.score = 0.7
        self.assertIsNone(await cache.get_cache("unrelated", "user_1001"))

if __name__ == "__main__":
    unittest.main()
