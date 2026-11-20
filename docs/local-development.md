# Native Windows development

Run Java applications in Windows PowerShell and infrastructure in Docker Desktop. This is an alternative to the [complete Docker deployment](docker.md), not an additional stack to start on the same ports.

## Prerequisites

- JDK 21, including `javac`.
- Docker Desktop running Linux containers.
- Windows PowerShell and the repository's `mvnw.cmd`; a separate Maven installation is unnecessary.
- Six application terminals plus one free control/request terminal for the complete platform. Detection alone needs only incident-service, event-service and their infrastructure; add the simulator to generate scenarios.

Run commands from the repository root. Replace `C:\path\to\incident-response` below with your actual checkout. Environment variables belong to the terminal where they are set; they do not carry into another terminal. Spring Boot does not automatically load `.env`.

```powershell
Set-Location 'C:\path\to\incident-response'
java -version
javac -version
.\mvnw.cmd -version
docker version
docker compose version
```

Java and javac must report version 21. If necessary, select the installed JDK in each launching terminal:

```powershell
$env:JAVA_HOME = Read-Host 'Full Windows path to your JDK 21 installation'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Do not expose an unauthenticated Docker TCP endpoint or introduce a WSL Docker proxy. The native Testcontainers suites use Docker Desktop directly.

## Control terminal: infrastructure and topic

Keep existing credentials if a PostgreSQL volume already exists. These commands start only PostgreSQL, Kafka and Redis, then run the topic initializer; they do not require a model key or Grafana password.

```powershell
if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
}
notepad.exe .env
```

Set `DB_NAME`, `DB_USERNAME`, a private nonempty `DB_PASSWORD`, and `DB_PORT` locally. The examples below assume `incident_response`, `incident_app`, and host port `5433`. Use the same actual values in the incident-service terminal. Kafka and Redis default to host ports `9092` and `6379`.

```powershell
docker compose config --quiet
docker compose up -d --wait --wait-timeout 120 postgres kafka redis
if ($LASTEXITCODE -ne 0) { throw 'Infrastructure is not ready.' }
docker compose run --rm kafka-init
if ($LASTEXITCODE -ne 0) { throw 'Kafka topic initialization failed.' }
docker compose ps --all
```

PostgreSQL, Kafka and Redis should be healthy. The initializer creates `service-events` with three partitions and replication factor one and then exits successfully. Repeating initialization is safe. Do not change the Kafka cluster ID while reusing its data volume.

Compose reads `.env`; native Java does not. Shell variables override Compose's env-file values. Use `config --quiet`, not unrestricted rendered configuration, to avoid printing credentials.

## Terminal 1: incident-service

Supply the same database credentials used by the Docker database, not credentials for a separate Windows PostgreSQL installation.

```powershell
Set-Location 'C:\path\to\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8080'
$env:DB_HOST = '127.0.0.1'
$env:DB_PORT = '5433'
$env:DB_NAME = 'incident_response'
$credentials = Get-Credential -UserName 'incident_app' -Message 'Existing Docker database credentials'
$env:DB_USERNAME = $credentials.UserName
$env:DB_PASSWORD = $credentials.GetNetworkCredential().Password
Remove-Variable credentials
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:REDIS_HOST = '127.0.0.1'
$env:REDIS_PORT = '6379'
.\mvnw.cmd -pl incident-service spring-boot:run
```

Flyway applies the migrations; Hibernate uses `ddl-auto=validate`. Do not create tables manually or disable validation. Correlation defaults to five ERROR/CRITICAL events in 60 seconds. Optional overrides are `INCIDENT_CORRELATION_THRESHOLD`, `INCIDENT_CORRELATION_WINDOW_SECONDS`, `INCIDENT_HEALTH_DEGRADED_THRESHOLD` and `INCIDENT_HEALTH_DOWN_THRESHOLD`; see [the environment reference](../.env.example).

## Terminal 2: event-service

```powershell
Set-Location 'C:\path\to\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8082'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
.\mvnw.cmd -pl event-service spring-boot:run
```

No database or Redis credentials are required. If changing `KAFKA_EVENTS_TOPIC`, set the same topic for both services and the initializer.

## Terminal 3: service-simulator

```powershell
Set-Location 'C:\path\to\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8083'
$env:EVENT_SERVICE_BASE_URL = 'http://127.0.0.1:8082'
.\mvnw.cmd -pl service-simulator spring-boot:run
```

The simulator needs only event-service HTTP access. Its default outbound path is direct to event-service. An optional native configuration can set `EVENT_SERVICE_BASE_URL` to the gateway origin, `http://127.0.0.1:8081`, after the gateway is available; the existing `/api/events/**` route then handles those submissions. This is not the default Compose/Kubernetes wiring and needs no routing code change.

`SIMULATOR_FAILURE_EVENT_COUNT` defaults to five and is independent of the incident threshold. `SIMULATOR_CASCADE_DELAY` defaults to `250ms`; HTTP connect/read timeouts default to `2s`/`15s`. The simulator does not automatically retry writes.

## Terminal 4: diagnostics-mcp-server

```powershell
Set-Location 'C:\path\to\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8084'
$env:INCIDENT_SERVICE_BASE_URL = 'http://127.0.0.1:8080'
.\mvnw.cmd -pl diagnostics-mcp-server spring-boot:run
```

Use an origin, not a URL ending in `/api/incidents`. MCP is at `/mcp`; it is not a collection of REST routes named after the tools. This service has no database/cache/broker access and needs no provider key. See [manual MCP requests](api-examples.md#manual-mcp-requests).

## Terminal 5: ai-analysis-service

Use a Chat Completions model supporting function calling that is available to your provider account. Starting the service does not call the provider; an analysis request can transmit operational evidence and incur charges.

```powershell
Set-Location 'C:\path\to\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8085'
$env:DIAGNOSTICS_MCP_BASE_URL = 'http://127.0.0.1:8084'
$env:OPENAI_MODEL = Read-Host 'Provider model ID available to your account'
$secret = Read-Host 'Provider API key (hidden input)' -AsSecureString
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
Remove-Variable secret
.\mvnw.cmd -pl ai-analysis-service spring-boot:run
```

For a compatible provider other than the default OpenAI endpoint, set `SPRING_AI_OPENAI_CHAT_BASE_URL` before startup; for example, `https://openrouter.ai/api/v1`. Do not append `/chat/completions`. Provider retries are disabled. Do not enable request-body/header wire logging to diagnose authentication.

## Terminal 6: api-gateway

All four destination origins are required. The gateway does not start the other applications and holds no database, broker, cache or provider credentials.

```powershell
Set-Location 'C:\path\to\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8081'
$env:GATEWAY_EVENT_SERVICE_URL = 'http://127.0.0.1:8082'
$env:GATEWAY_INCIDENT_SERVICE_URL = 'http://127.0.0.1:8080'
$env:GATEWAY_ANALYSIS_SERVICE_URL = 'http://127.0.0.1:8085'
$env:GATEWAY_SIMULATOR_URL = 'http://127.0.0.1:8083'
.\mvnw.cmd -pl api-gateway spring-boot:run
```

The gateway can start independently; a route to an unavailable destination still fails. Gateway Actuator UP is not a full dependency or provider check.

## Free terminal: inspect and exercise APIs

```powershell
$gateway = 'http://127.0.0.1:8081'
Invoke-RestMethod "$gateway/actuator/health"
Invoke-RestMethod "$gateway/api/incidents"
Invoke-RestMethod "$gateway/api/services"
```

Use [API examples](api-examples.md) for intentional writes and [the README demo](../README.md#five-minute-demo-warm-stack) for the full scenario. The Docker smoke script targets Compose containers and rejects a native gateway; do not use it as a native-only smoke command.

To inspect the Docker database in pgAdmin, register a separate server using host `localhost`, port `5433` (or your `DB_PORT`), database `incident_response`, and your Docker credentials. A Windows PostgreSQL server on port `5432` is a different database. The Docker image creates its database/login on first initialization; no manual database creation is needed.

## Optional live-provider test

This opt-in profile is not part of the ordinary test gate. Keep incident-service and diagnostics running with an existing incident. In terminal 5, stop the known AI foreground process with Ctrl+C, retaining its MCP/provider environment; the integration test starts its own private AI HTTP listener. If using a different free terminal, first repeat terminal 5's environment/key setup there.

```powershell
$env:MANUAL_ANALYSIS_INCIDENT_ID = Read-Host 'Existing incident UUID for an intentional live-provider test'
.\mvnw.cmd -pl ai-analysis-service -Pmanual-openai verify
```

The profile explicitly enables `LiveOpenAiAnalysisIT`, calls the real configured MCP server/provider and can incur charges. Do not run it in normal CI or treat it as a harmless connectivity check. Missing required settings fail rather than pretending a provider call succeeded. Resume the native AI application afterward only if you need it.

## Build, stop and retain data

Run the full automated suite from a free terminal with Docker Desktop available:

```powershell
.\mvnw.cmd -B -ntp '-Dmaven.compiler.showDeprecation=true' clean verify
```

Stop known foreground applications with Ctrl+C in their own terminals first if they were launched with `java -jar` from `target`: Windows can lock a running JAR and prevent `clean`. Do not kill unrelated processes. To test without deleting/repackaging a running JAR, use `.\mvnw.cmd test` instead; this is not the same clean-build gate.

From the infrastructure control terminal, using the same project selection:

```powershell
docker compose down
Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
```

Remove sensitive variables in the terminals where they were actually set, or close those terminals. `down` retains named PostgreSQL/Kafka volumes; Redis's rolling state is intentionally ephemeral. Never add `--volumes` as routine troubleshooting. Existing database/Grafana credentials are not changed by editing `.env`. Back up and investigate a Flyway validation failure rather than automatically repairing history or resetting data.
