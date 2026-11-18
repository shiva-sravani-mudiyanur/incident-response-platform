"""Offline Stage 14 application contracts; no cluster, .env, or provider calls.

Run with a local Python that has PyYAML, e.g. /usr/bin/python3 on WSL.
Only the kubectl client (including native Windows kubectl.exe) is invoked.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

try:
    import yaml
except ImportError:
    yaml = None


def load_documents(text):
    """Use an existing local YAML parser without changing project dependencies."""
    if yaml is not None:
        return list(yaml.safe_load_all(text))
    # WSL's system Python may have PyYAML while the agent's isolated Python does not.
    parser = Path('/usr/bin/python3')
    if not parser.is_file():
        raise RuntimeError('Run packaging checks using a local Python with PyYAML installed')
    result = subprocess.run([str(parser), '-c',
                             'import json,sys,yaml; print(json.dumps(list(yaml.safe_load_all(sys.stdin.read()))))'],
                            input=text, capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError('A local Python with PyYAML is required: ' + result.stderr)
    return json.loads(result.stdout)

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'kubernetes' / 'base'
PORTS = {'incident-service': 8080, 'api-gateway': 8081, 'event-service': 8082,
         'service-simulator': 8083, 'diagnostics-mcp-server': 8084,
         'ai-analysis-service': 8085}
REPLICAS = {name: (2 if name in {'incident-service', 'event-service'} else 1)
            for name in PORTS}


def render():
    kubectl = shutil.which('kubectl.exe') or shutil.which('kubectl')
    if not kubectl:
        raise RuntimeError('kubectl client is required; no cluster is needed')
    path = str(BASE)
    if kubectl.endswith('.exe') and os.name != 'nt':
        path = subprocess.check_output(['wslpath', '-w', path], text=True).strip()
    result = subprocess.run([kubectl, 'kustomize', path], capture_output=True,
                            text=True, timeout=60, cwd=ROOT)
    if result.returncode:
        raise AssertionError('Application base must render offline: ' + result.stderr)
    return [doc for doc in load_documents(result.stdout) if doc]


class KubernetesContractTest(unittest.TestCase):
    def test_offline_base_contains_exact_six_applications_and_services(self):
        docs = render()
        self.assertEqual(len({(d['kind'], d['metadata']['name']) for d in docs}), len(docs))
        for kind in ('Deployment', 'Service'):
            self.assertEqual({d['metadata']['name'] for d in docs if d['kind'] == kind}, set(PORTS))
        self.assertTrue(all(d['kind'] in {'Deployment', 'Service', 'ConfigMap'} for d in docs))
        for doc in docs:
            self.assertEqual(doc['metadata']['namespace'], 'incident-response')
        deployments = {d['metadata']['name']: d for d in docs if d['kind'] == 'Deployment'}
        services = {d['metadata']['name']: d for d in docs if d['kind'] == 'Service'}
        for name, port in PORTS.items():
            with self.subTest(app=name):
                spec = deployments[name]['spec']
                pod = spec['template']['spec']
                self.assertEqual(spec['replicas'], REPLICAS[name])
                self.assertEqual(len(pod['containers']), 1)
                container = pod['containers'][0]
                self.assertEqual(container['image'], 'incident-response/' + name + ':stage14')
                self.assertEqual(container['imagePullPolicy'], 'IfNotPresent')
                self.assertEqual(container['ports'], [{'name': 'http', 'containerPort': port}])
                self.assertEqual(spec['selector']['matchLabels'], spec['template']['metadata']['labels'])
                service = services[name]['spec']
                self.assertEqual(service['type'], 'ClusterIP')
                self.assertEqual(service['selector'], spec['selector']['matchLabels'])
                self.assertEqual(service['ports'], [{'name': 'http', 'port': port, 'targetPort': 'http'}])
        kustomization = load_documents((BASE / 'kustomization.yaml').read_text())[0]
        self.assertEqual(kustomization['namespace'], 'incident-response')
        resources = kustomization['resources']
        self.assertEqual(len(resources), len(set(resources)))
        self.assertEqual(set(resources), {p.name for p in BASE.glob('*.yaml') if p.name != 'kustomization.yaml'})
        self.assertTrue(all((BASE / name).is_file() for name in resources))

    def test_scoped_configuration_and_synthetic_secret_contracts(self):
        docs = render()
        maps = {d['metadata']['name']: d['data'] for d in docs if d['kind'] == 'ConfigMap'}
        deployments = {d['metadata']['name']: d for d in docs if d['kind'] == 'Deployment'}
        self.assertIn('application-common', maps)
        self.assertEqual(maps['application-common']['DB_NAME'], 'incident_response')
        forbidden = {'OPENAI_API_KEY', 'OPENAI_MODEL', 'DB_USERNAME', 'DB_PASSWORD'}
        for data in maps.values():
            self.assertFalse(forbidden.intersection(data))
            self.assertTrue(all(isinstance(value, str) for value in data.values()))
        expected = {
            'incident-service': {'DB_HOST': 'postgres', 'DB_PORT': '5432', 'DB_NAME': 'incident_response',
                                 'REDIS_HOST': 'redis', 'REDIS_PORT': '6379',
                                 'KAFKA_BOOTSTRAP_SERVERS': 'kafka:19092',
                                 'KAFKA_EVENTS_TOPIC': 'service-events', 'KAFKA_CONSUMER_GROUP': 'incident-processors'},
            'event-service': {'KAFKA_BOOTSTRAP_SERVERS': 'kafka:19092', 'KAFKA_EVENTS_TOPIC': 'service-events'},
            'service-simulator': {'EVENT_SERVICE_BASE_URL': 'http://event-service:8082'},
            'diagnostics-mcp-server': {'INCIDENT_SERVICE_BASE_URL': 'http://incident-service:8080'},
            'ai-analysis-service': {'DIAGNOSTICS_MCP_BASE_URL': 'http://diagnostics-mcp-server:8084'},
            'api-gateway': {'GATEWAY_EVENT_SERVICE_URL': 'http://event-service:8082',
                            'GATEWAY_INCIDENT_SERVICE_URL': 'http://incident-service:8080',
                            'GATEWAY_ANALYSIS_SERVICE_URL': 'http://ai-analysis-service:8085',
                            'GATEWAY_SIMULATOR_URL': 'http://service-simulator:8083'}}
        # Synthetic values only; mirror kubelet's envFrom then explicit env precedence.
        secrets = {'incident-database': {'DB_USERNAME': 'synthetic-user', 'DB_PASSWORD': 'synthetic-password'},
                   'analysis-provider': {'OPENAI_API_KEY': 'synthetic-key', 'OPENAI_MODEL': 'synthetic-model'}}
        for name, deployment in deployments.items():
            with self.subTest(app=name):
                container = deployment['spec']['template']['spec']['containers'][0]
                env = {}
                refs = container.get('envFrom', [])
                self.assertIn({'configMapRef': {'name': 'application-common'}}, refs)
                for source in refs:
                    self.assertEqual(set(source), {'configMapRef'}, 'Never import an entire Secret')
                    env.update(maps[source['configMapRef']['name']])
                entries = container.get('env', [])
                self.assertEqual(len(entries), len({entry['name'] for entry in entries}))
                secret_refs = {}
                for entry in entries:
                    if 'value' in entry:
                        self.assertNotIn(entry['name'], forbidden)
                        env[entry['name']] = entry['value']
                    else:
                        ref = entry['valueFrom']['secretKeyRef']
                        secret_refs[entry['name']] = ref
                        value = secrets.get(ref['name'], {}).get(ref['key'])
                        if value is not None:
                            env[entry['name']] = value
                        else:
                            self.assertTrue(ref.get('optional', False))
                self.assertEqual(env['SERVER_ADDRESS'], '0.0.0.0')
                self.assertEqual(env['SERVER_PORT'], str(PORTS[name]))
                for key, value in expected[name].items():
                    self.assertEqual(env[key], value)
                if name == 'incident-service':
                    self.assertEqual(set(secret_refs), {'DB_USERNAME', 'DB_PASSWORD'})
                    for key in secret_refs:
                        self.assertEqual(secret_refs[key], {'name': 'incident-database', 'key': key, 'optional': False})
                elif name == 'ai-analysis-service':
                    self.assertEqual(set(secret_refs), {'OPENAI_API_KEY', 'OPENAI_MODEL',
                                     'SPRING_AI_OPENAI_CHAT_API_KEY', 'SPRING_AI_OPENAI_CHAT_BASE_URL'})
                    for key in ('OPENAI_API_KEY', 'OPENAI_MODEL', 'SPRING_AI_OPENAI_CHAT_API_KEY'):
                        target = 'OPENAI_API_KEY' if key == 'SPRING_AI_OPENAI_CHAT_API_KEY' else key
                        self.assertEqual(secret_refs[key], {'name': 'analysis-provider', 'key': target, 'optional': False})
                    self.assertEqual(secret_refs['SPRING_AI_OPENAI_CHAT_BASE_URL'], {
                        'name': 'analysis-provider', 'key': 'SPRING_AI_OPENAI_CHAT_BASE_URL', 'optional': True})
                    self.assertEqual(env['SPRING_AI_OPENAI_CHAT_BASE_URL'], 'https://api.openai.com/v1')
                    self.assertEqual(env['SPRING_AI_OPENAI_CHAT_API_KEY'], 'synthetic-key')
                    # A provided optional override must remain higher priority than the default.
                    self.assertIn('SPRING_AI_OPENAI_CHAT_BASE_URL', [e['name'] for e in entries])
                else:
                    self.assertEqual(secret_refs, {})
                if name != 'ai-analysis-service':
                    self.assertFalse({'OPENAI_API_KEY', 'OPENAI_MODEL', 'SPRING_AI_OPENAI_CHAT_API_KEY'}.intersection(env))
                if name != 'incident-service':
                    self.assertFalse({'DB_USERNAME', 'DB_PASSWORD'}.intersection(env))

    def test_probe_groups_and_boot4_read_only_health_access(self):
        docs = render()
        maps = {d['metadata']['name']: d['data'] for d in docs if d['kind'] == 'ConfigMap'}
        for deployment in (d for d in docs if d['kind'] == 'Deployment'):
            name = deployment['metadata']['name']
            with self.subTest(app=name):
                container = deployment['spec']['template']['spec']['containers'][0]
                env = {}
                for source in container['envFrom']:
                    env.update(maps[source['configMapRef']['name']])
                env.update({entry['name']: entry['value'] for entry in container['env'] if 'value' in entry})
                explicit = {entry['name']: entry.get('value') for entry in container['env']}
                self.assertEqual(explicit.get('MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED'), 'true')
                self.assertEqual(env['MANAGEMENT_ENDPOINT_HEALTH_ACCESS'], 'read-only')
                self.assertEqual(env['MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE'], 'health')
                self.assertEqual(env['MANAGEMENT_ENDPOINTS_ACCESS_DEFAULT'], 'none')
                self.assertEqual(env['MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE'], 'livenessState')
                readiness = 'readinessState,db,redis' if name == 'incident-service' else 'readinessState'
                self.assertEqual(env['MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE'], readiness)
                for probe_name, path in [('startupProbe', '/actuator/health/liveness'),
                                         ('livenessProbe', '/actuator/health/liveness'),
                                         ('readinessProbe', '/actuator/health/readiness')]:
                    probe = container[probe_name]
                    self.assertEqual(probe['httpGet'], {'path': path, 'port': 'http'})
                    self.assertGreaterEqual(probe['timeoutSeconds'], 1)
                    self.assertGreaterEqual(probe['periodSeconds'], 1)
                    self.assertGreaterEqual(probe['failureThreshold'], 1)
                startup = container['startupProbe']
                self.assertGreaterEqual(startup['periodSeconds'] * startup['failureThreshold'], 300)

    def test_hardened_runtime_resources_and_zero_unavailable_rollouts(self):
        for deployment in (d for d in render() if d['kind'] == 'Deployment'):
            name = deployment['metadata']['name']
            with self.subTest(app=name):
                spec = deployment['spec']
                self.assertEqual(spec.get('strategy'), {'type': 'RollingUpdate', 'rollingUpdate': {'maxSurge': 1, 'maxUnavailable': 0}})
                self.assertEqual(spec['minReadySeconds'], 5)
                pod = spec['template']['spec']
                self.assertFalse(pod['automountServiceAccountToken'])
                self.assertEqual(pod['terminationGracePeriodSeconds'], 30)
                self.assertEqual(pod['securityContext']['runAsUser'], 10001)
                self.assertEqual(pod['securityContext']['runAsGroup'], 10001)
                self.assertTrue(pod['securityContext']['runAsNonRoot'])
                self.assertEqual(pod['securityContext']['seccompProfile'], {'type': 'RuntimeDefault'})
                container = pod['containers'][0]
                self.assertTrue(container['securityContext']['readOnlyRootFilesystem'])
                self.assertFalse(container['securityContext']['allowPrivilegeEscalation'])
                self.assertEqual(container['securityContext']['capabilities'], {'drop': ['ALL']})
                self.assertEqual(container['resources']['requests'], {'cpu': '100m', 'memory': '256Mi'})
                memory = '1Gi' if name in {'incident-service', 'ai-analysis-service'} else '768Mi'
                self.assertEqual(container['resources']['limits'], {'cpu': '1', 'memory': memory})
                self.assertEqual(pod['volumes'], [{'name': 'tmp', 'emptyDir': {'sizeLimit': '128Mi'}}])
                self.assertEqual(container['volumeMounts'], [{'name': 'tmp', 'mountPath': '/tmp'}])
                self.assertNotIn('hostNetwork', pod)
                self.assertNotIn('hostPID', pod)
                self.assertNotIn('hostIPC', pod)

    def test_kafka_consumers_and_producers_wait_for_existing_topic(self):
        deployments = {d['metadata']['name']: d for d in render() if d['kind'] == 'Deployment'}
        for name, deployment in deployments.items():
            pod = deployment['spec']['template']['spec']
            if name not in {'incident-service', 'event-service'}:
                self.assertFalse(pod.get('initContainers'))
                continue
            with self.subTest(app=name):
                init = pod.get('initContainers', [])
                self.assertEqual(len(init), 1, 'Kafka applications must wait for the infrastructure topic Job')
                wait = init[0]
                self.assertEqual(wait['name'], 'wait-for-events-topic')
                self.assertEqual(wait['image'], 'apache/kafka:4.1.2')
                self.assertEqual(wait['imagePullPolicy'], 'IfNotPresent')
                self.assertEqual(wait['command'], ['/bin/sh', '-ec'])
                script = wait['args'][0]
                self.assertIn('/opt/kafka/bin/kafka-topics.sh', script)
                self.assertIn('--bootstrap-server kafka:19092', script)
                self.assertIn('--describe --topic service-events', script)
                self.assertNotIn('--create', script)
                self.assertIn('while [ "$attempt" -le 120 ]', script)
                self.assertIn('attempt=$((attempt + 1))', script)
                self.assertIn('exit 1', script)
                self.assertIn('default.api.timeout.ms=5000', script)
                self.assertIn('request.timeout.ms=5000', script)
                shell = shutil.which('sh')
                if shell:
                    self.assertEqual(subprocess.run([shell, '-n'], input=script, text=True,
                                                    capture_output=True).returncode, 0)
                env = {entry['name']: entry['value'] for entry in wait['env']}
                self.assertEqual(env['KAFKA_HEAP_OPTS'], '-Xms32m -Xmx128m')
                self.assertEqual(env['LOG_DIR'], '/tmp/kafka-logs')
                self.assertEqual(wait['securityContext'], {
                    'readOnlyRootFilesystem': True, 'allowPrivilegeEscalation': False,
                    'capabilities': {'drop': ['ALL']}})
                self.assertEqual(wait['volumeMounts'], [{'name': 'tmp', 'mountPath': '/tmp'}])
                self.assertTrue(wait['resources']['requests'])
                self.assertTrue(wait['resources']['limits'])


if __name__ == '__main__':
    unittest.main()
