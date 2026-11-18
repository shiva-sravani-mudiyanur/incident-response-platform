# Stage 15 verification: HTTP balancing versus Kafka assignment

Observed execution against `kind-incident-response-stage14`, namespace `incident-response`. These are measured results, not sample output.

## Endpoint and deployment

- Added `GET /api/instance` to incident-service with DTO fields `serviceName`, `hostname`, `timestamp` and `Cache-Control: no-store`.
- Hostname resolves once from the real OS using `InetAddress.getLocalHost().getHostName()`. The service reuses the injected UTC Clock and captures the timestamp for each request. The controller delegates to the service; no hostname/pod name is manually injected.
- Built and loaded `incident-response/incident-service:stage15` into Kind; other application images remain `stage14`.
- Applied server-side dry run and actual manifests, then successfully waited for the incident Deployment rollout.
- Two incident replicas were Ready with zero application restarts. The Service explicitly has `sessionAffinity: None`. No Java load balancer, sticky cookie/session, gateway route, or direct-pod request routing was added.

## Real HTTP demonstration

Actual command:

```powershell
Set-Location 'C:\Users\Shiva Sravani\Desktop\incident-response'
$env:KUBECONFIG = Join-Path $env:USERPROFILE '.kube\incident-response-stage14.yaml'
& .\kubernetes\demo-load-balancing.ps1 -Requests 20
```

Each iteration launched a new curl process inside the api-gateway pod, opening a fresh connection to `http://incident-service:8080/api/instance`. This uses the Kubernetes Service, not the gateway's route table, direct pod IPs, or an incident-service port-forward. Responses were checked against the current Ready pod names and valid service/timestamp fields.

Actual result:

| Responding hostname | Successful requests |
| --- | ---: |
| incident-service-666fff5775-nkmrl | 5 |
| incident-service-666fff5775-xwcvz | 15 |
| Total | 20 |

Both pod instances answered through the same Service DNS destination. This demonstrates multi-pod HTTP connection distribution. The unequal split is valid; Kubernetes does not promise strict request alternation or a 50/50 sample. The script fails rather than claiming distribution if fewer than two valid hostnames are observed.

## Separate Kafka observation

Actual read-only inspection:

```powershell
kubectl -n incident-response get pods -l app.kubernetes.io/name=incident-service -o wide
kubectl -n incident-response exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe --group incident-processors --members --verbose
```

The stable two-consumer assignment observed during this check was:

| Pod / member host | Assigned service-events partitions |
| --- | --- |
| incident-service-666fff5775-nkmrl / 10.244.0.20 | 0, 1 |
| incident-service-666fff5775-xwcvz / 10.244.0.19 | 2 |

Kafka partition ownership is independent of HTTP request selection. The pod that answered 5 HTTP requests owned two partitions, while the pod that answered 15 owned one. These counts do not measure Kafka event volume or processing effort. Assignments/IPs may change after a rebalance/redeployment; do not copy old IDs as current configuration. No events were published, offsets reset, or group membership deliberately changed for this inspection.

## Tests and build results

- Full native Windows Maven `test` reactor: **534 tests passed**, zero failures/errors/skips. Incident-service contributes 147 tests, including five new service/hostname/controller tests.
- Five new JVM tests cover actual hostname resolution, fixed-clock response identity, fresh timestamps, GET JSON/no-store contract, and rejection of POST.
- Packaging/manifest suite: **18 passed**, including stage15 incident image and explicit no-affinity contract.
- Windows PowerShell/Pester demo contracts: **6 passed**, including actual count aggregation, rejection of a single observed backend/invalid response, Service-DNS curl invocation, and rejection of sticky affinity before requests.
- Docker packaging and Kind image loading succeeded; the new packaged application was exercised by the live demonstration.
- The optional native incident-module `verify` goal passed its tests but failed at Spring Boot repackage because Windows could not rename the existing target JAR to `.jar.original`. It was not reported as a successful native verify. No running user process was killed to remove the file/rename restriction. A separate full `test` run passed, and real Docker packaging/deployment succeeded, so this does not block the requested Kubernetes demonstration.
- Framework/dependency pins and database schema/detection logic were unchanged. No real model/provider, simulator, or incident-write request was made.

See [LOAD_BALANCING.md](LOAD_BALANCING.md) for the reproducible demo, connection-reuse caveat and separate Kafka concepts; [README.md](README.md) contains updated build/load/apply commands. Native, Compose and other Kubernetes services/data were preserved; only the requested incident Deployment rolled forward. The cluster was left running for inspection. No commits or pushes were made.
