# Stage 14 verification record

This records real local Kubernetes execution, not proposed/sample output.

## Environment and scope

- Isolated Kind cluster/context: `incident-response-stage14` / `kind-incident-response-stage14`.
- Kubernetes node: v1.36.4; Kind v0.33.0; native Windows kubectl v1.36.1.
- Dedicated kubeconfig: `C:\Users\Shiva Sravani\.kube\incident-response-stage14.yaml`, outside Git and separate from the default kubeconfig.
- Namespace: `incident-response`.
- Six Stage 13 application images rebuilt from source and loaded with tags `incident-response/APP_NAME:stage14`.
- Database/provider Secrets created at runtime from ignored local env files. No Secret values/manifests or kubeconfig were committed.
- Existing Compose projects remained running with their original nine/three-container counts. No original volumes, caches, application processes, or configuration were reset.

## Observed deployment health

| Deployment | Ready / desired | App container restarts |
| --- | --- | --- |
| incident-service | 2 / 2 | 0 |
| event-service | 2 / 2 | 0 |
| api-gateway | 1 / 1 | 0 |
| diagnostics-mcp-server | 1 / 1 | 0 |
| ai-analysis-service | 1 / 1 | 0 |
| service-simulator | 1 / 1 | 0 |

Each of the eight application pods returned `UP` from both `/actuator/health/liveness` and `/actuator/health/readiness`. All six application Service DNS names were additionally exercised from inside the gateway pod; each returned readiness `UP`. EndpointSlices contained the correct ready endpoint counts (two for incident/event, one for each other application).

PostgreSQL and Kafka StatefulSets were each 1/1 Ready, Redis 1/1 Ready, and both 1Gi persistent claims were Bound. The `kafka-init` Job completed 1/1 in 38 seconds. Early startup-probe connection refusals occurred while JVMs/broker warmed up; all probes subsequently succeeded without application restarts.

Actual commands included:

```powershell
$env:KUBECONFIG = Join-Path $env:USERPROFILE '.kube\incident-response-stage14.yaml'
kubectl apply --dry-run=server -k .\kubernetes
kubectl apply -k .\kubernetes
kubectl -n incident-response get deployments
kubectl -n incident-response get pods
kubectl -n incident-response get services
kubectl -n incident-response get pvc
kubectl -n incident-response get jobs
kubectl -n incident-response logs deployment/api-gateway --tail=2
```

`kubectl rollout status` succeeded for all six application Deployments. Server-side dry run accepted every assembled resource before deployment. Runtime probe/DNS/endpoint checks were performed through read-only kubectl calls; no analysis or simulator POST was issued.

## Automated verification

- Full native Windows Maven reactor: **529 tests passed; zero failures/errors/skips**. Normal tests did not invoke real model providers.
- Packaging suite: **18 tests passed**, including five new application Kubernetes contracts and three new infrastructure contracts, alongside the existing ten Docker/Compose contracts.
- No changes to Java source, POM/framework pins, migrations, detection rules, or provider retry policy.
- New source credential-pattern scan and `git diff --check` passed; `.env` and `.env.kubernetes-db` are ignored.
- See `README.md` here for complete setup, image loading, Secret references, apply/get/log commands, probe behavior, and optional port-forwarding.

## Resolved local bootstrap/import issues

The initial Kind bootstrap failed because this Docker engine uses cgroup v1. Kubernetes 1.36 defaults to rejecting cgroup v1. A retry with the isolated node's `failCgroupV1: false` compatibility config succeeded. This is a deprecated local-host fallback; prefer a cgroup-v2 engine. No host/Docker restart was performed.

Kind's attempt to import pre-existing partial multi-platform infrastructure images failed with a missing content digest. Kubernetes subsequently pulled the official PostgreSQL/Kafka/Redis images itself, and all infrastructure became healthy. The six freshly built application images loaded into Kind successfully. These resolved earlier attempts do not change the final healthy verdict; their logs were kept separate from successful health evidence.

## Deliberate boundaries

Verification stopped after deployment health and internal Service discovery, as requested. No new synthetic incidents, paid model requests, or remediation were performed. A healthy AI pod proves startup/readiness, not external provider authentication/availability. The namespace uses a separate empty application database; IDs from Compose/native databases are not shared. MCP remains one replica because sessions are process-local.

The local single-node cluster, namespace, workloads, Secrets, and PVCs were left running for inspection. No port-forward process was left running. This is not a production HA/authentication/TLS deployment. Deleting a namespace/PVC/Kind cluster can destroy its development data; do not use deletion as a configuration fix. No commits or pushes were made.
