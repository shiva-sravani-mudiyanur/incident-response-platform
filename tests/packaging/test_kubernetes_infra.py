"""Infrastructure contract: JSON Kubernetes resources need no parser dependency."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class KubernetesInfrastructureTest(unittest.TestCase):
    def resources(self):
        path = ROOT / 'kubernetes/infra/infrastructure.json'
        self.assertTrue(path.is_file(), 'Local Kubernetes infrastructure manifests are missing')
        return {(r['kind'], r['metadata']['name']): r for r in json.loads(path.read_text())['items']}

    def test_simple_shared_dependencies_and_persistent_claims(self):
        resources = self.resources()
        for name in ('postgres', 'kafka'):
            spec = resources[('StatefulSet', name)]['spec']
            self.assertEqual(spec['replicas'], 1)
            self.assertEqual(len(spec['volumeClaimTemplates']), 1)
            self.assertIn(('Service', name), resources)
        self.assertEqual(resources[('Deployment','redis')]['spec']['replicas'], 1)
        kafka = resources[('Service','kafka')]['spec']
        self.assertEqual(kafka['clusterIP'], 'None')
        self.assertTrue(kafka['publishNotReadyAddresses'])

    def test_no_secret_literals_or_host_ports_and_all_requests_probes_present(self):
        for (kind, name), resource in self.resources().items():
            self.assertNotEqual(kind, 'Secret')
            if kind not in ('Deployment','StatefulSet'): continue
            pod = resource['spec']['template']['spec']
            self.assertFalse(pod['automountServiceAccountToken'])
            for container in pod['containers']:
                self.assertIn('requests', container['resources'])
                self.assertIn('limits', container['resources'])
                self.assertIn('readinessProbe', container)
                self.assertIn('livenessProbe', container)
                for port in container.get('ports', []):
                    self.assertNotIn('hostPort', port)
                for env in container.get('env', []):
                    if env['name'] in ('POSTGRES_PASSWORD','POSTGRES_USER'):
                        self.assertIn('secretKeyRef', env['valueFrom'])

    def test_topic_initialization_is_bounded(self):
        job = self.resources()[('Job','kafka-init')]['spec']
        self.assertLessEqual(job['activeDeadlineSeconds'], 300)
        self.assertLessEqual(job['backoffLimit'], 6)
        command = ' '.join(job['template']['spec']['containers'][0]['command'])
        self.assertIn('--create --if-not-exists', command)
        self.assertIn('kafka:19092', command)


if __name__ == '__main__':
    unittest.main()
