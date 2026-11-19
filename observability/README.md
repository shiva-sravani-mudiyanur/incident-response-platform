# Stage 16 — Minimal observability

## Architecture and scope

```text
Spring Boot services
  → Micrometer (application counters/gauge/timers plus Boot JVM/HTTP metrics)
  → each service's /actuator/prometheus
  → Prometheus (pull every 15 seconds, store samples)
  → Grafana (query the provisioned Prometheus datasource)
```

No new service is inserted into the event-processing or analysis path. The six applications do not depend on Prometheus or Grafana. Detection thresholds, Kafka topics/groups/keys/acks, Redis keys/windows/deduplication, database migrations, MCP tools, model options/tool loop, gateway routes and REST DTOs are unchanged. Metrics scraping never calls the model or mutates incidents.

All six services add the Spring Boot-managed `io.micrometer:micrometer-registry-prometheus` dependency, without upgrading Spring Boot, Java, Spring AI, Spring Cloud or JUnit. Structured logging uses Boot's built-in Logstash JSON support rather than another logging dependency. The only Kubernetes source change is the common read-only Actuator allowlist; the existing cluster/images are not redeployed by this Compose verification. Existing stage14/stage15 images must be rebuilt through the existing image workflow before Kubernetes pods can expose the new registry/instrumentation.

## Startup — Windows PowerShell

Use one free control terminal. Keep any foreground Java/port-forward terminal running separately. Do not launch a second consumer against an existing Kafka broker while treating it as an isolated test.

1. Open the existing private `.env` locally. Do not overwrite it with the example if it already contains working database/provider settings.
2. Add `GRAFANA_ADMIN_USER=admin` and set a private `GRAFANA_ADMIN_PASSWORD`. Never paste passwords/API keys into chat or commit `.env`.
3. Ensure the existing database variables and provider key/model are supplied for a full six-application startup. Use `.env.example` as a variable reference, not as a replacement for existing credentials.
4. Select the intended Compose project and free host ports. The following default command uses `incident-response-platform`. If your full stack is in another project, include that project's explicit arguments instead.

```powershell
# Replace this path with your actual checkout directory.
Set-Location 'C:\path\to\incident-response'
notepad.exe .env
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
```

The full stack has eleven long-running healthy containers: six applications, PostgreSQL, Kafka, Redis, Prometheus and Grafana. `kafka-init` is a successful exited one-shot job, not a twelfth running server. Health does not establish real-provider availability.

For an existing full project, carry the same explicit project/env-file arguments into every command and every new terminal. For example, the separately retained Stage 16 verification project uses the exact argument array recorded in `docs/stage16-verification.md`. Do not reuse another project's incident UUID or assume an old `$dc` variable selects the intended stack.

If you need a separate fresh project, create a new ignored `.env.observability-local` containing your own private DB credentials and all host-port overrides. Use a different DB/Kafka/Redis publication and all six different HTTP ports; include `PROMETHEUS_PORT` and `GRAFANA_PORT`. Then use `--project-name` and both ordered `--env-file` arguments consistently. Do not delete/reset another project's volumes or resolve its incidents to make a smoke repeatable. Host-port overrides do not change internal Service DNS or scrape targets.

Grafana refuses an empty admin password at container startup; the AI container retains its existing empty-key/model guard. Infrastructure-only Compose commands do not require those credentials during configuration parsing. Grafana's admin settings initialize a new database only; an existing `grafana_data` volume retains its original login. Changing `.env` does not reset that account. Do not delete the volume just to change a password.

## URLs and Actuator access

Default host URLs:

| Component | URL |
| --- | --- |
| Prometheus | http://127.0.0.1:9090 |
| Prometheus target health | http://127.0.0.1:9090/targets |
| Grafana | http://127.0.0.1:3000 |
| Dashboard | http://127.0.0.1:3000/d/incident-response |

Grafana login: the configured `GRAFANA_ADMIN_USER` and your private password. The provisioned dashboard is also under **Dashboards → Incident Response → Incident Response — Minimal Observability**, and is configured as the home dashboard. No manual datasource creation or dashboard import is required.

Every Spring service exposes only these read-only management endpoints on its existing HTTP port:

- `/actuator/health` — sanitized availability, no dependency/configuration details.
- `/actuator/info` — nonsensitive info; an empty object is normal.
- `/actuator/metrics` — Micrometer meter names; `/actuator/metrics/<name>` shows measurements.
- `/actuator/prometheus` — Prometheus text/OpenMetrics export.

Access defaults to none; JMX exposure and `/actuator` discovery are disabled. `/actuator/env`, `/actuator/configprops`, `/actuator/beans`, `/actuator/heapdump`, `/actuator/loggers`, gateway administration and shutdown are not enabled. Metrics remain unauthenticated development endpoints containing operational data. Published ports bind host loopback; use authentication/TLS/network controls before exposing anything outside a trusted local network. This stage does not introduce a production security platform.

```powershell
# Default incident-service host port; substitute the selected project's binding.
curl.exe --fail http://127.0.0.1:8080/actuator/health
curl.exe --fail http://127.0.0.1:8080/actuator/metrics/incident.events.processed
curl.exe --fail http://127.0.0.1:8080/actuator/prometheus
```

The gateway's Actuator endpoints describe the gateway itself, not incident-service. Prometheus deliberately scrapes each service directly, not by adding new management proxy routes.

## Scrape/storage configuration

`prometheus/prometheus.yml` contains six named jobs, all with `/actuator/prometheus`:

| Job / container DNS | Internal port |
| --- | --- |
| incident-service | 8080 |
| api-gateway | 8081 |
| event-service | 8082 |
| service-simulator | 8083 |
| diagnostics-mcp-server | 8084 |
| ai-analysis-service | 8085 |

Global scrape/evaluation interval: 15 seconds. Scrape timeout: 5 seconds. Each application exports the fixed `application=${spring.application.name}` tag; Prometheus adds its fixed job/target identity. Configuration is mounted read-only. The Prometheus data volume persists samples with seven-day retention. For a scrape configuration edit, restart only the selected project's Prometheus container; HTTP lifecycle/admin APIs are not enabled.

Grafana files:

- `grafana/provisioning/datasources/datasource.yml`: default datasource `Prometheus`, stable UID `prometheus`, server-side proxy to `http://prometheus:9090`.
- `grafana/provisioning/dashboards/default.yml`: load dashboard JSON files into the Incident Response folder; rescan every 30 seconds, no UI overrides.
- `grafana/dashboards/incident-response.json`: one version-controlled dashboard with seven panels.

Grafana uses its own persistent data volume. Provisioning files are read-only; edit the repository rather than saving ad hoc UI changes. Anonymous access/signup and update/reporting checks are disabled. Neither monitoring container receives the provider API key. Images are version/digest pinned in Compose.

## Custom meter semantics

| Micrometer name | Prometheus name | Type / bounded tags | Meaning |
| --- | --- | --- | --- |
| `event.received` | `event_received_total` | Counter | Valid accept attempts, even if publishing later fails |
| `event.published` | `event_published_total` | Counter | Confirmed Kafka acknowledgements |
| `event.publish.errors` | `event_publish_errors_total` | Counter | Failed publish attempts, not every HTTP validation error |
| `event.publish.duration` | `event_publish_duration_seconds_{count,sum,max}` | Timer; outcome=success/error | Blocking publish attempt duration |
| `incident.events.processed` | `incident_events_processed_total` | Counter | Newly inserted, successfully committed events |
| `incident.events.duplicates` | `incident_events_duplicates_total` | Counter | Duplicate deliveries, not additional persisted events |
| `incident.events.errors` | `incident_events_errors_total` | Counter | Failed processing attempts; a retry can count another failure |
| `incident.events.processing` | `incident_events_processing_seconds_{count,sum,max}` | Timer; outcome=success/duplicate/error | Logical processing duration including transaction completion |
| `incident.incidents.created` | `incident_incidents_total` | Counter; source=manual/correlation | Committed actual creations; no rollback overcount |
| `incident.incidents.active` | `incident_incidents_active` | Gauge | Database-wide OPEN + INVESTIGATING count, including preexisting incidents |
| `analysis.requests` | `analysis_requests_total` | Counter; outcome=success/error | Completed whole analysis attempts |
| `analysis.errors` | `analysis_errors_total` | Counter | Failed whole analysis attempts |
| `analysis.duration` | `analysis_duration_seconds_{count,sum,max}` | Timer; outcome=success/error | Validation, MCP preflight/reads, model rounds, output validation and failures |

Counters/timers are per-process and reset on restart. They are not lifetime database totals. Use `rate`/`increase` for time-window throughput, with enough scrape samples. Five events accepted by the simulator is not proof of consumption; compare the persisted-event counter/history as well. The registry's Prometheus client normalizes reserved suffixes: the Micrometer name `incident.incidents.created` exports as `incident_incidents_total`, not `incident_incidents_created_total`. Dashboard queries and HTTP export coverage use the actual observed export name. AI meters are registered at zero before the first request without recording synthetic observations. Runtime telemetry recording failures do not replace business results or original exceptions.

The active gauge reads committed persistent state using a bounded read-only query, rather than incrementing/decrementing an in-memory estimate. Failed gauge reads return NaN, never fake zero. Each incident replica observes the same database-wide count: `max(incident_incidents_active)` is correct; summing replicas double-counts. This rule assumes replicas share the same logical database, as in this project.

No custom meter label uses eventId, traceId, incidentId, message, metadata, exception text or dynamically supplied service names. Trace IDs belong in logs, not metric tags. The original correlation thresholds and Redis state are unaffected by instrumentation.

## Dashboard panels

1. **Event processing rate** — `rate` of committed new-event count, events/second across incident instances; duplicate deliveries are excluded.
2. **Incidents created (running processes)** — sum of the creation counters from current processes, including manual/correlation sources. It can reset after restart even while the database retains incidents.
3. **Active incidents** — maximum database-wide gauge across replicas; OPEN/INVESTIGATING count, not historical incident total.
4. **Processing errors (running processes)** — three separate cumulative signals: incident processing, Kafka publishing and whole AI analysis failures. These are not all HTTP 4xx/5xx responses.
5. **Mean event processing latency** — summed rate of timer seconds divided by summed rate of observations; all processing outcomes. This is a mean, not a percentile.
6. **Mean AI analysis latency** — the analogous whole-analysis mean, including failed attempts. A real analysis must run before this panel has observations; scraping does not call the provider.
7. **Spring service scrape health** — `up` per job, UP=1/DOWN=0. Successful scraping is not proof that every business dependency or provider is available.

Dashboard refresh is 15 seconds, default range is the last 15 minutes. Rate windows use Grafana's `$__rate_interval` with the datasource's 15-second scrape interval. Allow at least two scrapes before checking rates. At idle the event rate is zero; latency can show a gap/No data when the window contains no observations. No query replaces missing data with a fabricated zero. A short scenario produces a burst visible in the chart's time range, not a sustained load.

## Real scenario verification

Do this only against a fresh isolated project, with no previous `order-service` history or incident. The existing smoke rejects shared state before posting and validates the gateway's actual project binding. It sends one simulator POST, validates exact IDs through Kafka/Redis/history/incident persistence, and retains the records. It is not safe to replay an uncertain write automatically.

```powershell
# Set these to the exact isolated project, ordered env files and published port.
$project = 'your-isolated-project'
$files = @('.env', '.env.observability-local')
$dc = @('--project-name', $project, '--env-file', $files[0], '--env-file', $files[1])
docker compose @dc ps
docker compose @dc port api-gateway 8081
# Use the port returned above, not a native app's default port.
.\scripts\smoke-docker.ps1 -ProjectName $project -EnvFile $files -Gateway 'http://127.0.0.1:28081' -IncludeAi
```

`-IncludeAi` authorizes one analysis HTTP POST, potentially several bounded provider rounds; it is never retried automatically and can incur provider charges. All normal automated tests remain offline. If the provider fails, the error counter and error-duration observation should still be recorded; do not confuse that with a successful AI response.

Before the smoke, inspect Prometheus targets and capture zero counters after initial successful scrapes. Afterwards:

- `/actuator/prometheus` contains the custom counters/gauge/timer series.
- Five newly processed events increase `incident_events_processed_total` by five; duplicate delivery must not increase it again.
- The correlated creation increases `incident_incidents_total` by one and active count to one on a fresh database.
- The success event timer records five observations; the analysis timer records the actual request outcome and duration.
- Compare active gauge with current incident statuses. On an explicitly authorized synthetic incident only, OPEN → INVESTIGATING retains one active incident; RESOLVED reduces it to zero without increasing creation count. Never mutate another project's incidents merely to test a gauge.
- Inspect JSON event/incident container logs and match the simulator's traceId through receipt, publishing, consumption, processing, correlation and automatic creation. Scoped MDC is restored on success/error; logs do not include event messages or metadata.
- Verify Grafana's provisioned datasource connection and dashboard queries return the same real data as Prometheus. View the short burst/latencies in the time window containing the scenario; idle recent rates can legitimately return to zero.

For the measured Stage 16 run, exact context, counts and test outcomes, read `../docs/stage16-verification.md`. Do not reuse its incident ID against a different database.

## Troubleshooting / safety

- **Targets DOWN initially:** wait for the selected applications to become healthy and another scrape. Inspect only the selected project's service logs; confirm exporter HTTP 200, container DNS and internal ports. Existing pre-Stage16 images do not contain the new registry until rebuilt.
- **Observer unhealthy:** inspect `docker compose @dc logs --tail 50 prometheus grafana`. Applications have no monitoring dependency and can continue processing. Never print resolved Compose configuration or unrestricted container environments; these contain credentials.
- **Grafana asks for a login:** use the private password from your own env file; no default admin/admin password exists. The datasource/dashboard are already provisioned after login.
- **No AI latency:** no analysis observations is normal until an analysis actually finishes. Run one intentionally authorized analysis against a real incident in that same project, not a provider call as a health probe.
- **All active replicas show the same number:** expected for a shared database; use max, not sum.
- **Metrics changed after restart:** counters/timers reset; the database-derived active gauge does not reset persistent state.
- **Logs are JSON instead of plain text:** intentional. Docker Desktop/`docker compose logs` still display them; search/filter by the top-level traceId. This stage does not add Loki/Elasticsearch or centralized log storage.
- **Another project is healthy but exec says not running:** correct the project/env-file argument array; do not start native applications or restart an old project on occupied ports.
- **Retention/cleanup:** `docker compose @dc stop` stops only the selected project and preserves data. Do not use `down -v` as routine verification cleanup; it removes persistent audit/metrics/Grafana data.

## Reference documentation

- Spring Boot metrics: https://docs.spring.io/spring-boot/reference/actuator/metrics.html
- Spring Boot structured logging/MDC: https://docs.spring.io/spring-boot/reference/features/logging.html
- Prometheus scrape configuration: https://prometheus.io/docs/prometheus/latest/configuration/configuration/
- Grafana provisioning: https://grafana.com/docs/grafana/latest/administration/provisioning/
