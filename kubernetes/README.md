# Stage 14: local Kubernetes deployment

Deploy the existing applications; no Java/framework or detection changes are needed. Built-in Kustomize (`kubectl -k`) assembles the manifests. This is a local scaling demonstration, not a production HA/security design.

## Layout and replicas

- `base/`: six application Deployments, six internal ClusterIP Services, nonsecret ConfigMap configuration, external Secret references, resource requests/limits, and Actuator probes.
- `infra/infrastructure.json`: standard Kubernetes JSON resources for one PostgreSQL StatefulSet/PVC, one Kafka StatefulSet/PVC, ephemeral Redis, their Services, and an idempotent topic-init Job.
- `namespace.yaml`, `kustomization.yaml`: isolated `incident-response` namespace and complete stack.
- `database.env.example`: blank database-password template, not a Kubernetes Secret.
- `kind-cgroup-v1.yaml`: optional compatibility configuration for old local Docker hosts only, not an application manifest or production recommendation.

Application replicas start at incident-service **2**, event-service **2**, api-gateway **1**, diagnostics-mcp-server **1**, ai-analysis-service **1**, service-simulator **1**. Both incident consumers share PostgreSQL/Redis and the same Kafka group, distributing the topic's three partitions. More replicas do not increase partition-level parallelism beyond the partition count. Diagnostics remains one replica because its MCP sessions are in-memory; do not blindly scale it behind round-robin routing.

All inter-application destinations use Kubernetes Service DNS (`incident-service`, `event-service`, `diagnostics-mcp-server`, etc.) in the same namespace. PostgreSQL is `postgres:5432`, Redis `redis:6379`, and Kafka `kafka:19092`. Kafka advertises its reachable Kubernetes DNS name, not a host-only address. No application host ports, external broker, external database, or Compose network is required. This deployment does not use or modify your existing native/Compose data.

## 1. Select a local cluster explicitly

Use Docker Desktop Kubernetes, Kind, or Minikube. Ensure `kubectl` is installed and a node is Ready before applying workloads. Do not apply these resources to an unknown or production context.

```powershell
Set-Location 'C:\Users\Shiva Sravani\Desktop\incident-response'
kubectl config current-context
kubectl get nodes
```

For the verified dedicated Kind cluster, each new PowerShell terminal should select its own kubeconfig explicitly:

```powershell
$env:KUBECONFIG = Join-Path $env:USERPROFILE '.kube\incident-response-stage14.yaml'
kubectl config current-context
kubectl get nodes
```

Expected context: `kind-incident-response-stage14`. Keep using this configuration and namespace; shell variables do not cross terminals. The dedicated kubeconfig is outside the repository and does not overwrite the user's default kubeconfig. Treat it as a credential and never commit or share its contents.

### Creating your own Kind cluster

Install Kind using its official instructions: https://kind.sigs.k8s.io/docs/user/quick-start/ . Verification used Kind v0.33.0 with the following Kubernetes 1.36.4 image (matching the installed kubectl minor version):

```powershell
$env:KUBECONFIG = Join-Path $env:USERPROFILE '.kube\incident-response-stage14.yaml'
kind create cluster --name incident-response-stage14 --kubeconfig $env:KUBECONFIG --image 'kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed'
```

Do not recreate an existing cluster or overwrite a pre-existing kubeconfig just to follow these instructions. If Kind was installed outside PATH, invoke its actual executable path.

The verification host uses cgroup v1. Kubernetes 1.36 refuses this by default; the first bootstrap failed before application deployment. A local-only retry succeeded with the same command plus `--config .\kubernetes\kind-cgroup-v1.yaml`. Prefer a current cgroup-v2 Docker engine. The compatibility patch sets `failCgroupV1: false` only for this development node; it is deprecated and not guaranteed on future Kubernetes releases. It does not alter Docker, restart your host, or change application manifests. See the Kubernetes cgroup guidance: https://kubernetes.io/blog/2026/10/06/kubernetes-cgroups-v2-shift/ .

## 2. Build and make images available to the nodes

Reuse the Stage 13 Dockerfile; image builds deliberately skip tests, so run regression checks separately.

```powershell
$apps = @('api-gateway','event-service','incident-service','diagnostics-mcp-server','ai-analysis-service','service-simulator')
foreach ($app in $apps) {
    docker build --build-arg "MODULE=$app" --tag "incident-response/${app}:stage14" .
    if ($LASTEXITCODE -ne 0) { throw "Image build failed: $app" }
}
```

For Kind:

```powershell
$images = @($apps | ForEach-Object { "incident-response/${_}:stage14" })
kind load docker-image @images --name incident-response-stage14
```

For Minikube, load each image with `minikube image load IMAGE_NAME`. For Docker Desktop Kubernetes, confirm its nodes can use these local images; otherwise use a registry reachable by the cluster and change the image references with a Kustomize image override. Manifests use `IfNotPresent`, not `Always`, so local versioned image tags can be used without an external registry. Rebuilding the same tag requires loading it again and restarting the applicable Deployments; do not assume already running pods updated themselves.

Infrastructure images can be pulled by Kubernetes. Loading Docker Desktop's partial multi-platform infrastructure images with Kind can fail with a missing content digest; this is an image-import issue, not an application configuration problem. Prefer letting Kubernetes pull them, or export/load an archive for the actual node platform. Do not change framework/image versions merely to hide a loader error.

## 3. Create Secrets locally, never in Git

Apply the namespace before creating namespaced Secrets:

```powershell
kubectl apply -f .\kubernetes\namespace.yaml
```

For a NEW isolated database, create project-root `.env.kubernetes-db` from the template and set a private password locally. Do not overwrite an existing local file or change credentials after a PostgreSQL PVC has been initialized.

```powershell
if (-not (Test-Path -LiteralPath '.env.kubernetes-db')) {
    Copy-Item .\kubernetes\database.env.example .env.kubernetes-db
}
notepad.exe .env.kubernetes-db
kubectl -n incident-response create secret generic incident-database --from-env-file=.env.kubernetes-db
```

Required file keys are `DB_USERNAME` and `DB_PASSWORD`; the database name is nonsecret configuration. The local file is already excluded by the repository's `.env.*` ignore rule. PostgreSQL and incident-service reference this Secret; the other applications do not receive its credentials.

Use a provider-only ignored local env file containing `OPENAI_API_KEY`, `OPENAI_MODEL`, and optionally `SPRING_AI_OPENAI_CHAT_BASE_URL`. The current local `.env` can be used if it contains your provider settings; never paste the key into commands or chat. For OpenRouter the base URL is `https://openrouter.ai/api/v1`; otherwise use the selected provider's compatible endpoint. The default nonsecret configuration targets `https://api.openai.com/v1`.

```powershell
kubectl -n incident-response create secret generic analysis-provider --from-env-file=.env
kubectl -n incident-response get secrets
```

Application manifests reference individual Secret keys; only ai-analysis-service receives the provider settings. No Secret values or base64-encoded Secret manifests belong in Git. Kubernetes Secret base64 encoding is not encryption, and cluster administrators can read Secrets. Do not use `kubectl get secret -o yaml`, environment dumps, or commit kubeconfig files. For an existing Secret, update it deliberately with a dry-run/create-to-apply pipeline, then restart the affected workloads; do not delete data/PVCs to fix credentials. Startup/probes do not call the real provider, so a healthy AI pod does not prove provider authentication or model availability.

## 4. Apply and wait for health

```powershell
kubectl apply -k .\kubernetes
kubectl -n incident-response get pods
kubectl -n incident-response get services
kubectl -n incident-response get pvc
kubectl -n incident-response wait --for=condition=complete job/kafka-init --timeout=300s
foreach ($app in $apps) {
    kubectl -n incident-response rollout status "deployment/$app" --timeout=300s
    if ($LASTEXITCODE -ne 0) { throw "Deployment not healthy: $app" }
}
kubectl -n incident-response get deployments
```

Expected application READY counts: incident-service `2/2`, event-service `2/2`, and each other application `1/1`. PostgreSQL and Kafka each have one StatefulSet pod; Redis has one Deployment pod. The topic Job should complete rather than run forever. Claims preserve PostgreSQL/Kafka state across pod replacement, but deleting the namespace/PVCs or the Kind node can destroy development data. Redis retains its earlier ephemeral rolling-window policy.

Kubernetes does not implement Compose `depends_on`. Kafka topic initialization is an explicit Job; event/incident pods wait for the topic before Java startup. Services resolve internally without a discovery server. Application startup/liveness/readiness use Actuator; liveness excludes external dependencies to avoid restart storms during dependency outages, while incident readiness includes its database/Redis checks. Startup probes allow JVM warmup before liveness checks. Readiness removes unready pods from Service endpoints; it is not proof of every business operation or model call.

Resource requests/limits and `/tmp` volumes are set for local JVM workloads; tune based on measurements before production. Application containers run non-root with read-only roots, dropped capabilities, no privilege escalation, no mounted service-account token, and RuntimeDefault seccomp. This single-node demonstration is not PostgreSQL/Kafka HA, and there is no HPA, ingress controller, authentication/TLS, or new retry policy.

## 5. Inspect logs and Service discovery

```powershell
kubectl -n incident-response logs deployment/api-gateway --tail=100
kubectl -n incident-response logs -l app.kubernetes.io/name=incident-service --all-containers=true --prefix --tail=100
kubectl -n incident-response logs job/kafka-init --tail=100
kubectl -n incident-response get endpointslices
kubectl -n incident-response get events --field-selector type=Warning --sort-by=.lastTimestamp
```

Use the specific failing pod and `kubectl describe pod POD_NAME` for scheduling, probe, missing-Secret, or image-pull failures. Do not publish raw credential-bearing logs/describe output. Missing Secret keys should be fixed locally, not replaced by dummy keys just to make a pod appear healthy.

## 6. Optional host access (no model call)

In one dedicated PowerShell terminal, with the same kubeconfig selected:

```powershell
kubectl -n incident-response port-forward service/api-gateway 18091:8081 --address 127.0.0.1
```

Leave it running. In another terminal:

```powershell
Invoke-RestMethod 'http://127.0.0.1:18091/actuator/health'
Invoke-RestMethod 'http://127.0.0.1:18091/api/incidents'
```

These are read-only checks and are not AI analysis requests. The Kubernetes database is separate, so UUIDs from the native/Compose stacks need not exist here. An analysis POST may invoke a paid provider; Stage 14 verification stops at healthy deployments and Service discovery, with no simulation, provider call, or remediation.

## Regression and verification record

With Docker Desktop running, the existing native Maven suite remains the regression gate:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\mvnw.cmd -B -ntp test
```

The offline manifest contracts need the kubectl client and Python 3 with PyYAML (the WSL system Python used during verification already supplied it). No cluster, user env-file reads, or provider calls are needed for these contracts:

```powershell
py -m unittest discover -s tests/packaging -p 'test_kubernetes*.py' -v
```

The parent verified the actual deployments, every application pod's two probe endpoints, Service DNS, and ready EndpointSlice counts. See [VERIFICATION.md](VERIFICATION.md) for the execution record, replica counts, test results, isolation, and resolved local bootstrap issues. A server-side dry run alone is not evidence that pods became healthy.

## Cleanup boundaries

Leave a verified cluster running for inspection if desired. Stop port-forward with Ctrl+C in its own terminal. Do not delete the namespace/PVCs/Kind cluster as a troubleshooting shortcut; those actions remove this deployment's data. Existing Compose projects/native applications remain independent. Never commit or push automatically.
