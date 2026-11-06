"""Security boundaries of the cloud preparation package; no cloud writes."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cloud_bundle', ROOT / 'deploy/build_cloud_bundle.py')
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class CloudBundleTests(unittest.TestCase):
    def test_source_allowlist_excludes_secrets_and_test_trees(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('app/router.py', 'agent/config/settings.py', 'agent/config/mcp_servers.json',
                         'agent/.env', 'agent/.env.before-local-deploy', 'agent/tests/credential_fixture.py',
                         'agent/test/manual.py', 'runtime/application-secret.key', 'mock_data/guide.md', 'mock_data/password.env'):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture')
            actual = {p.relative_to(root).as_posix() for p in bundle.source_files(root)}
            self.assertEqual(actual, {'app/router.py', 'agent/config/settings.py', 'agent/config/mcp_servers.json', 'mock_data/guide.md'})

    def test_only_gateway_publishes_ports_and_data_network_is_private(self):
        compose = yaml.safe_load((ROOT / 'deploy/cloud/compose.yaml').read_text())
        for name, service in compose['services'].items():
            self.assertEqual(bool(service.get('ports')), name == 'gateway')
        self.assertTrue(compose['networks']['data']['internal'])
        self.assertEqual(compose['services']['business']['environment']['PLATFORM_DEMO'], 'false')
        self.assertEqual(compose['services']['business']['environment']['PLATFORM_SECURE_COOKIE'], 'true')

    def test_configuration_is_new_and_existing_key_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('prepare_config.py', 'config.example.env'):
                shutil.copyfile(ROOT / 'deploy/cloud' / name, root / name)
            command = [sys.executable, str(root / 'prepare_config.py'), '--domain', 'cloud.example.com', '--email', 'ops@example.com']
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            env = (root / '.env').read_text()
            key = (root / 'secrets/application.key').read_bytes()
            self.assertEqual(len(key), 32)
            values = dict(line.split('=', 1) for line in env.splitlines() if '=' in line and not line.startswith('#'))
            self.assertNotEqual(values['MYSQL_PASSWORD'], values['MYSQL_ROOT_PASSWORD'])
            self.assertNotIn(values['PLATFORM_ADMIN_PASSWORD'], result.stdout)
            self.assertEqual(values['SILICONFLOW_API_KEY'], '')
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual((root / '.env').read_text(), env)
            self.assertEqual((root / 'secrets/application.key').read_bytes(), key)


if __name__ == '__main__':
    unittest.main()
