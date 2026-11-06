import unittest
from unittest.mock import patch
from types import SimpleNamespace
from agent.tools import published_knowledge

class ApplicationRunbookTests(unittest.TestCase):
    def document(self,id,title):return {'id':id,'title':title,'version':1,'valid_until':'2030-01-01','content':'部署失败后检查任务和运行证据。'}
    def test_template_version_excludes_product_and_other_template_docs(self):
        wanted=self.document('a','[RUNBOOK spring-react-single-host@1] 部署手册')
        documents=[wanted,self.document('b','云产品部署'),self.document('c','[RUNBOOK spring-react-single-host@2] 部署手册')]
        with patch.object(published_knowledge,'snapshot',return_value={'revision':1,'documents':documents}),patch('agent.config.get_settings',return_value=SimpleNamespace(api_key_configured=False)):
            result=published_knowledge.retrieve('部署',title_prefix='[RUNBOOK spring-react-single-host@1]')
            self.assertIn('(a)',result);self.assertNotIn('(b)',result);self.assertNotIn('(c)',result)
    def test_concurrently_retired_runbook_is_not_returned(self):
        doc=self.document('a','[RUNBOOK spring-react-single-host@1] 部署手册')
        with patch.object(published_knowledge,'snapshot',side_effect=[{'revision':1,'documents':[doc]},{'revision':2,'documents':[]}]),patch('agent.config.get_settings',return_value=SimpleNamespace(api_key_configured=False)):
            result=published_knowledge.retrieve('部署',title_prefix='[RUNBOOK spring-react-single-host@1]')
            self.assertNotIn('(a)',result)

if __name__=='__main__':unittest.main()
