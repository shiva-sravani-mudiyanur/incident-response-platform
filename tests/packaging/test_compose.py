"""Real Compose rendering checks; use only synthetic credentials, never local .env."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
APPS = {'api-gateway', 'event-service', 'incident-service', 'ai-analysis-service',
        'diagnostics-mcp-server', 'service-simulator'}


def cli_path(path, executable):
    if executable.endswith('.exe') and os.name != 'nt':
        return subprocess.check_output(['wslpath', '-w', str(path)], text=True).strip()
    return str(path)


class ComposeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docker = shutil.which('docker.exe') or shutil.which('docker')
        if not cls.docker:
            raise RuntimeError('Docker Compose CLI is required for packaging checks')
        # Avoid inherited values or the user's local credential file.
        env = {k: v for k, v in os.environ.items() if k in {
            'PATH', 'HOME', 'USERPROFILE', 'SYSTEMROOT', 'SystemRoot', 'TEMP', 'TMP',
            'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_CONFIG', 'WSL_INTEROP', 'WSL_DISTRO_NAME'}}
        cls.fixture = tempfile.TemporaryDirectory(prefix='compose-contract-', dir=os.environ.get('TMPDIR'))
        fixture = Path(cls.fixture.name, 'fixture.env')
        fixture.write_text('DB_NAME=contract_db\nDB_USERNAME=contract_user\n'
                           'DB_PASSWORD=synthetic-contract-password\nDB_PORT=15433\n'
                           'OPENAI_API_KEY=synthetic-contract-key\nOPENAI_MODEL=contract-model\n'
                           'DB_HOST=localhost\nREDIS_HOST=localhost\nREDIS_PORT=16379\n'
                           'KAFKA_BOOTSTRAP_SERVERS=localhost:9092\n'
                           'EVENT_SERVICE_BASE_URL=http://localhost:8082\n'
                           'INCIDENT_SERVICE_BASE_URL=http://localhost:8080\n'
                           'DIAGNOSTICS_MCP_BASE_URL=http://localhost:8084\n')
        args = [cls.docker, 'compose', '--env-file', cli_path(fixture, cls.docker),
                '-f', cli_path(ROOT / 'docker-compose.yml', cls.docker), 'config', '--format', 'json']
        result = subprocess.run(args, capture_output=True, text=True, env=env, cwd=ROOT)
        if result.returncode:
            raise RuntimeError('Synthetic Compose fixture did not render: ' + result.stderr + result.stdout)
        cls.config = json.loads(result.stdout)
        cls.services = cls.config['services']

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def test_all_six_applications_have_source_builds(self):
        self.assertTrue(APPS.issubset(self.services))
        for name in APPS:
            with self.subTest(service=name):
                self.assertEqual(self.services[name]['build']['args']['MODULE'], name)

    def test_container_routes_ignore_native_loopback_settings(self):
        expected = {
            'incident-service': {'DB_HOST': 'postgres', 'DB_PORT': '5432', 'REDIS_HOST': 'redis',
                                 'REDIS_PORT': '6379', 'KAFKA_BOOTSTRAP_SERVERS': 'kafka:19092'},
            'event-service': {'KAFKA_BOOTSTRAP_SERVERS': 'kafka:19092'},
            'service-simulator': {'EVENT_SERVICE_BASE_URL': 'http://event-service:8082'},
            'diagnostics-mcp-server': {'INCIDENT_SERVICE_BASE_URL': 'http://incident-service:8080'},
            'ai-analysis-service': {'DIAGNOSTICS_MCP_BASE_URL': 'http://diagnostics-mcp-server:8084'},
            'api-gateway': {'GATEWAY_INCIDENT_SERVICE_URL': 'http://incident-service:8080',
                            'GATEWAY_EVENT_SERVICE_URL': 'http://event-service:8082',
                            'GATEWAY_SIMULATOR_URL': 'http://service-simulator:8083',
                            'GATEWAY_ANALYSIS_SERVICE_URL': 'http://ai-analysis-service:8085'}}
        for name, fields in expected.items():
            for key, value in fields.items():
                with self.subTest(service=name, field=key):
                    self.assertEqual(self.services[name]['environment'][key], value)

    def test_health_checks_and_hardened_runtime_for_every_application(self):
        for name in APPS:
            with self.subTest(service=name):
                svc = self.services[name]
                self.assertEqual(svc['environment']['SERVER_ADDRESS'], '0.0.0.0')
                self.assertIn('/app/healthcheck.sh', svc['healthcheck']['test'])
                self.assertTrue(svc['read_only'])
                self.assertTrue(svc['init'])
                self.assertIn('ALL', svc['cap_drop'])
                self.assertIn('no-new-privileges:true', svc['security_opt'])
                self.assertTrue(svc['tmpfs'])
                for port in svc.get('ports', []):
                    self.assertEqual(port['host_ip'], '127.0.0.1')

    def test_topic_creation_precedes_kafka_applications(self):
        for name in ['event-service', 'incident-service']:
            deps = self.services[name]['depends_on']
            self.assertEqual(deps['kafka-init']['condition'], 'service_completed_successfully')
            self.assertEqual(deps['kafka']['condition'], 'service_healthy')
        for name in ['postgres', 'redis']:
            self.assertEqual(self.services['incident-service']['depends_on'][name]['condition'], 'service_healthy')

    def test_provider_key_only_reaches_ai_runtime(self):
        self.assertEqual(self.services['ai-analysis-service']['environment']['OPENAI_API_KEY'], 'synthetic-contract-key')
        self.assertEqual(self.services['ai-analysis-service']['environment']['OPENAI_MODEL'], 'contract-model')
        for name, svc in self.services.items():
            if name != 'ai-analysis-service':
                self.assertNotIn('OPENAI_API_KEY', svc.get('environment', {}))
            self.assertNotIn('OPENAI_API_KEY', svc.get('build', {}).get('args', {}))

    def test_existing_persistent_volumes_are_preserved(self):
        self.assertIn('postgres_data', self.config['volumes'])
        self.assertIn('kafka_data', self.config['volumes'])
        self.assertEqual(self.services['postgres']['volumes'][0]['source'], 'postgres_data')
        self.assertEqual(self.services['kafka']['volumes'][0]['source'], 'kafka_data')

    def test_observers_are_isolated_from_business_dependencies_and_use_persistent_data(self):
        for name, internal in [('prometheus', 9090), ('grafana', 3000)]:
            self.assertIn(name, self.services, name + ' must start through Compose')
            service = self.services[name]
            self.assertTrue(service['read_only'])
            self.assertTrue(service['healthcheck']['test'])
            self.assertIn('ALL', service['cap_drop'])
            self.assertTrue(all(port['host_ip'] == '127.0.0.1' for port in service['ports']))
            self.assertEqual(service['ports'][0]['target'], internal)
            mounts = service['volumes']
            self.assertTrue(any(mount['type'] == 'volume' for mount in mounts))
            self.assertTrue(all(mount.get('read_only') for mount in mounts if mount['type'] == 'bind'))
        for name in APPS:
            self.assertFalse({'prometheus', 'grafana'}.intersection(self.services[name].get('depends_on', {})))
        grafana = self.services['grafana']
        self.assertIn('GF_SECURITY_ADMIN_PASSWORD:?', grafana['entrypoint'][-1])
        self.assertIn('exec /run.sh', grafana['entrypoint'][-1])
        for name, service in self.services.items():
            if name != 'grafana':
                self.assertNotIn('GF_SECURITY_ADMIN_PASSWORD', service.get('environment', {}))

    def test_ai_refuses_empty_external_credentials_before_java_starts(self):
        entrypoint = self.services['ai-analysis-service'].get('entrypoint', [])
        self.assertTrue(entrypoint, 'AI needs fail-fast external key/model validation')
        self.assertIn('OPENAI_API_KEY:?', entrypoint[-1])
        self.assertIn('OPENAI_MODEL:?', entrypoint[-1])
        self.assertIn('exec java', entrypoint[-1])


if __name__ == '__main__':
    unittest.main()
