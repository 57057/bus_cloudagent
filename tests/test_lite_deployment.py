"""Lightweight mode preserves publication checks and never connects disabled stores."""
import asyncio
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from agent.tools import published_knowledge


class LiteDeploymentTests(unittest.TestCase):
    def test_configured_llm_with_keywords_does_not_load_milvus(self):
        doc = {'id':'current','title':'[RUNBOOK spring-react-single-host@1] demo','version':1,
               'valid_until':'2030-01-01','content':'Deployment task health and rollback evidence.'}
        with patch.object(published_knowledge,'snapshot',return_value={'revision':1,'documents':[doc]}), \
             patch('agent.config.get_settings',return_value=SimpleNamespace(api_key_configured=True,document_search_backend='keywords')), \
             patch.dict('sys.modules',{'pymilvus':None}):
            result = published_knowledge.retrieve('deployment')
            self.assertIn('(current)', result)

    def test_retirement_is_still_checked_in_lite_mode(self):
        doc = {'id':'retired','title':'demo','version':1,'valid_until':'2030-01-01','content':'deployment'}
        with patch.object(published_knowledge,'snapshot',side_effect=[{'revision':1,'documents':[doc]},{'revision':2,'documents':[]}]), \
             patch('agent.config.get_settings',return_value=SimpleNamespace(api_key_configured=True,document_search_backend='keywords')):
            self.assertNotIn('(retired)', published_knowledge.retrieve('deployment'))

    def test_disabled_long_term_memory_does_not_connect(self):
        from agent.core.memory.long_term import LongTermMemory
        memory = LongTermMemory()
        with patch('agent.core.memory.long_term.get_settings',return_value=SimpleNamespace(vector_backend_enabled=False)), \
             patch.dict('sys.modules',{'pymilvus':None}):
            asyncio.run(memory.initialize())
        self.assertFalse(memory.database_available)
        self.assertIsNone(memory._client)

    def test_disabled_cache_does_not_connect(self):
        from app.infra.cache import SemanticCache
        cache = SemanticCache()
        cache.settings = SimpleNamespace(vector_backend_enabled=False)
        with patch.dict('sys.modules',{'pymilvus':None}):
            asyncio.run(cache.initialize())
        self.assertFalse(cache.available)
        self.assertIsNone(cache._client)


if __name__ == '__main__':
    unittest.main()
