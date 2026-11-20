# Docker operation and isolated demos

The default Compose project is `incident-response-platform`. It builds six applications from this checkout and runs PostgreSQL, Kafka, Redis, Prometheus, Grafana and a one-shot Kafka topic initializer. Run from the repository root in Windows PowerShell with Docker Desktop running Linux containers.

## First setup

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
notepad.exe .env
```

Set `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_PORT`, `OPENAI_API_KEY`, `OPENAI_MODEL`, and `GRAFANA_ADMIN_PASSWORD` locally. The template supplies nonsecret local database names/ports, but leaves passwords and provider selection blank. Use a compatible Chat Completions model supporting tool calls. For a different compatible provider, set `SPRING_AI_OPENAI_CHAT_BASE_URL`; the default is `https://api.openai.com/v1`.

The full default stack requires the AI key/model even if a subsequent smoke omits analysis; the AI container deliberately rejects empty settings. Detection itself does not use a model. [Native development](local-development.md) also supports starting the core pipeline without the AI application.

Never commit `.env`, keys, passwords, kubeconfig or Secret values. Only the AI container receives the provider key, at runtime, not as a build argument. Startup/probes/scrapes do not call the provider. An analysis POST sends diagnostic data to that provider and can incur charges.

Reuse existing PostgreSQL credentials when retaining a data volume. Changing `.env` does not reinitialize the database or reset an existing Grafana account. Do not delete volumes or repair Flyway to hide configuration problems. Shell environment overrides env-file values; use a fresh control terminal or deliberately remove stale overrides.

## Build and start

```powershell
docker compose config --quiet
docker compose up --build -d --wait --wait-timeout 300
if ($LASTEXITCODE -ne 0) { throw 'Stack startup failed; inspect the selected project before any smoke.' }
docker compose ps --all
```

Expect eleven long-running healthy containers and `kafka-init` exited with code 0. Readiness is not successful model authorization or proof of the entire business flow. A cold build downloads base images and Maven dependencies; no five-minute fresh-install guarantee is made.

The shared multi-stage Dockerfile uses Java 21, an allowlisted source context, a non-root runtime user, and only the executable application JAR in its final stage. Compose application roots are read-only, with writable bounded `/tmp`, dropped capabilities and bounded logs. Image assembly deliberately skips tests; run the [test commands](../README.md#testing-strategy) separately.

## Ports and internal networking

All published ports bind to host `127.0.0.1`. Internal container addresses remain fixed when host ports change.

| Host-port variable | Template/default | Container port |
| --- | --- | --- |
| `INCIDENT_SERVICE_PORT` | 8080 | 8080 |
| `GATEWAY_PORT` | 8081 | 8081 |
| `EVENT_SERVICE_PORT` | 8082 | 8082 |
| `SIMULATOR_PORT` | 8083 | 8083 |
| `DIAGNOSTICS_MCP_PORT` | 8084 | 8084 |
| `AI_ANALYSIS_PORT` | 8085 | 8085 |
| `DB_PORT` | 5433 in template; required | 5432 |
| `KAFKA_PORT` | 9092 | 9092, external listener |
| `REDIS_PORT` | 6379 | 6379 |
| `PROMETHEUS_PORT` | 9090 | 9090 |
| `GRAFANA_PORT` | 3000 | 3000 |

Applications use `postgres:5432`, `redis:6379`, and the Kafka INTERNAL listener `kafka:19092`. Native host clients use the published Kafka listener and its matching advertised host/port. The simulator calls `event-service:8082`; diagnostics calls `incident-service:8080`; AI calls `diagnostics-mcp-server:8084`. `localhost` inside one container is not another service.

Do not start native applications on occupied default HTTP ports. Do not point an additional incident consumer at another project's broker/database while describing the run as isolated.

## Fresh isolated demo alongside an existing stack

Choose a new project name for new PostgreSQL/Kafka volumes and change every conflicting host port. Check the proposed ports before startup; the following are examples, not a claim that they are free on your machine. Keep the existing private `.env`; the overrides below contain no credentials.

```powershell
$project = 'incident-response-demo-' + [Guid]::NewGuid().ToString('N')
$env:DB_PORT = '25433'
$env:KAFKA_PORT = '29092'
$env:REDIS_PORT = '26379'
$env:INCIDENT_SERVICE_PORT = '28080'
$env:GATEWAY_PORT = '28081'
$env:EVENT_SERVICE_PORT = '28082'
$env:SIMULATOR_PORT = '28083'
$env:DIAGNOSTICS_MCP_PORT = '28084'
$env:AI_ANALYSIS_PORT = '28085'
$env:PROMETHEUS_PORT = '19090'
$env:GRAFANA_PORT = '13000'
$dc = @('--project-name', $project, '--env-file', '.env')
docker compose @dc config --quiet
docker compose @dc up --build -d --wait --wait-timeout 300
if ($LASTEXITCODE -ne 0) { throw 'Isolated startup failed; do not run smoke.' }
docker compose @dc ps --all
```

Reuse `$dc` and the same environment in this control terminal. In a new terminal, re-establish the project, env files and host-port overrides; variables do not cross terminal boundaries. For repeated work, save the nonsecret overrides in an ignored `.env.demo-local` and use both files in order:

```powershell
# After deliberately creating .env.demo-local with the intended port overrides:
$project = Read-Host 'Exact existing demo project name'
$files = @('.env', '.env.demo-local')
$dc = @('--project-name', $project, '--env-file', $files[0], '--env-file', $files[1])
docker compose @dc ps --all
docker compose @dc port api-gateway 8081
```

Use that actual gateway binding and matching files for the smoke. `--project-name` alone isolates networks/volumes but does not avoid host-port conflicts.

## Synthetic smoke: choose one mode

Start with no `order-service` incidents, event history or correlation members in the selected deployment. The script refuses retained state rather than resetting it or falsely claiming a new creation. Do not run scenario producers concurrently. It also verifies that the gateway belongs to the selected Compose project.

For the alternate-port project created above, use pipeline-only verification:

```powershell
powershell.exe -NoProfile -File .\scripts\smoke-docker.ps1 -ProjectName $project -EnvFile '.env' -Gateway 'http://127.0.0.1:28081'
```

Or, instead, opt into the complete analysis path on that fresh project:

```powershell
powershell.exe -NoProfile -File .\scripts\smoke-docker.ps1 -ProjectName $project -EnvFile '.env' -Gateway 'http://127.0.0.1:28081' -IncludeAi
```

For the two-env-file variant, invoke `& .\scripts\smoke-docker.ps1 -ProjectName $project -EnvFile $files -Gateway 'http://127.0.0.1:28081' -IncludeAi` from the same PowerShell session. The in-process invocation preserves the env-file array. Use the exact selected project/files/port; do not mix examples.

Do not run both modes consecutively and expect another new incident. `-IncludeAi` authorizes one analysis HTTP POST, potentially several bounded model rounds; it may incur charges and is not automatically retried. A pipeline-only run proves no model result.

The smoke submits one database-failure scenario and compares exact IDs/trace through persisted REST history, a separate Kafka diagnostic consumer, Redis window members, incident creation and derived health. AI mode additionally checks captured MCP evidence and analysis structure. Incident DTOs have no triggering-event foreign key; isolation, identity, service/count and timing establish the observed scenario, not an invented response field. This does not prove every sentence of model reasoning.

Match `-ExpectedEventCount`/`-ExpectedThreshold` to nondefault settings, and pass `-KafkaTopic` if `KAFKA_EVENTS_TOPIC` differs from `service-events`. Polling, Kafka scans and subprocesses have finite limits. Expired evidence, incomplete scans, wrong IDs, unhealthy domain state or provider errors fail rather than silently skipping. Preserve partial progress and investigate before deliberately running another write.

## Inspect, stop and retain evidence

Default-project examples:

```powershell
docker compose ps --all
docker compose logs --tail 100 incident-service
docker compose logs --tail 100 ai-analysis-service
docker compose down
```

For a selected isolated project, use `docker compose @dc ...` consistently instead. `down` removes that project's containers/network but retains its PostgreSQL, Kafka, Prometheus and Grafana named volumes. Redis is deliberately ephemeral. Do not add `-v`/`--volumes` as routine cleanup, delete retained audit data, or stop another project.

Do not share unrestricted rendered Compose config, container environment/inspect output, raw credential-bearing logs or local env files. Readiness conditions order startup, not ongoing dependency supervision. [Diagnostic read resilience](../diagnostics-mcp-server/README.md) handles the implemented synchronous read boundaries; there is no blanket gateway/write/provider retry.
