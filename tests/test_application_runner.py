import importlib.util
import json
import io
import threading
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

if sys.platform=='win32':sys.modules.setdefault('fcntl',types.SimpleNamespace())
spec=importlib.util.spec_from_file_location('application_runner',Path(__file__).resolve().parents[1]/'deployment-runner/runner.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class RunnerTests(unittest.TestCase):
    def test_cancelled_candidate_keeps_previous_release_and_cleans_its_own_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({'backend_digest':'b','frontend_digest':'f'},directory)
            task={'id':'d'*32,'attempt':1,'allocation_id':'a'*32,'project_id':'b'*32,'kind':'DEPLOY',
                  'payload':{'template':'spring-react-single-host@1','backend_image':'b','frontend_image':'f'}}
            runner.db.execute('INSERT INTO releases VALUES(?,?,?,?,?,?)',('b'*32,'c'*32,'a'*32,'old-backend','old-frontend',18080));runner.db.commit()
            runner.checkpoint=Mock(side_effect=module.DeploymentCancelled)
            runner.docker=Mock(return_value='');runner.remove_candidates=Mock()
            output=runner.execute(task)
            self.assertTrue(output['cancelled']);self.assertTrue(output['candidate_cleanup_confirmed'])
            runner.remove_candidates.assert_called_once_with(task)
            self.assertEqual(runner.db.execute('SELECT backend,frontend FROM releases').fetchone(),('old-backend','old-frontend'))
            self.assertFalse(any(call.args[0]=='stop' for call in runner.docker.call_args_list));runner.db.close()

    def test_cleanup_failure_blocks_further_work_instead_of_claiming_cancellation(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({'backend_digest':'b','frontend_digest':'f'},directory)
            runner.checkpoint=Mock(side_effect=module.DeploymentCancelled)
            runner.remove_candidates=Mock(side_effect=RuntimeError('unavailable'))
            result=runner.execute({'id':'d'*32,'attempt':1,'allocation_id':'a'*32,'project_id':'b'*32,'kind':'DEPLOY',
                'payload':{'template':'spring-react-single-host@1','backend_image':'b','frontend_image':'f'}})
            self.assertTrue(result['manual_required']);self.assertNotIn('cancelled',result);runner.db.close()

    def test_candidate_cleanup_requires_both_current_allocation_and_deployment(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({},directory);runner.docker=Mock(return_value='')
            runner.remove_candidates({'id':'d'*32,'allocation_id':'a'*32})
            for call in runner.docker.call_args_list:
                self.assertIn('label=cloudagent.allocation='+'a'*32,call.args)
                self.assertIn('label=cloudagent.deployment='+'d'*32,call.args)
            runner.db.close()

    def test_stop_start_preserves_release_journal(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({},directory)
            runner.db.execute('INSERT INTO releases VALUES(?,?,?,?,?,?)',('b'*32,'c'*32,'a'*32,'backend','frontend',18080));runner.db.commit()
            runner.docker=Mock(return_value='');runner.wait_health=Mock()
            task={'allocation_id':'a'*32,'project_id':'b'*32,'kind':'STOP','payload':{'template':'spring-react-single-host@1'}}
            self.assertFalse(runner.execute(task)['health']);task['kind']='START';self.assertTrue(runner.execute(task)['health'])
            self.assertEqual(runner.db.execute('SELECT COUNT(*) FROM releases').fetchone()[0],1)
            runner.wait_health.assert_called_once_with('backend','frontend');runner.db.close()

    def test_request_reuses_http_clients_without_sharing_them_between_threads(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({'base_url':'http://127.0.0.1:8080'},directory)
            opener=Mock()
            opener.open.side_effect=lambda *a,**k: io.BytesIO(b'{"data":{"ok":true}}')
            with patch.object(module.urllib.request,'build_opener',return_value=opener) as builder:
                for _ in range(100):self.assertTrue(runner.request('heartbeat',{})['ok'])
                self.assertEqual(builder.call_count,1)
                worker=threading.Thread(target=lambda:runner.request('heartbeat',{}))
                worker.start();worker.join()
                self.assertEqual(builder.call_count,2)
            runner.db.close()

    def test_duplicate_claim_replays_persisted_receipt_without_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({},directory)
            task={'id':'a'*32,'attempt':1}
            runner.execute=Mock(return_value={'health':True})
            def request(path,body):return {'task':task} if path=='tasks/claim' else {}
            runner.request=Mock(side_effect=request)
            runner.tick();runner.tick()
            self.assertEqual(runner.execute.call_count,1)
            runner.db.close()

    def test_changed_attempt_is_not_blindly_reexecuted(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({},directory)
            runner.db.execute('INSERT INTO receipts VALUES(?,?,?,?)',('a'*32,1,'DONE','{}'));runner.db.commit()
            runner.request=Mock(return_value={'task':{'id':'a'*32,'attempt':2}})
            runner.execute=Mock()
            with self.assertRaises(RuntimeError):runner.tick()
            runner.execute.assert_not_called();runner.db.close()

    def test_cleanup_only_targets_explicit_allocation_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            runner=module.Runner({},directory)
            runner.docker=Mock(return_value='')
            runner.execute({'allocation_id':'a'*32,'kind':'CLEANUP','payload':{}})
            command=runner.docker.call_args_list[0].args
            self.assertIn('label=cloudagent.managed=true',command)
            self.assertIn('label=cloudagent.allocation='+'a'*32,command)
            self.assertNotIn('prune',str(runner.docker.call_args_list));runner.db.close()

    def test_rejects_shell_like_identifiers(self):
        for value in ('../../etc','a;rm -rf /','$(id)','', 'not-an-allocation'):
            with self.assertRaises(ValueError):module.Runner.identifier(value)

    def test_logs_remove_known_credentials_and_truncate(self):
        raw='x'*9000+' password=visible-token https://user:pass@example.test/path bearer-value'
        result=module.Runner.redact_logs(raw,['API_TOKEN=bearer-value'])
        self.assertLessEqual(len(result),8000)
        for value in ('visible-token','user:pass','bearer-value'):self.assertNotIn(value,result)

if __name__=='__main__':unittest.main()
