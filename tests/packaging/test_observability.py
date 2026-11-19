"""Repository observability wiring contracts; no provider or application calls."""
from pathlib import Path
import json
import unittest

from test_kubernetes import PORTS, load_documents

ROOT = Path(__file__).resolve().parents[2]


class ObservabilityContractTest(unittest.TestCase):
    def test_grafana_provisions_the_datasource_and_one_real_metric_dashboard(self):
        base = ROOT / 'observability/grafana'
        source = base / 'provisioning/datasources/datasource.yml'
        self.assertTrue(source.is_file(), 'Grafana needs an automatically provisioned datasource')
        config = load_documents(source.read_text())[0]
        self.assertEqual(len(config['datasources']), 1)
        datasource = config['datasources'][0]
        self.assertEqual(datasource['uid'], 'prometheus')
        self.assertEqual(datasource['url'], 'http://prometheus:9090')
        self.assertEqual(datasource['access'], 'proxy')
        self.assertTrue(datasource['isDefault'])
        providers = load_documents((base / 'provisioning/dashboards/default.yml').read_text())[0]['providers']
        self.assertEqual(len(providers), 1)
        self.assertEqual(providers[0]['options']['path'], '/var/lib/grafana/dashboards')
        self.assertFalse(providers[0]['allowUiUpdates'])
        dashboards = list((base / 'dashboards').glob('*.json'))
        self.assertEqual(len(dashboards), 1)
        dashboard = json.loads(dashboards[0].read_text())
        self.assertEqual(dashboard['uid'], 'incident-response')
        self.assertEqual(len(dashboard['panels']), 7)
        queries = []
        for panel in dashboard['panels']:
            self.assertEqual(panel['datasource']['uid'], datasource['uid'])
            self.assertTrue(panel['targets'])
            queries.extend(target['expr'] for target in panel['targets'])
        query_text = '\n'.join(queries)
        for metric in ('incident_events_processed_total', 'incident_incidents_total',
                       'incident_incidents_active', 'incident_events_errors_total',
                       'incident_events_processing_seconds_sum', 'incident_events_processing_seconds_count',
                       'analysis_duration_seconds_sum', 'analysis_duration_seconds_count'):
            self.assertIn(metric, query_text)
        # Every replica reads the same database-wide active count; never sum it.
        active = next(query for query in queries if 'incident_incidents_active' in query)
        self.assertTrue(active.startswith('max('))

    def test_prometheus_scrapes_each_application_directly_over_service_dns(self):
        path = ROOT / 'observability/prometheus/prometheus.yml'
        self.assertTrue(path.is_file(), 'Prometheus needs a repository-owned scrape configuration')
        config = load_documents(path.read_text())[0]
        self.assertEqual(config['global']['scrape_interval'], '15s')
        self.assertEqual(config['global']['scrape_timeout'], '5s')
        jobs = config['scrape_configs']
        self.assertEqual({job['job_name'] for job in jobs}, set(PORTS))
        self.assertEqual(len(jobs), len(PORTS))
        for job in jobs:
            with self.subTest(service=job['job_name']):
                self.assertEqual(job['metrics_path'], '/actuator/prometheus')
                self.assertEqual(job['static_configs'], [
                    {'targets': [f"{job['job_name']}:{PORTS[job['job_name']]}"]}])


if __name__ == '__main__':
    unittest.main()
