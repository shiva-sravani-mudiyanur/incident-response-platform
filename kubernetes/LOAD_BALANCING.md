# Stage 15: HTTP Service distribution and Kafka consumer groups

These are two different kinds of work distribution, not one load balancer. No custom Java load balancer or sticky-session mechanism is introduced:

| Mechanism | Work being distributed | Stage 15 target |
| --- | --- | --- |
| Kubernetes `Service` | HTTP connections to the incident API | `incident-service:8080` with at least two Ready incident pods |
| Kafka consumer group | Ownership of topic partitions | Two consumers in `incident-processors`, consuming the three partitions of `service-events` |

This is a read-only demonstration guide, not an execution transcript. Deployment, endpoint/tests, the demo script, and actual verification evidence are maintained separately. Do not treat the expected observations below as measured results.

## Repository configuration and prerequisites

The inspected configuration is in:

- `kubernetes/base/incident-service.yaml`: two replicas; a `ClusterIP` Service selecting `app.kubernetes.io/name: incident-service`; Service port `8080` targets the container's named `http` port, also `8080`.
- `kubernetes/base/api-gateway.yaml`: the in-cluster client deployment used by this demo.
- `kubernetes/infra/infrastructure.json`: `apache/kafka:4.1.2`, bootstrap address `kafka:19092`, and infrastructure-owned creation of `service-events` with three partitions and replication factor one.
- `incident-service/src/main/resources/application.yaml` and `consumer/EventConsumer.java`: the `incident-processors` group and one topic listener. `config/KafkaConsumerConfiguration.java` does not increase listener concurrency. With two healthy, joined application instances, the demo target is two group members; verify membership rather than assuming pod readiness proves it.
- `event-service/src/main/java/com/umass/event/service/KafkaEventPublisher.java`: publishes with `event.serviceName()` as the record key; `config/KafkaProducerConfiguration.java` uses a string key serializer.
- Root `pom.xml`: Java 21 and Spring Boot `4.0.9`. The existing cluster guide pins Kind `v0.33.0` and Kubernetes node `v1.36.4`; these are repository pins, not a claim about the current live cluster.

Before running this guide, the Stage 15 deployment must be complete: incident-service uses `incident-response/incident-service:stage15` with two replicas, while the other application images retain `stage14`. The endpoint and `kubernetes/demo-load-balancing.ps1` must already be present. This guide does not rebuild images, apply manifests, scale workloads, publish events, or call an AI provider.

The required Service policy is `spec.sessionAffinity: None` (no sticky sessions). The inspected pre-update Service omitted the field, whose Kubernetes default is `None`; Stage 15 makes the intended policy explicit. Check the live Service below instead of assuming the manifest was deployed.[2]

## 1. Select the dedicated cluster in Windows PowerShell

Run from the repository root. Select the dedicated kubeconfig in **every new terminal**; do not overwrite or print its credentials.

```powershell
# Replace this path with your actual checkout directory.
Set-Location 'C:\path\to\incident-response'
$env:KUBECONFIG = Join-Path $env:USERPROFILE '.kube\incident-response-stage14.yaml'
$context = kubectl config current-context
if ($LASTEXITCODE -ne 0 -or $context -ne 'kind-incident-response-stage14') {
    throw 'Wrong or unavailable context; stop and check the dedicated kubeconfig.'
}
kubectl -n incident-response get deployment incident-service api-gateway
kubectl -n incident-response get pods -l app.kubernetes.io/name=incident-service -o wide
kubectl -n incident-response get service incident-service -o yaml
kubectl -n incident-response get endpointslices -l kubernetes.io/service-name=incident-service -o yaml
```

Check two Ready incident pods, the intended deployed image, Service selector/port, `sessionAffinity: None`, and ready EndpointSlice backends matching those pods. A Service uses selectors and EndpointSlices to identify its backends; the Service DNS name provides a stable in-cluster destination.[1]

Use current pod names and addresses from this cluster. Do not copy old pod suffixes, IPs, resource UUIDs, consumer IDs, or topic IDs from another cluster or an earlier run.

## 2. HTTP: send fresh connections through the Service

The Stage 15 read-only endpoint is:

```text
http://incident-service:8080/api/instance
```

Its JSON fields are:

| Field | Meaning |
| --- | --- |
| `serviceName` | Logical application name, `incident-service` |
| `hostname` | Actual OS hostname resolved with Java `InetAddress` |
| `timestamp` | Timestamp supplied by the responding instance |

The default Pod hostname derives from its pod name. No manual hostname assignment or Downward API injection is needed for this design.[8]

The path under test is:

```text
fresh curl inside api-gateway pod
  -> incident-service Service DNS :8080
  -> Service networking
  -> one ready incident-service pod
  -> /api/instance JSON
```

The api-gateway pod is just the location of the HTTP client here; the command does **not** call a gateway HTTP route. A single diagnostic request is:

```powershell
kubectl -n incident-response exec deployment/api-gateway -- curl --fail --silent --show-error --connect-timeout 5 --max-time 15 http://incident-service:8080/api/instance
```

Run the bounded demonstration:

```powershell
powershell.exe -NoProfile -File .\kubernetes\demo-load-balancing.ps1 -Requests 20
```

The script parameters are `-Requests` (default `20`) and `-Namespace` (default `incident-response`). The namespace can also be supplied explicitly:

```powershell
powershell.exe -NoProfile -File .\kubernetes\demo-load-balancing.ps1 -Requests 20 -Namespace incident-response
```

Each request must launch an independent `curl` process via `kubectl exec deployment/api-gateway`, addressing the Service DNS URL above. Do not replace this with direct Pod IP requests or a port-forward to incident-service. A Service port-forward selects a single pod and therefore does not test Service backend distribution.[3]

### Interpret the result carefully

Kubernetes Service forwarding is connection-level networking, not a per-request Layer 7 HTTP load balancer. A persistent HTTP connection can keep sending requests to the same backend even with `sessionAffinity: None`. Separate curl processes avoid reusing one connection across the sample. Backend selection depends on the cluster networking implementation; strict round-robin, alternating hostnames, and equal response counts are not guarantees.[2]

The bounded sample passes the demonstration only if successful, valid responses contain **at least two unique actual hostnames** matching the current incident pods. Report measured counts, not a fabricated split. Twenty requests are a probabilistic demonstration, not a mathematical guarantee that every backend will appear.

If only one hostname appears, the sample has not demonstrated multi-pod distribution. Check readiness, EndpointSlices, selector, affinity, and that each request really uses a new curl process against Service DNS. After checking, a larger but still bounded sample may help; do not claim success from the replica count alone. This is not a throughput benchmark or proof of equal production traffic.

## 3. Kafka: inspect partition assignments, not HTTP traffic

Use these read-only commands in the same dedicated PowerShell session:

```powershell
kubectl -n incident-response exec kafka-0 -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe --topic service-events
kubectl -n incident-response exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe --group incident-processors --members --verbose
kubectl -n incident-response exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe --group incident-processors --state
```

Kafka's official 4.1 operations documentation supports both `--members --verbose` (active members and assigned partitions) and `--state` (group state, strategy, and member count). Run the latter as a separate description command.[4]

For a stable group with the intended two consumers and three partitions, an ordinary balanced assignment is typically two partitions on one member and one on the other. Within this consumer group, each partition has one active owner; additional consumers beyond the partition count would have no partitions to consume. A membership change or failure triggers a rebalance, so ownership is not permanent. Separate consumer groups consume independent streams with their own group progress rather than splitting this group's assignments.[6]

Read the actual member and partition rows before stating who owns what. Compare the reported member host addresses with current incident Pod IPs from `kubectl get pods -o wide`; do not assert which hostname owns which partition without that evidence. During a rollout or rebalance, transient membership may differ from the steady-state target. Inspect again after it settles; do not initiate scaling to demonstrate it.

Kafka preserves ordering within a partition, not a total order across all topic partitions.[7] The inspected producer sends `template.send(topic, event.serviceName(), event)`. With the configured default partitioning, the key's hash influences partition selection. Repeated events sharing a `serviceName` can concentrate on one partition; different names can also map to the same partition. Partition ownership therefore does not imply equal event counts or processing effort.[5]

The `/api/instance` requests do not publish Kafka events or determine partition assignments. HTTP response counts cannot prove Kafka work distribution, and Kafka membership cannot prove HTTP Service distribution. These inspection commands require no new events, offset resets, group changes, or provider calls.

## Evidence checklist

Record real results separately after the endpoint, script, and deployment are verified:

- Dedicated context/namespace, deployed incident image, and two Ready incident pods.
- Live Service affinity and matching ready EndpointSlice backends.
- Requested/completed HTTP sample size, actual hostnames and measured counts, and whether at least two unique hostnames were observed.
- Actual topic partition count, stable group state/member count, and verbose member assignments, mapping hosts only when supported by current Pod IPs.

This document is the procedure and expected interpretation, not an execution transcript. The completed parent-run measurements, test results, image rollout, and native packaging caveat are recorded in [Stage 15 verification](STAGE15_VERIFICATION.md).

## Sources

[1] https://kubernetes.io/docs/concepts/services-networking/service
[2] https://kubernetes.io/docs/reference/networking/virtual-ips
[3] https://kubernetes.io/docs/reference/kubectl/generated/kubectl_port-forward
[4] https://kafka.apache.org/41/operations/basic-kafka-operations
[5] https://kafka.apache.org/41/configuration/producer-configs
[6] https://kafka.apache.org/41/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html
[7] https://kafka.apache.org/41/getting-started/introduction
[8] https://kubernetes.io/docs/concepts/workloads/pods/pod-hostname
