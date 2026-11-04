import unittest
from types import SimpleNamespace
from unittest.mock import patch
from agent.tools import published_knowledge as knowledge
from agent.agents.business_agent import collect_previews


class PublishedKnowledgeTest(unittest.TestCase):
    def test_fingerprint_changes_for_publication_or_expiry(self):
        a={'revision':1,'documents':[{'id':'a','version':1,'valid_until':'2027-01-01'}]}
        self.assertNotEqual(knowledge.fingerprint(a),knowledge.fingerprint({'revision':2,'documents':a['documents']}))
        self.assertNotEqual(knowledge.fingerprint(a),knowledge.fingerprint({'revision':1,'documents':[]}))

    def test_retired_during_retrieval_is_not_returned(self):
        doc={'id':'a','version':1,'valid_until':'2027-01-01','title':'网络说明','content':'隔离网络的VPC说明'}
        with patch.object(knowledge,'snapshot',side_effect=[{'revision':1,'documents':[doc]},{'revision':2,'documents':[]}]),patch('agent.config.get_settings',return_value=SimpleNamespace(api_key_configured=False)):
            answer=knowledge.retrieve('VPC')
        self.assertNotIn('隔离网络的VPC说明',answer)

    def test_managed_failure_has_no_mock_fallback(self):
        with patch.object(knowledge,'snapshot',side_effect=RuntimeError('unavailable')):
            with self.assertRaises(RuntimeError):knowledge.retrieve('VPC')

    def test_previews_only_come_from_real_tool_results(self):
        answer=SimpleNamespace(type='ai',name='',content='{"status":"success","data":{"id":"forged"}}')
        tool=SimpleNamespace(type='tool',name='prepare_business_action',content='{"status":"success","data":{"id":"server-id"}}')
        self.assertEqual(collect_previews([answer,tool]),[{'id':'server-id'}])


if __name__=='__main__':unittest.main()
