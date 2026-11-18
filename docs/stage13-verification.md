# Stage 13 verification

Verified on the Windows Docker Desktop engine using the existing checkout, Java 21, and pinned project framework versions. All values below are observed synthetic test artifacts, not example output.

## Changes and isolation

- A shared multi-stage Dockerfile builds each of the six Maven modules through `MODULE`.
- Compose starts PostgreSQL, Redis, Kafka, topic initialization, and all six applications.
- Internal addresses use Compose service DNS. Host debug ports are loopback-only.
- Runtime images use a non-root JRE, explicit executable health helper, and no baked API key.
- Application containers have read-only roots, writable `/tmp`, dropped capabilities, and no-new-privileges.
- Existing native applications and the `incident-response-platform` infrastructure were left running; no existing database/cache was reset.
- Verification used the separate project `incident-response-stage13`: HTTP host ports 18080–18085, PostgreSQL 15433, Kafka 19092, Redis 16379. Its named volumes and synthetic data remain available for inspection.
- The provider was configured externally by the user in ignored `.env`. A separate ignored verification env file supplied isolated database credentials and host ports. Neither file is a source/build artifact.

## Build and automated results

Actual commands executed:

```powershell
.\mvnw.cmd -B -ntp test
# The verification env filename is local; it contains no committed credentials.
docker compose --project-name incident-response-stage13 --env-file .env.stage13-verification-d1f20093 build
docker compose --project-name incident-response-stage13 --env-file .env --env-file .env.stage13-verification-d1f20093 up --build -d --wait --wait-timeout 300
```

- Six application images built successfully from source, without host target/JAR dependencies.
- Nine long-running containers reported healthy; `kafka-init` exited successfully with code 0.
- Full native Maven reactor: **529 passed, 0 failures/errors/skips** (incident 142, event 55, simulator 40, diagnostics 159, AI 103, gateway 30).
- Python packaging/Compose contracts: **10 passed**.
- Native Windows PowerShell/Pester smoke contracts: **21 passed**, including no paid calls by default and no POST retry after failure.
- All 56 PowerShell blocks in the root README and both PowerShell script files parsed successfully.
- Each built image was exercised with network disabled, read-only root, tmpfs `/tmp`, dropped capabilities, and non-root UID 10001. The JAR/health helper were readable/executable, `/app` was unwritable, and Maven/javac were absent.
- A real one-off AI container with an empty key exited with code 2 and a safe configuration message before Java startup; no provider was invoked.
- `git diff --check` passed. Real `.env` and the verification override are ignored by Git; Docker context is allowlisted and excludes them.

## Complete live smoke

Actual command (one simulation POST, then one explicit analysis POST; no automatic write retries):

```powershell
& .\scripts\smoke-docker.ps1 -Gateway 'http://127.0.0.1:18081' -ProjectName incident-response-stage13 -EnvFile @('.env','.env.stage13-verification-d1f20093') -IncludeAi
```

Observed results:

- Scenario: `DATABASE_FAILURE`; root service: `order-service`.
- Trace: `b402b724-ed1e-456e-8f33-249b6a6b564d`.
- Five simulator acknowledgements, five matching Kafka records, five matching persisted REST-history rows, and five matching Redis correlation sorted-set members.
- New incident: `2626a507-cef6-439a-b72c-62bd313f63e1`, `OPEN`, errorCount 5.
- Event IDs:
  - `98246d42-a43d-48f5-8cca-8c23c6d04139`
  - `9d0e356a-72f2-4d62-a866-66303d36c8b6`
  - `7ecf4b0f-a943-4303-a243-63a489ca9d62`
  - `b14fcfd6-a770-429e-bcdd-fe775577f655`
  - `7ebbcf6d-b49b-479a-9a73-0c957b2c5d39`
- Kafka was inspected using a separate diagnostic consumer group with offset commits disabled, never the application's consumer group.
- Real provider-backed analysis succeeded with nonempty probable cause/recommendations, valid confidence/response schema, and captured evidence references:
  - E1: `get_incident`
  - E2: `get_related_events`
  - E3: `get_service_health`
  - E4: `get_recent_errors`
- The exact incident and its five persisted event rows were read back again after analysis; incident status/count remained unchanged.

The first script attempt stopped before any simulation/provider call because an unknown service correctly returns HTTP 404 for its history. The script now uses known-service listing for preflight and polls for incident visibility before reading history. A separate regression handles Linux nanosecond Kafka timestamps versus the database's microsecond truncation, without rounding away real mismatches. Application APIs and detection logic were not changed.

## Boundaries

Successful captured evidence does not prove that every sentence of model prose is semantically correct. One analysis POST may contain multiple bounded provider rounds. The normal automated suite makes no real provider requests. Redis correlation membership is transient and may expire after verification; persisted events/incidents and Kafka data are retained. The incident DTO exposes no direct event FK or traceId, so linkage is established through clean-run isolation, service/count/creation time, and observed event identities rather than inventing an API field.

The complete script requires a clean fixed-service history and must not simply be rerun against this retained incident. Use a new isolated project for another creation smoke, or inspect the existing incident through GET. See the README Stage 13 section for normal startup, port overrides, credential prerequisites, regression commands, and non-destructive shutdown. No commits, pushes, framework upgrades, future stages, or remediation were performed.
