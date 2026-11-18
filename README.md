# incident-response-platform

A portfolio-focused distributed incident response platform. The intended system will ingest operational events, correlate failures deterministically, persist incidents, and eventually provide evidence-grounded AI investigations. Only the completed stages described below are implemented.

## Current services

| Service | Default HTTP port | Purpose | Runtime dependencies |
| --- | --- | --- | --- |
| incident-service | 8080 | Manage incidents, persist events, and correlate failures automatically | PostgreSQL, Kafka, Redis |
| event-service | 8082 | Validate operational events, assign missing IDs, publish JSON to Kafka, and acknowledge with 202 | Kafka |
| service-simulator | 8083 | Generate development scenarios through event-service's HTTP API only | event-service over HTTP |
| diagnostics-mcp-server | 8084 | Read-only MCP diagnostic tools over Streamable HTTP | incident-service over HTTP only |
| ai-analysis-service | 8085 | Evidence-grounded, read-only incident analysis with a bounded tool loop | diagnostics MCP over Streamable HTTP; OpenAI chat model |
| api-gateway | 8081 | Config-driven HTTP entry point for event, incident, service, analysis, and simulation APIs | Selected downstream services over HTTP only |

All six modules use Java 21 and Spring Boot 4.0.9, inherited from the root Maven POM. Each runs as its own application in a separate terminal. SERVER_PORT can override its default port. Stage 4 connects event-service and incident-service through Kafka and persists incident_events asynchronously. Stage 5 adds Redis-backed failure correlation and deterministic automatic incident creation without an LLM. Stage 7 adds an HTTP-only scenario generator; it does not implement detection or access Kafka, Redis, PostgreSQL, or the incident API directly. Stage 8 adds operational health/history APIs; Stage 9 exposes five read-only MCP tools backed only by those REST APIs and the existing incident API. Stage 10 adds a separate OpenAI-backed analysis service that reads diagnostics through MCP only; detection remains deterministic. Stage 11 adds an optional API gateway on loopback port 8081 as one convenient client entry point; existing services and their internal communication remain independent. Stage 12 adds bounded resilience only to diagnostics REST GETs and scoped AI MCP diagnostic reads, without gateway or model retries.

## Stages 1–2: Incident Service and PostgreSQL Persistence

Implemented with Java 21, Maven, Spring Boot 4.0.9, Spring MVC, Spring Data JPA, Jakarta Validation, PostgreSQL, Flyway, and Actuator. Tests use JUnit Jupiter 5.13.4, Mockito, MockMvc, and PostgreSQL Testcontainers. Framework versions are managed by the parent POM; Spring Boot has not been upgraded.

The Maven parent is named `incident-response-platform`. The existing checkout directory remains `incident-response`; there is no need to rename it to build or run the project.

```text
pom.xml                         Maven parent and module list
docker-compose.yml              PostgreSQL, KRaft Kafka, Redis, and topic initialization
.env.example                    Compose and application environment reference; no real password
incident-service/
  pom.xml                       Service dependencies and executable JAR build
  src/main/java/com/umass/incident/
    controller/                 HTTP requests and responses only
    service/                    Incident operations and validated event persistence
    consumer/                   Typed Kafka listener delegating to the service
    repository/                 Spring Data JPA persistence
    model/                      Incident/IncidentEvent entities and enums
    dto/                        Validated request and response contracts
    config/                     Injectable UTC clock and typed Kafka consumer factory
    exception/                  Controlled API errors
  src/main/resources/
    application.yaml            Environment-based runtime configuration
    db/migration/               Flyway-managed PostgreSQL schema
  src/test/java/                Unit, controller, and real PostgreSQL/HTTP tests
event-service/
  pom.xml                       Web service with a Kafka producer; no database dependency
  src/main/java/com/umass/event/
    EventServiceApplication.java
    controller/                 POST /api/events
    service/                    ID assignment, acknowledged Kafka publishing, safe logging
    config/                     Explicit JSON serializer using Boot's configured mapper
    dto/                        ServiceEvent and EventAcceptedResponse
    model/                      EventType and EventSeverity enums
    exception/                  Validation and malformed-request ProblemDetail responses
  src/main/resources/
    application.yaml            Configurable port and strict enum-number handling
  src/test/java/                Service, MockMvc, and real Kafka/HTTP tests
service-simulator/
  pom.xml                       HTTP-only scenario generator; no infrastructure clients
  src/main/java/                Application, simulation API/service, HTTP client, config, delay
  src/main/resources/
    application.yaml            Port, required event-service URL, count and timing settings
  src/test/java/                JUnit 5 unit/controller/config and real HTTP stub tests
diagnostics-mcp-server/
  pom.xml                       Spring AI 2.0.1 MCP WebMVC starter; no data-store clients
  src/main/java/com/umass/diagnostics/
    config/                     Required incident-service origin, HTTP timeouts, scan limits
    service/                    REST-only diagnostic logic and incident-service client
    dto/                        Local REST contracts and structured diagnostic results
    tool/                       Five annotated read-only MCP tools
  src/main/resources/
    application.yaml            Loopback HTTP, STREAMABLE transport, /mcp endpoint
  src/test/java/                Unit/HTTP-client tests and real MCP client/server integration
ai-analysis-service/
  pom.xml                       Spring AI OpenAI/MCP clients; opt-in manual-openai profile
  .env.example                  Blank key/model placeholders and bounded local settings
  src/main/java/com/umass/analysis/
    controller/                 Thin POST incident-analysis endpoint
    service/                    Required diagnostics, bounded model/tool loop, grounding checks
    mcp/                        Scoped, read-only Streamable HTTP client sessions
    dto/                        Analysis, confidence, and captured evidence response contracts
    config/                     Validated analysis budgets and environment configuration
    exception/                  Sanitized analysis failures
  src/test/java/                Mock-ChatModel unit/HTTP tests, private MCP fixture, opt-in live IT
api-gateway/
  pom.xml                       Gateway-only Spring Cloud BOM and WebFlux gateway starter
  .env.example                  Nonsecret destination URLs and gateway-local settings
  src/main/java/                Gateway application; no domain or infrastructure clients
  src/main/resources/
    application.yaml            Config-driven routes, HTTP timeouts, loopback, health only
  src/test/java/                Private loopback downstream stubs; no Docker or live providers
```

Request flow:

```text
HTTP -> IncidentController -> IncidentService -> IncidentRepository -> PostgreSQL
        request DTOs          response DTO mapping                Flyway schema
```

JPA entities are never returned from controllers. Creation assigns a UUID, OPEN status, and UTC creation/update timestamps on the server. The caller supplies title, severity, rootService, and errorCount. Timestamp precision is normalized to PostgreSQL microseconds so a creation response matches subsequent retrieval. Status updates preserve identity, creation time, and other incident fields. All three defined statuses are accepted; no transition restrictions are introduced in this stage.

Stage 2 added PostgreSQL persistence; Stage 4 added KRaft Kafka and a topic initializer to Compose; Stage 5 adds transient Redis correlation state. All six Java applications run natively on Windows, not in containers; Compose still runs infrastructure only. Stage 8 exposes operational REST APIs and restricted Actuator health/info/metrics in incident-service. Stage 9 adds a REST-backed MCP server; Stage 10 adds read-only AI analysis. Stage 11 adds an HTTP-only API gateway; Kubernetes remains unimplemented.

## Prerequisites

- A full JDK 21, including `javac` (a Java runtime alone is not sufficient).
- Windows PowerShell and the included Windows Maven wrapper (`.\mvnw.cmd`). A separate Maven installation is optional.
- Docker Desktop with Docker Compose, for PostgreSQL/Kafka/Redis and real Testcontainers integration tests. No local PostgreSQL, Kafka, or Redis installation is needed.

Run the following in Windows PowerShell from the project directory:

```powershell
java -version
javac -version
.\mvnw.cmd -version
docker version
docker compose version
```

Java and javac must report version 21. If JAVA_HOME is set, it must point to your Windows JDK installation, not a WSL or Linux path. If you need to select a JDK for the current PowerShell session:

```powershell
$env:JAVA_HOME = Read-Host 'Full Windows path to your JDK 21 installation'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

Start Docker Desktop in Linux-container mode before running the full test suite. The PostgreSQL test image runs inside Docker Desktop; the application and Maven run natively on Windows. WSL shell commands, a Docker TCP proxy, and WSL integration for this project are not required when running from Windows PowerShell. Do not expose an unauthenticated Docker TCP endpoint. No Linux home-directory paths or Docker socket paths are configured in the project.

## Build and test

From the repository root:

```powershell
.\mvnw.cmd clean verify
```

On Windows, stop any application launched with `java -jar` from a module's target directory before using clean/verify; its running JVM can lock that JAR. To run every test without deleting/repackaging a running JAR, use `.\mvnw.cmd test` instead.

Equivalent with Maven installed:

```powershell
mvn clean verify
```

The default suite includes:

- IncidentServiceTest: creation, retrieval, not found, status update, filtering, unfiltered listing, missing-update protection, and timestamp precision.
- IncidentControllerTest: creation, retrieval, listing/filtering, status update, validation, malformed JSON/UUID/enums, missing incidents, and invalid updates.
- IncidentPersistenceIntegrationTest: actual PostgreSQL migration and DB_* configuration, repository save/retrieve/filter, persisted service status updates, transaction commit, rollback after a flushed insert, rollback after a failed flushed update, and real HTTP lifecycle/error behavior.
- EventServiceTest: normalized-event publication, missing/supplied IDs, optional metadata, immutable snapshots, safe logging, and no acceptance log on publication failure.
- KafkaEventPublisherTest: mocked KafkaTemplate acknowledgement, asynchronous/synchronous failures, bounded waiting, and restored interruption.
- EventControllerTest: valid acceptance, invalid input, metadata limits, and 503 when publication cannot be confirmed.
- EventApplicationIntegrationTest: real HTTP -> real Kafka, JSON/Instant representation, no Java class headers, all severity values, validation, and health.
- EventProcessingServiceTest and EventConsumerTest: consumer validation, field mapping, processing time, delegation, and propagation of database failures for retry.
- KafkaEventPipelineIntegrationTest: real Kafka -> production typed listener -> PostgreSQL, V2 migration, ignored Java type headers, malformed/missing/numeric severity rejection without blocking subsequent valid events, and repeated event IDs.

The infrastructure integration suites start disposable Kafka, PostgreSQL, and/or Redis Testcontainers and stop them afterward. They use random mapped ports and generated database credentials, never H2 or a developer database/broker. The database-only regression suite disables listener startup; the dedicated Kafka suite exercises the production listener. These suites fail rather than silently skipping when Docker is unavailable. They execute during `mvn test` and `mvn verify`; no Compose startup or application environment variables are required first. The simulator's real HTTP stub integration suite does not use Docker; see Stage 7 below.

For an explicitly limited unit/controller run without infrastructure:

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=IncidentServiceTest,IncidentControllerTest' test
.\mvnw.cmd -pl incident-service '-Dtest=EventProcessingServiceTest,EventConsumerTest' test
.\mvnw.cmd -pl event-service '-Dtest=EventServiceTest,EventControllerTest,KafkaEventPublisherTest' test
```

This focused command is not a substitute for running the full suite.

### JUnit 5 compatibility

Spring Framework 7's SpringExtension requires JUnit 6. To retain the requested JUnit 5 while using Spring Boot 4.0.9, controller tests use standalone MockMvc with the real controller/advice/validation and a mocked service. Integration tests start and close a real Spring Boot application directly via SpringApplicationBuilder and use an HTTP client plus the real repository. They do not use @WebMvcTest or @SpringBootTest. Mockito is attached as a test JVM agent, avoiding dynamic-agent attachment warnings on Java 21.

## Start PostgreSQL/Kafka/Redis and run incident-service

Start Docker Desktop in Linux-container mode. From Windows PowerShell, open the repository root (the directory containing docker-compose.yml). Configure the database, Kafka, and Redis settings in that same terminal. Reuse your existing database credentials if its persistent volume already exists:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5433'
$env:DB_NAME = 'incident_response'
$credentials = Get-Credential -UserName 'incident_app' -Message 'Choose credentials for the Docker development database'
$env:DB_USERNAME = $credentials.UserName
$env:DB_PASSWORD = $credentials.GetNetworkCredential().Password
Remove-Variable credentials
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:REDIS_HOST = 'localhost'
$env:REDIS_PORT = '6379'
$env:INCIDENT_CORRELATION_THRESHOLD = '5'
$env:INCIDENT_CORRELATION_WINDOW_SECONDS = '60'
```

These are local-development examples. DB_HOST identifies the database host, DB_PORT is its published host port, DB_NAME is the database name, and DB_USERNAME/DB_PASSWORD are its login credentials. No values are hardcoded in application.yaml. DB_PORT=5433 avoids the Windows PostgreSQL service commonly listening on 5432; use another available port if needed. The old DATABASE_URL, DATABASE_USERNAME, and DATABASE_PASSWORD settings have been replaced by DB_*.

Start and check all three infrastructure services, then create the topic idempotently:

```powershell
docker compose config --quiet
docker compose up --wait --wait-timeout 120 postgres kafka redis
docker compose run --rm kafka-init
docker compose ps
docker compose logs postgres
```

postgres, kafka, and redis must all show healthy. PostgreSQL is published only on loopback, maps DB_PORT to container port 5432, and uses a persistent named volume. Kafka is a single combined KRaft broker/controller, with no ZooKeeper. Its external listener uses localhost:9092 by default; the internal Compose listener is kafka:19092. Redis is pinned to redis:8.2-alpine, published only on loopback at REDIS_PORT (6379 by default), and checked with redis-cli ping. Redis is a transient cache: snapshot/AOF persistence is disabled and no persistent volume is needed. This unauthenticated Redis setup is for local development, not production. The one-shot kafka-init creates service-events with three partitions and replication factor one; it is safe to rerun. Plain `docker compose up` also starts the initializer and stays attached to logs. The explicit two-command sequence above waits only on long-running services before running the short-lived initializer.

On the first startup with an empty volume, the PostgreSQL image creates DB_NAME and the DB_USERNAME login using DB_PASSWORD. No manual database creation in pgAdmin is required. These Docker credentials are independent of any existing Windows PostgreSQL credentials. This is a local-development database; the image's initialization user is an administrator and should not be treated as a production least-privilege setup.

Keep the same PowerShell session and start the application:

```powershell
$env:SERVER_PORT = '8080'
.\mvnw.cmd -pl incident-service spring-boot:run
```

If another application uses port 8080, set SERVER_PORT to an available port and use that port in API requests. Port 8081 is reserved for the Stage 11 gateway by default; if changing incident-service's port, also update GATEWAY_INCIDENT_SERVICE_URL in the gateway terminal. Keep the application terminal open and use a second terminal for HTTP requests.

Flyway applies `V1__create_incidents_table.sql` and the new `V2__create_incident_events_table.sql` when incident-service starts. Existing Stage 2/3 databases apply only the new V2; V1 is unchanged. Compose alone does not create application tables. Hibernate uses `ddl-auto=validate` and never creates or updates tables. A configuration/migration/connection failure prevents startup rather than silently using another database. Create the Kafka topic before starting the listener.

### Optional Compose .env file

For Compose configuration saved between sessions, copy the example locally:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set your own nonempty DB_PASSWORD in .env. Never commit it; .env and .env.* are ignored, while .env.example contains no real password. Docker Compose loads .env, but Spring Boot does not: a Compose .env file does not populate Java process environment variables. Set DB_*, KAFKA_BOOTSTRAP_SERVERS, REDIS_HOST, and REDIS_PORT in the incident-service terminal/IDE, plus any correlation overrides shown above. event-service still needs only Kafka configuration, not database or Redis settings. Set EVENT_SERVICE_BASE_URL and any SIMULATOR_* overrides separately in the simulator terminal; the simulator needs no database, Kafka, or Redis variables. Shell environment values override Compose .env values. Do not share rendered `docker compose config` output because it includes resolved secrets; use `config --quiet`. If changing KAFKA_PORT, also update KAFKA_BOOTSTRAP_SERVERS. If changing REDIS_PORT, use the same published port in incident-service. KAFKA_EVENTS_TOPIC must match between event-service, incident-service, and the initializer. Keep KAFKA_CLUSTER_ID unchanged while reusing its volume.

Environment variables belong to the current PowerShell session and its child processes, not every terminal. Close the session or remove password variables when finished.

### Data persistence and stopping

Stop incident-service with Ctrl+C. From a terminal with the same Compose configuration:

```powershell
docker compose down
```

The postgres_data and kafka_data volumes are retained, preserving database rows, Kafka records, and consumer offsets. Redis correlation windows are transient and are lost when its container is removed/restarted; existing PostgreSQL incidents remain. To restart, repeat the infrastructure commands above and start the applications. Do not use `down --volumes` unless you intentionally want to delete both persistent data stores. Changing DB_NAME, DB_USERNAME, or DB_PASSWORD does not reinitialize an existing volume. This setup neither imports nor modifies Windows PostgreSQL data.

### Existing Stage 1 databases

Stage 2 renames V1__create_incidents.sql to the requested V1__create_incidents_table.sql without changing its SQL or version. A fresh Compose volume and Testcontainers database use the new filename normally. A database that already applied the old filename may fail Flyway validation because its recorded migration description is different. Back it up and review a one-time Flyway metadata repair before reusing it with Stage 2. No automatic repair, history edits, or destructive reset is performed. Do not bypass validation to hide this difference; use the fresh Compose database for this stage.

After building, the executable JAR can be used instead:

```powershell
java -jar .\incident-service\target\incident-service-0.0.1-SNAPSHOT.jar
```

## API contracts and manual verification

Configure the URL of your running service in a second Windows PowerShell window. These examples use native PowerShell HTTP commands, avoiding Windows PowerShell's `curl` alias and shell-specific JSON quoting:

```powershell
$env:API_BASE_URL = 'http://localhost:8080'
```

1. Create an incident; expect HTTP 201, a Location header, and an incident response. Required fields: a nonblank title up to 200 characters, a severity, a nonblank rootService up to 100 characters, and a nonnegative integer errorCount.

```powershell
$createRequest = @{
    title = 'Payment database failures'
    severity = 'HIGH'
    rootService = 'payment-service'
    errorCount = 5
} | ConvertTo-Json
$incident = Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents" -Method Post -ContentType 'application/json' -Body $createRequest
$incident
```

Response fields: id, title, status, severity, rootService, createdAt, updatedAt, errorCount. Severity values are LOW, MEDIUM, HIGH, CRITICAL. Status values are OPEN, INVESTIGATING, RESOLVED. Enum values are case-sensitive.

2. Use the returned UUID, then retrieve and list; expect HTTP 200. Lists are JSON arrays (empty results are `[]`). Ordering and pagination are not part of this stage's contract.

```powershell
$env:INCIDENT_ID = $incident.id
Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents/$env:INCIDENT_ID"
Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents"
Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents?status=OPEN"
```

3. Update status; expect HTTP 200 and status INVESTIGATING. Retrieve again to confirm the change is persisted. RESOLVED can also be used.

```powershell
$updateRequest = @{ status = 'INVESTIGATING' } | ConvertTo-Json
Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents/$env:INCIDENT_ID/status" -Method Patch -ContentType 'application/json' -Body $updateRequest
Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents/$env:INCIDENT_ID"
```

4. Verify controlled errors: POST `{}` returns 400 with field errors; invalid enum values, malformed JSON, and malformed UUIDs return 400. A well-formed UUID that does not exist returns 404. Responses use ProblemDetail with status and detail; validation failures also contain an errors map. Stack traces and database exception details are not exposed by the default error endpoint.

PowerShell throws an HTTP error for non-success responses. The try/catch examples display the status code and error response intentionally:

```powershell
try {
    Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents" -Method Post -ContentType 'application/json' -Body '{}'
} catch {
    [int]$_.Exception.Response.StatusCode
    $_.ErrorDetails.Message
}
try {
    Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents?status=INVALID"
} catch {
    [int]$_.Exception.Response.StatusCode
    $_.ErrorDetails.Message
}
try {
    Invoke-RestMethod -Uri "$env:API_BASE_URL/api/incidents/00000000-0000-0000-0000-000000000000"
} catch {
    [int]$_.Exception.Response.StatusCode
    $_.ErrorDetails.Message
}
Invoke-RestMethod -Uri "$env:API_BASE_URL/actuator/health"
```

5. Restart the application against the same database and retrieve the original incident to confirm durability.

6. Inspect Docker PostgreSQL directly from a PowerShell session configured with the same DB_* variables. No password is placed in the command:

```powershell
docker compose exec postgres psql -U $env:DB_USERNAME -d $env:DB_NAME -c 'TABLE public.incidents;'
```

Alternatively register a separate pgAdmin server using host localhost, port equal to DB_PORT (5433 in the example), maintenance database equal to DB_NAME, and the Docker DB_USERNAME/DB_PASSWORD. Do not select the Windows PostgreSQL server on 5432 and expect to see the separate Docker records.

## Stages 3–4: Event Service and Kafka Pipeline

The second service accepts operational events from backend applications. Its current flow is:

```text
POST /api/events -> EventController -> EventService -> KafkaEventPublisher
                                                     -> service-events (JSON)
                 <- HTTP 202 with eventId after broker acknowledgement

service-events -> EventConsumer (@KafkaListener, group incident-processors)
               -> EventProcessingService (validate, log, transaction)
               -> IncidentEventRepository -> PostgreSQL incident_events
```

Stage 3 introduced validated acceptance. Stage 4 publishes the normalized ServiceEvent as JSON, keyed by serviceName. A 202 confirms broker acknowledgement, not completed database processing or incident creation. A failed/unconfirmed publication returns 503 rather than false acceptance. A timeout can occur after Kafka accepted a record, so retries should reuse a supplied eventId. Stage 5 correlates consumed failures in incident-service; event-service has no Redis dependency. There is still no event retrieval endpoint. Stage 7 adds simulator-to-event-service HTTP calls; event-service and incident-service still communicate through Kafka, not direct HTTP.

### Run event-service on Windows

In a separate Windows PowerShell terminal at the repository root:

```powershell
$env:SERVER_PORT = '8082'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
.\mvnw.cmd -pl event-service spring-boot:run
```

event-service needs Kafka and the topic initialized above, but no DB_* or REDIS_* variables or running incident-service. Events remain in Kafka until incident-service consumes them. incident-service runs in its own terminal with its database, Kafka, and Redis settings. Explicitly setting SERVER_PORT prevents port reuse from another service's environment. Stop either application with Ctrl+C in its own terminal.

After a root build, event-service can alternatively be started as an executable JAR:

```powershell
$env:SERVER_PORT = '8082'
java -jar .\event-service\target\event-service-0.0.1-SNAPSHOT.jar
```

### Event request contract

POST /api/events requires Content-Type: application/json. The ServiceEvent DTO contains:

| Field | Type | Validation/behavior |
| --- | --- | --- |
| eventId | UUID | Optional; omitted/null generates a UUID; a supplied valid UUID is preserved |
| serviceName | String | Required, nonblank, maximum 100 characters |
| eventType | EventType | Required; one of the named values below |
| severity | EventSeverity | Required; INFO, WARNING, ERROR, CRITICAL |
| message | String | Required, nonblank, maximum 4000 characters |
| timestamp | Instant | Required ISO-8601 instant, for example 2026-11-15T12:00:00Z; not generated by the service |
| traceId | String | Optional, maximum 128 characters |
| metadata | Map of String to String | Optional; omitted/null becomes an empty accepted map; maximum 50 entries, nonblank keys up to 100 characters, nonnull values up to 1000 characters |

The initial EventType values are SERVICE_STARTED, SERVICE_STOPPED, REQUEST_FAILED, DATABASE_ERROR, DEPENDENCY_ERROR, TIMEOUT. This small initial contract was chosen because the Stage 3 request did not specify event-type values. Enum names are case-sensitive; unknown names and numeric enum ordinals are rejected.

The response is an EventAcceptedResponse containing only eventId. Logging includes eventId, serviceName, eventType, severity, and timestamp. Raw message, traceId, and metadata are not logged or echoed in the acknowledgement; line breaks in the logged service name are sanitized. The accepted service object retains its payload and a defensive, immutable metadata snapshot.

### Verify event acceptance

In another PowerShell terminal:

```powershell
$env:EVENT_API_BASE_URL = 'http://localhost:8082'
$eventRequest = @{
    serviceName = 'payment-service'
    eventType = 'DATABASE_ERROR'
    severity = 'ERROR'
    message = 'Database connection failed'
    timestamp = [DateTime]::UtcNow.ToString('o')
    traceId = [Guid]::NewGuid().ToString()
    metadata = @{ database = 'payments' }
} | ConvertTo-Json -Depth 3
$accepted = Invoke-RestMethod -Uri "$env:EVENT_API_BASE_URL/api/events" -Method Post -ContentType 'application/json' -Body $eventRequest
$accepted
Invoke-RestMethod -Uri "$env:EVENT_API_BASE_URL/actuator/health"
```

Expect HTTP 202, an eventId, an `Accepted event` entry in the event-service terminal with that ID, and health UP. In Postman, use POST to http://localhost:8082/api/events with Body > raw > JSON:

```json
{
  "serviceName": "payment-service",
  "eventType": "DATABASE_ERROR",
  "severity": "ERROR",
  "message": "Database connection failed",
  "timestamp": "2026-11-15T12:00:00Z",
  "traceId": "trace-123",
  "metadata": { "database": "payments" }
}
```

Remove serviceName or severity and resend: expect 400 with an errors map naming the missing field. An empty body, malformed JSON, invalid UUID/timestamp, or unknown enum also returns a controlled 400 ProblemDetail response. To test ID preservation, include an actual valid eventId UUID and confirm the acknowledgement returns exactly that value. One isolated error below the configured threshold does not create an incident; repeated qualifying failures can now create one through Stage 5.

### Event-service tests

Run all event-service tests, including real HTTP/Kafka Testcontainers tests (Docker required; no PostgreSQL required):

```powershell
.\mvnw.cmd -pl event-service test
```

Run just the event service/controller unit tests:

```powershell
.\mvnw.cmd -pl event-service '-Dtest=EventServiceTest,EventControllerTest,KafkaEventPublisherTest' test
```

The root command `.\mvnw.cmd clean verify` builds and tests all six modules, including the Stage 7 simulator and Stage 11 gateway. Docker is required for the earlier infrastructure integration suites, not for simulator or gateway tests. Compose runs infrastructure only; application containerization is not part of Stage 7.

## Stage 4: Kafka delivery and database verification

Serialization is configured in KafkaProducerConfiguration/KafkaConsumerConfiguration using Spring Kafka's Jackson 3 serializers and Boot's configured JSON mapper. The producer sends no Java class-name headers; the consumer always targets its own ServiceEvent DTO and ignores incoming type headers. Event-service and incident-service have no Maven dependency on each other. The consumer requires an eventId, even though HTTP requests may omit it: event-service assigns it before publication.

The incident_events table stores event_id (primary key), service_name, event_type, severity, message, timestamp, nullable trace_id, and processed_at. Source and processing instants use PostgreSQL microsecond precision. Metadata travels in Kafka JSON but is not persisted in this initial table. Stage 4 supplied durable event storage; Stage 5 adds automatic incidents using the correlation rules below.

Consumer auto-commit is disabled. RECORD acknowledgement commits after the transactional processing method returns successfully. Database or Redis failures propagate and retry indefinitely with a configurable interval; they are not intentionally skipped. Invalid DTOs and malformed JSON are logged with topic/partition/offset and skipped so a poison record does not block following valid events. There is no dead-letter topic in this stage. Logs omit raw payload/message/trace/metadata. The processor skips an eventId already committed to incident_events, preserving the stored row and preventing repeated correlation, even after Redis expiry. Stage 6 makes this database decision atomic for concurrent deliveries as well. Redis also deduplicates retried, not-yet-committed events within its window. This is at-least-once processing, not an exactly-once claim or a transactional outbox.

After starting infrastructure and both services, post the event shown above and save the returned eventId. Then:

1. Find `Accepted event eventId=...` in event-service and `Consumed event eventId=...` in incident-service. The latter is logged after the database transaction completes.
2. Run these inspection commands in a terminal with the same complete Compose DB_* settings. All Compose commands interpolate the whole file, even when inspecting only Kafka:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe --topic service-events
docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:19092 --describe --group incident-processors
docker compose exec postgres psql -U $env:DB_USERNAME -d $env:DB_NAME -c 'SELECT event_id, service_name, event_type, severity, message, timestamp, trace_id, processed_at FROM public.incident_events ORDER BY processed_at DESC LIMIT 10;'
docker compose exec postgres psql -U $env:DB_USERNAME -d $env:DB_NAME -c 'SELECT version, script, success FROM flyway_schema_history ORDER BY installed_rank;'
```

Expect three topic partitions, the incident-processors consumer group, a database row matching the exact acknowledged eventId, and successful V1/V2 migrations. PostgreSQL persistence is asynchronous; retry the query briefly if needed. pgAdmin can inspect the same table under the Docker server/database, not the separate Windows PostgreSQL instance.

3. Optional backlog check: stop only incident-service with Ctrl+C, then post another event. It should still return 202 because Kafka remains available. Restart incident-service with the same environment and confirm its event row appears.
4. Repeat the malformed HTTP checks above: these still return 400. A Kafka outage or unconfirmed broker acknowledgement returns 503; do not interpret a basic health response as proof of broker connectivity. If retrying an uncertain publication, supply the same eventId.
5. Stop both applications before `docker compose down`. Restart infrastructure with the two commands above, then the services, and verify the event row remains. Do not remove volumes.

The Kafka Compose image is pinned to apache/kafka:4.1.2. This is local-development infrastructure: a single broker/controller, replication factor one, PLAINTEXT listeners, loopback-only host publishing, and persistent kafka_data storage. It is not a production high-availability or authenticated cluster. If changing host ports, keep advertised-listener and application bootstrap settings consistent.

## Stage 5: Redis failure correlation

incident-service counts only ERROR and CRITICAL events per serviceName. INFO and WARNING events are persisted but do not contribute to correlation. The default trigger is **at least 5 distinct failures within a sliding 60-second processing-time window**, not the source event timestamp. Atomic Redis sorted-set operations use Redis server TIME, prune expired members, and count the remaining UUID eventId members. Redis deduplicates the same eventId within its window; the PostgreSQL check additionally skips already committed events after Redis expiry.

The per-service keys are `errors:{serviceName}` and `errors:{serviceName}:critical`. The braces are literal Redis hash tags: order-service uses `errors:{order-service}` and `errors:{order-service}:critical`. Both are sorted sets, with TTL refreshed after the last qualifying failure so idle state expires. The critical set tracks which failures in the window are CRITICAL. An automatically created incident is OPEN, has rootService equal to serviceName, uses the current window errorCount, and receives a deterministic title without an LLM. Its severity is CRITICAL if any CRITICAL failure remains in the window; otherwise it is HIGH.

An existing OPEN or INVESTIGATING incident with the same rootService suppresses a new automatic incident, including when the active incident was created manually. A PostgreSQL transaction-scoped advisory lock prevents concurrent duplicate automatic creation for that service. Stage 5 does not escalate or update an existing incident. Once the active incident is RESOLVED, a fresh qualifying event may create another incident if the threshold is still met; resolution does not itself clear the Redis window. Redis and PostgreSQL do not share a distributed atomic transaction, and this is not an exactly-once guarantee.

| Configuration | Local example/default | Purpose |
| --- | --- | --- |
| REDIS_HOST | localhost | Required Redis host for incident-service |
| REDIS_PORT | 6379 | Required Redis port; also the Compose published port |
| INCIDENT_CORRELATION_THRESHOLD | 5 | Failure count required to trigger an incident |
| INCIDENT_CORRELATION_WINDOW_SECONDS | 60 | Sliding processing-time window and idle expiry |

These map to `incident.correlation.threshold=5` and `incident.correlation.window-seconds=60`; both must be positive. CorrelationServiceTest covers the detection rules. RedisFailureCounterIntegrationTest covers expiry, rolling-window pruning, deduplication, and atomic concurrent updates. CorrelationIntegrationTest exercises real Redis/PostgreSQL transactions and concurrent incident suppression; KafkaEventPipelineIntegrationTest also verifies automatic incident creation through Kafka.

### Windows / Postman verification

Start PostgreSQL, Kafka, and Redis, run kafka-init, and start both Java services in the separate terminals described above. Use the default threshold/window (5/60) for this check. In Postman, create a unique service name such as `stage5-payment-<new UUID>` and reuse that exact name throughout this check; this avoids collisions with prior events or manually created active incidents.

1. Send **five POST** requests to `http://localhost:8082/api/events`, Body > raw > JSON, within 60 seconds. Use the same unique serviceName and severity ERROR. Omit eventId as below so event-service generates a different UUID for every request, or supply a different valid UUID each time. Each should return 202. Replace the example service name once, then send the same body five times:

```json
{
  "serviceName": "stage5-payment-replace-with-unique-suffix",
  "eventType": "DATABASE_ERROR",
  "severity": "ERROR",
  "message": "Stage 5 correlation verification",
  "timestamp": "2026-11-16T12:00:00Z"
}
```

2. Send **GET** `http://localhost:8080/api/incidents?status=OPEN`. Consumption is asynchronous: retry briefly, then find the exact rootService. Expect **one** generated incident, a deterministic title, severity HIGH, and errorCount 5. Save its id and title. The list can contain unrelated incidents; filter by your unique rootService rather than counting the whole response.
3. Send a sixth ERROR with a new eventId for that same service. After it is consumed, GET again: there must still be only one active incident for that service, with the same id and title. No existing-incident count/severity update is required.
4. For a **different unique serviceName**, send five WARNING events with distinct eventIds. After consumption, GET again: there must be no incident for that warning-only service. INFO is also ignored by correlation.
5. Optional: for another unique service, send four ERROR events and one CRITICAL within the window; expect one CRITICAL incident. To check resolution behavior, PATCH the original incident to RESOLVED using the existing status endpoint, then send a fresh ERROR while at least five failures still remain in its window: a new OPEN incident may be created with a new id.

Optional Redis inspection from a PowerShell terminal with the same Compose settings (replace the example service name with the exact value sent):

```powershell
$serviceName = 'stage5-payment-replace-with-unique-suffix'
$counterKey = 'errors:{' + $serviceName + '}'
docker compose exec redis redis-cli ZCARD $counterKey
docker compose exec redis redis-cli PTTL $counterKey
docker compose exec redis redis-cli ZCARD "${counterKey}:critical"
```

These are **sorted-set** keys: use ZCARD, not GET. PTTL is remaining lifetime in milliseconds; -2 means the key has expired or does not exist. Inspect soon after consumption: idle keys expire, so later ZCARD can be 0. Between writes, ZCARD can include aged members until the next pruning operation or key expiry; it is not a separate live-window API.

## Stage 6: Idempotent Kafka event processing

The idempotency key is ServiceEvent.eventId. PostgreSQL already enforces uniqueness through the event_id primary key in V2; no applied migration is changed and no new migration is required.

EventProcessingService validates the event, then performs `INSERT ... ON CONFLICT (event_id) DO NOTHING` through IncidentEventRepository inside the processing transaction. Only an inserted row proceeds to Redis correlation. A duplicate returns normally, logs `Ignoring duplicate event`, and does not change the original event row, Redis counters, or incidents. This replaces the earlier check-then-save race: concurrent deliveries wait for PostgreSQL's uniqueness decision instead of raising avoidable duplicate-key failures. Reusing an ID with different valid payload data still preserves the first committed row; send a new ID for a genuinely new event.

Event persistence and automatic incident creation commit together in PostgreSQL. If processing fails, the insert rolls back and Kafka can retry. Redis's atomic UUID-member deduplication protects retries within the correlation window; the PostgreSQL gate remains effective after Redis expiry or application restart, as long as the stored event row is retained. Redis and PostgreSQL still do not share a distributed atomic transaction; this is not a general exactly-once guarantee across failures, cache loss, or deliberate database deletion.

To verify in Postman, supply one valid eventId and a unique serviceName, then send the same ERROR event twice. Both POSTs can return 202 because event-service publishes both messages; after consumption, expect one incident_events row and Redis ZCARD 1. After four other distinct failures reach the threshold, replaying the threshold-triggering event must leave the count at 5 and must not create another incident. Duplicates are skipped even if the original incident has since been resolved.

Focused tests (Docker Desktop required for the integration suites):

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=EventProcessingServiceTest,CorrelationIntegrationTest,KafkaEventPipelineIntegrationTest' test
```

Tests cover sequential and concurrent redelivery, PostgreSQL-enforced uniqueness, unchanged original payloads, Redis counter preservation after a duplicate, duplicates after cache expiry, and replay of a threshold-triggering event through the real Kafka listener.

## Stage 7: HTTP-only service simulator

The third Maven module, service-simulator, generates realistic operational event messages for local development. It sends each event to `POST /api/events` on event-service over HTTP. It has no direct Kafka, Redis, PostgreSQL, or incident API access and contains no failure detection or active-incident suppression logic. Those rules remain in incident-service.

```text
POST /simulate/{scenario} -> service-simulator -> HTTP POST /api/events
                                             -> event-service -> Kafka
                                                            -> incident-service
                                                               -> PostgreSQL / Redis
```

### Run service-simulator in a third Windows terminal

Start infrastructure and the first two applications using the earlier sections. In a **third Windows PowerShell terminal**, change to the project root and set the simulator's environment explicitly (variables do not carry over from other terminals):

```powershell
Set-Location 'C:\Users\Shiva Sravani\Desktop\incident-response'
$env:EVENT_SERVICE_BASE_URL = 'http://localhost:8082'
$env:SERVER_PORT = '8083'
.\mvnw.cmd -pl service-simulator spring-boot:run
```

Keep this terminal open; stop the simulator with Ctrl+C. Use another PowerShell window for requests. If the checkout or service ports differ, adjust the path and URLs accordingly. No DB_*, KAFKA_*, or REDIS_* settings are required in the simulator terminal. For incident verification, incident-service must also be running and consuming; simulator HTTP success alone does not establish that.

| Environment variable | Required/default | Purpose |
| --- | --- | --- |
| EVENT_SERVICE_BASE_URL | Required; local example `http://localhost:8082` | Base URL for event-service |
| SERVER_PORT | Optional; `8083` | Simulator HTTP port |
| SIMULATOR_FAILURE_EVENT_COUNT | Optional; `5` | Events per failure wave; validated from 1 through 100 |
| SIMULATOR_CASCADE_DELAY | Optional; `250ms` | Delay between cascade waves; zero is allowed |
| SIMULATOR_CONNECT_TIMEOUT | Optional; `2s` | Bounded HTTP connection timeout |
| SIMULATOR_READ_TIMEOUT | Optional; `15s` | Bounded HTTP response timeout |

Set optional overrides with `$env:NAME = 'value'` in the third terminal before startup. The failure count is independent of INCIDENT_CORRELATION_THRESHOLD: changing the simulator count does not change detection rules. The normal scenario always sends three events.

### Trigger scenarios (no request body)

These Windows-compatible commands use **curl.exe**, not Windows PowerShell's `curl` alias. Each POST has **no payload**; `-i` shows the response status and headers:

```powershell
curl.exe -i -X POST http://localhost:8083/simulate/normal
curl.exe -i -X POST http://localhost:8083/simulate/database-failure
curl.exe -i -X POST http://localhost:8083/simulate/payment-failure
curl.exe -i -X POST http://localhost:8083/simulate/cascade-failure
```

| Scenario | Events at the default count | Total |
| --- | --- | --- |
| normal | One INFO / SERVICE_STARTED for each of order-service, payment-service, and inventory-service | 3 |
| database-failure | Five ERROR / DATABASE_ERROR events for order-service | 5 |
| payment-failure | Five ERROR / TIMEOUT events for payment-service | 5 |
| cascade-failure | Five CRITICAL / DATABASE_ERROR events for order-service, wait 250ms, five ERROR / DEPENDENCY_ERROR events for payment-service, wait 250ms, five ERROR / DEPENDENCY_ERROR events for inventory-service | 15 |

Each scenario invocation creates one UUID traceId shared by all its events, a new UUID eventId for every event, and a timestamp at dispatch. Messages describe the relevant startup, database, timeout, or downstream dependency failure. A cascade sends its waves in the order shown; downstream Kafka consumption is asynchronous and is not a cross-service ordering guarantee.

The endpoints are synchronous: **HTTP 200** is returned only after every upstream POST has received **HTTP 202**. The JSON response contains `scenario`, `traceId`, `eventCount`, and `eventIds`. At the defaults, eventCount is 3, 5, 5, or 15 respectively; retain the returned traceId and IDs for verification. This confirms event-service acknowledgement, not completed consumer processing, database persistence, or incident creation.

An upstream failure or interruption returns **HTTP 502 ProblemDetail** with `scenario`, `traceId`, `acceptedEventCount`, and `acceptedEventIds`. Only confirmed acknowledgements appear in that list; it describes partial progress, not rollback. A timeout can mean an unconfirmed event was accepted upstream. The simulator does **not** automatically retry; rerunning creates a new traceId and new eventIds and is not client-side deduplication. Stage 6 eventId idempotency does not deduplicate a newly generated scenario run.

### Verify consumption and existing incidents

Allow time for asynchronous consumption. In a request terminal, inspect the existing incident API (not a new simulator or event retrieval endpoint):

```powershell
curl.exe -i 'http://localhost:8080/api/incidents?status=OPEN'
```

Match `rootService` to order-service, payment-service, or inventory-service, rather than counting all returned incidents. Match the simulator's exact eventIds to `Accepted event` logs in event-service and `Consumed event` logs in incident-service; use the existing Stage 4 database inspection to confirm the same event_id rows and their shared trace_id. The event-service and consumer logs intentionally do not print traceId. The simulator does not query these systems itself.

With the default threshold of five, a fresh qualifying database wave can create a HIGH order-service incident; a payment wave can create a HIGH payment-service incident. With no pre-existing active incidents and clean correlation windows, a cascade can create **up to three per-root-service incidents**: CRITICAL for order-service and HIGH for payment-service and inventory-service. A shared traceId is evidence for inspection, **not implemented cross-service incident correlation**. INFO startup events do not contribute failures.

These scenarios reuse fixed service names. Existing OPEN or INVESTIGATING incidents suppress new automatic incidents for the same rootService. Sending another five errors must not create a duplicate active incident; the existing incident is not automatically updated or escalated. Earlier standalone scenarios can therefore suppress new order/payment incidents during the cascade. Do not expect every rerun to create a new incident: expectations depend on existing incidents, correlation-window state, and the configured threshold. No reset or database deletion is needed for normal verification.

### Simulator tests (no Docker)

Run the focused JUnit 5 unit/controller/configuration/delay tests:

```powershell
.\mvnw.cmd -pl service-simulator '-Dtest=SimulationServiceTest,EventServiceClientTest,SimulationControllerTest,SimulatorPropertiesTest,SimulationDelayTest' test
```

Run the entire simulator module, including SimulatorApplicationIntegrationTest using a **real local HTTP stub**, without Docker or running applications:

```powershell
.\mvnw.cmd -pl service-simulator test
```

The simulator tests cover scenario payloads and shared/fresh IDs, count/configuration validation, cascade delays, bounded HTTP calls, successful acknowledgement, and partial failure/interruption reporting. They retain JUnit 5 without Spring's JUnit 6 test extension, following the compatibility approach above. They do not prove real Kafka delivery or incident creation. The root `.\mvnw.cmd clean verify` still needs Docker Desktop for the earlier services' infrastructure integration tests.

This unauthenticated simulation HTTP API is for single-instance, localhost development use only. It is not a production deployment or a production load generator. No application service is added to Compose, and this stage adds no authentication, test-fixture/reset API, or automatic retry machinery.

## Stage 8: service health and operational APIs

incident-service now exposes read-only service inventory, derived health, and paginated historical events. Controllers return DTOs, never JPA entities. No new application or infrastructure service is required; restart incident-service with its existing PostgreSQL, Kafka, and Redis environment to load this stage.

### Health configuration and rules

Set optional overrides in the **same PowerShell terminal that starts incident-service**, alongside its existing DB/Kafka/Redis variables:

```powershell
$env:INCIDENT_HEALTH_DEGRADED_THRESHOLD = '2'
$env:INCIDENT_HEALTH_DOWN_THRESHOLD = '5'
.\mvnw.cmd -pl incident-service spring-boot:run
```

As before, Compose's `.env` does not automatically configure a native Java application. If incident-service is already running, stop that known foreground application with Ctrl+C before restarting it. Do not start another instance on the occupied port.

| Setting | Default | Meaning |
| --- | --- | --- |
| incident.health.degraded-threshold / INCIDENT_HEALTH_DEGRADED_THRESHOLD | 2 | Minimum recent error count for DEGRADED |
| incident.health.down-threshold / INCIDENT_HEALTH_DOWN_THRESHOLD | 5 | Minimum recent error count for DOWN |

Both thresholds must be positive, and down-threshold must exceed degraded-threshold; invalid configuration fails startup validation. These are independent of incident.correlation.threshold and incident.correlation.window-seconds.

Default rules, in priority order:

1. Any OPEN or INVESTIGATING HIGH/CRITICAL incident for the service means **DOWN**, even with zero recent errors.
2. Five or more errors in the last minute means **DOWN**.
3. Two through four means **DEGRADED**.
4. Zero or one means **HEALTHY**.

Only ERROR and CRITICAL events count. Resolved incidents do not count as active. LOW/MEDIUM active incidents contribute to activeIncidentCount but do not override the error-based status.

This is an event-derived operational assessment, **not an HTTP reachability or heartbeat probe**. An idle known service can be HEALTHY, and an unresolved severe incident keeps it DOWN after its error window expires. lastEventTimestamp is the greatest stored source event timestamp; it is not a last-successful-probe timestamp.

### Read the service APIs

With incident-service running on its default port:

```powershell
curl.exe -i http://localhost:8080/api/services
curl.exe -i http://localhost:8080/api/services/order-service/health
curl.exe -i 'http://localhost:8080/api/services/order-service/events?page=0&size=20'
```

In Postman, use GET with those same URLs and no request body.

- `/api/services` returns an alphabetically ordered array of health DTOs for distinct service names found in PostgreSQL event history or incident roots. There is no hardcoded three-service registry; before any event/incident exists, the array is empty.
- `/api/services/{serviceName}/health` returns serviceName, status, errorsLastMinute, activeIncidentCount, and lastEventTimestamp. A service known only from incidents has a null lastEventTimestamp.
- `/api/services/{serviceName}/events` returns serviceName, events, page, size, totalElements, and totalPages. Each event DTO contains eventId, serviceName, eventType, severity, message, timestamp, traceId, and processedAt. Stored metadata is not added to this response. Events are sorted by source timestamp descending, then processedAt descending, then eventId ascending to break ties.
- History defaults to page=0 and size=20. Page must be nonnegative and size must be 1..100. Invalid pagination or blank/overlong service names return 400; names are matched literally and case-sensitively. Unknown services return 404 for both health and events. A known service with no events, or a page beyond the end, returns 200 with an empty events array.
- PostgreSQL/Redis operational query failures return sanitized 503 ProblemDetail responses, not a fabricated HEALTHY result or connection details.

The minute counter is a separate Redis sorted set, `health-errors:{serviceName}`, containing unique eventId members scored with Redis processing time. Eligible event processing updates it atomically alongside the existing Stage 5 correlation keys. Its TTL is 60 seconds after the latest eligible delivery; health reads filter the last minute using Redis server time without changing expiry or the correlation state. This works even when the configurable correlation window is shorter or longer than one minute, and even when no new event arrives to prune old scores. Existing database eventId deduplication and Redis member deduplication still apply.

Recent health state is transient, not PostgreSQL history. It warms from newly processed distinct failures after this upgrade or a Redis restart; historical rows and ignored duplicate deliveries do not rebuild it. Existing active severe incidents still override a zero minute count. The earlier DB/Redis non-atomic-transaction limitations also apply to this additional Redis view.

### Safely exposed Actuator endpoints

```powershell
curl.exe -i http://localhost:8080/actuator/health
curl.exe -i http://localhost:8080/actuator/info
curl.exe -i http://localhost:8080/actuator/metrics
curl.exe -i http://localhost:8080/actuator/metrics/jvm.memory.used
```

The existing Actuator dependency is retained. Endpoint access defaults to none, with only health/info/metrics granted read-only access and included in the HTTP allowlist. JMX exposure and the `/actuator` discovery page are disabled. Health component details are hidden; Boot may still advertise nonsensitive liveness/readiness group names. Default info contributors are disabled, so `/actuator/info` returns an empty object rather than environment, build, Git, Java, or OS details.

`/actuator/env`, `/actuator/configprops`, `/actuator/beans`, `/actuator/mappings`, `/actuator/loggers`, `/actuator/heapdump`, `/actuator/threaddump`, and `/actuator/shutdown` are not exposed. No wildcard HTTP exposure is configured. These development APIs remain unauthenticated: keep access on localhost/a trusted private network; this allowlist is not a substitute for production authentication and network controls.

### Verify Stage 8

1. Run the Stage 7 normal scenario once to register all three services through the existing Kafka pipeline. Wait for their matching consumed-event logs, then GET `/api/services` and each service's health/history. INFO events add history but not errors. Existing severe active incidents can still make a service DOWN.
2. Run the database-failure scenario once. After its events are consumed, inspect order-service health and history; match the returned eventIds to the stored history. With five recent errors and default health thresholds, status is DOWN. The simulator response alone does not prove consumer processing.
3. Inspect `health-errors:{order-service}` in Redis promptly. Do not confuse it with the configurable correlation key `errors:{order-service}`. After a minute without new qualifying failures, errorsLastMinute falls to zero. If a severe active incident remains, status stays DOWN until that incident is intentionally resolved.
4. Check the three permitted Actuator endpoints; confirm sensitive routes such as `/actuator/env` and `/actuator/configprops` return 404. Invalid history size=101 returns 400; a never-observed service returns 404.

Focused health/configuration/controller/service unit tests need no Docker:

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=HealthStatusCalculatorTest,HealthPropertiesTest,ServiceOperationsServiceTest,ServiceControllerTest' test
```

Real Redis and PostgreSQL/Kafka-backed operational HTTP tests require Docker Desktop, but neither Compose nor your normal applications need to be running. Testcontainers creates separate disposable infrastructure:

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=RedisFailureCounterIntegrationTest,OperationalEndpointsIntegrationTest' test
.\mvnw.cmd test
```

## Stage 9: Diagnostics MCP Server (no LLM)

diagnostics-mcp-server is a separate Spring Boot application using the Spring AI **2.0.1** MCP WebMVC starter with **Streamable HTTP**, not the legacy SSE-only transport. The Spring AI BOM is imported only in this module; Java 21, Spring Boot 4.0.9, and JUnit 5.13.4 remain unchanged. No chat model, LLM provider, API key, database driver, Redis client, or Kafka client is configured in the diagnostics service.

```text
MCP client -> POST /mcp -> @McpTool adapter -> DiagnosticsService
          -> IncidentServiceClient -> incident-service REST APIs
                                    -> existing PostgreSQL/Redis ownership
```

### Run in a fourth Windows PowerShell terminal

Keep incident-service running with its existing dependencies for live tool results. The diagnostics process needs only the incident-service origin, not its DB/Kafka/Redis credentials:

```powershell
cd 'C:\Users\Shiva Sravani\Desktop\incident-response'
$env:INCIDENT_SERVICE_BASE_URL = 'http://localhost:8080'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8084'
.\mvnw.cmd -pl diagnostics-mcp-server spring-boot:run
```

The origin must be absolute HTTP(S), with no credentials, API path, query, or fragment. For example, use `http://localhost:8080`, **not** `http://localhost:8080/api/incidents`. Compose's `.env` does not populate this native Java process. No new Compose service or application container is introduced.

MCP endpoint: `http://127.0.0.1:8084/mcp`. Local server health:

```powershell
curl.exe -i http://127.0.0.1:8084/actuator/health
```

This checks the MCP application's health, not upstream availability or successful tool execution. The server can start while incident-service is unavailable; tool calls then report structured errors. Diagnostics Actuator exposure is limited to read-only health, with component details and JMX exposure disabled.

### Available tools and REST boundaries

All five tools use `@McpTool` with required input schemas and read-only, non-destructive, idempotent hints. incidentId must be a full canonical UUID, not an eventId or Java's shortened UUID form. serviceName must be nonblank, at most 100 characters, and safe as one URI path segment. Inputs are validated before any downstream request; tool arguments cannot supply a destination URL.

| MCP tool | Argument | Result / incident-service calls |
| --- | --- | --- |
| get_incident | incidentId | Incident DTO from GET /api/incidents/{id} |
| get_service_health | serviceName | Health DTO from GET /api/services/{name}/health |
| get_error_frequency | serviceName | Rolling 60-second error count and errors/second derived from that health DTO; no history scan |
| get_recent_errors | serviceName | ERROR/CRITICAL event DTOs whose processedAt is within the configured recent window; reads paginated GET /api/services/{name}/events |
| get_related_events | incidentId | Fetches the incident, then scans its rootService history around incident.createdAt; returns root-service/time-window matches |

Related-event matching is explicitly **ROOT_SERVICE_TIME_WINDOW**: by default, 60 seconds before through 60 seconds after the incident's creation, using processedAt. It includes all severities in that interval. It is a diagnostic heuristic, **not proven causality, an incident-event foreign key, or cross-service/trace correlation**. The incident model does not store triggering event IDs. A missing incident stops the lookup without querying unrelated service history.

Recent errors use `(query time - recent window, query time]`, based on historical processedAt rather than the source timestamp. Frequency uses the existing Redis-derived errorsLastMinute field and always declares a 60-second window. These views can differ because of configured windows, clock differences, page limits, concurrent processing, cache restart/warm-up, and the documented PostgreSQL/Redis transaction boundary; no fake equality is imposed.

### Structured success, failures, and bounded scans

Tools return an MCP CallToolResult with JSON `structuredContent` and equivalent JSON text content. The envelope contains `success`, `data`, and `error`. Success has data and a null error. Failure sets MCP `isError=true`, success=false, and an error with a stable code and safe message:

- INVALID_INPUT: invalid/missing UUID or service name; no downstream call.
- NOT_FOUND: incident-service returned 404.
- DOWNSTREAM_UNAVAILABLE: connection/timeout or unavailable incident-service.
- INVALID_DOWNSTREAM_RESPONSE: missing, malformed, or inconsistent upstream data.

Raw upstream response bodies, connection details, and stack traces are not returned as tool errors. A successful HTTP transport response from `/mcp` is not enough: inspect `result.isError` and `result.structuredContent.success`. Malformed MCP protocol requests can instead return protocol-level errors.

History scans are bounded and report scanned pages/events and a `truncated` flag. Defaults inspect at most five pages of 100 rows. If more history exists, truncated=true means relevant events may remain unexamined—even when the returned events array is empty. Do not interpret a partial scan as proof that no matching events exist. Pages are ordered by **source timestamp**, so an old processedAt on one page is not a safe reason to stop early. Results deduplicate event IDs, but pages are not a cross-request database snapshot.

| Environment variable | Default | Scope |
| --- | --- | --- |
| DIAGNOSTICS_CONNECT_TIMEOUT | 2s | Per downstream connection; positive, at most 30s |
| DIAGNOSTICS_READ_TIMEOUT | 5s | Per downstream response; positive, at most 30s |
| DIAGNOSTICS_HISTORY_PAGE_SIZE | 100 | 1..100 |
| DIAGNOSTICS_MAX_HISTORY_PAGES | 5 | 1..10 |
| DIAGNOSTICS_RECENT_ERROR_WINDOW | 60s | Positive, at most one day |
| DIAGNOSTICS_RELATED_LOOKBACK | 60s | Positive, at most one day |
| DIAGNOSTICS_RELATED_LOOKAHEAD | 60s | Zero allowed, at most one day |
| DIAGNOSTICS_MCP_REQUEST_TIMEOUT | 2m | MCP request processing budget |

The MCP client's own timeout must also accommodate the bounded REST requests. If increasing page counts or HTTP timeouts, adjust both client and MCP processing budgets appropriately. Calls are read-only; no automatic retries, incident mutations, or downstream reset actions are added.

### Invoke through an MCP client or Postman

Connect a trusted Streamable HTTP MCP client to `http://127.0.0.1:8084/mcp`, initialize the MCP session, list tools, and call the names above. There are no REST routes such as `/get_incident` or `/api/tools` on this service.

For Postman, use POST `/mcp`, Body > raw > JSON, Content-Type `application/json`, and Accept `application/json, text/event-stream`. First initialize with:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"postman-check","version":"1.0"}}}
```

Save the response's `Mcp-Session-Id` header and negotiated protocolVersion. Include `Mcp-Session-Id` and `MCP-Protocol-Version` headers on subsequent requests. Responses can be SSE messages containing JSON-RPC data, not a plain JSON response body. Send the initialized notification (without an id):

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

Then list tools and invoke one, using separate POST requests in that same session:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_service_health","arguments":{"serviceName":"order-service"}}}
```

For get_incident/get_related_events, obtain an actual incident id using the existing incident-service GET `/api/incidents` API; do not substitute a simulator eventId. Service tools require a known service: first run the normal simulator scenario and wait for consumption if the database has no service history. No LLM is needed to list or invoke tools.

### Stage 9 tests

Run all diagnostics tests, including real Streamable HTTP MCP client/server invocation against a local incident-service HTTP contract stub:

```powershell
.\mvnw.cmd -pl diagnostics-mcp-server test
```

These tests need neither Docker, running applications, nor API keys. They exercise actual MCP discovery/invocation, not just Java method calls. The local REST stub does not independently prove live PostgreSQL/Redis behavior; the earlier modules retain their real infrastructure integration tests. Run the full reactor with Docker Desktop ready:

```powershell
.\mvnw.cmd test
```

The MCP endpoint is unauthenticated and binds to loopback by default. Its read-only hints describe tool intent; they are not authentication. Use only trusted local clients, treat returned operational text as untrusted data, and add an authentication/network security boundary before any nonlocal deployment. No LLM, autonomous investigation, production authentication, or remote deployment is introduced in Stage 9.

## Stage 10: AI Incident Analysis Service

ai-analysis-service uses Spring AI 2.0.1 and an OpenAI ChatModel to synthesize a grounded incident analysis. Its diagnostic reads go through the Stage 9 Streamable HTTP MCP server only: it has no incident-service REST client, PostgreSQL/Redis/Kafka client, repository, or incident mutation operation. Earlier modules, detection rules, and framework versions are unchanged.

```text
POST /api/analysis/incidents/{incidentId}
  -> IncidentAnalysisService -> read-only MCP diagnostics -> incident-service
  -> bounded OpenAI synthesis / optional diagnostic tool-call rounds
  -> validated analysis DTO (no persistence or automated remediation)
```

### Investigation, grounding, and read-only policy

The application requires a canonical incident UUID and obtains get_incident, get_related_events, and get_service_health before invoking the model. Recent errors are retrieved when current errors or incomplete/empty related history make them useful. Missing incidents and mandatory MCP failures stop before any model call. Each analysis uses a separate MCP session and pins its scope to that incident and its rootService. Spring AI supplies MCP support, with its underlying SDK client initialized lazily by the scoped gateway; global client auto-registration is disabled so arbitrary server tools are not imported into the model.

The system instructions require the model to separate **Observed Evidence**, **Probable Cause**, and **Recommendations**; never invent facts; treat incident/event text as untrusted data rather than instructions; and acknowledge uncertainty, time-window differences, and temporal—not causal—event matching. The model can request additional diagnostic reads through a bounded tool-calling loop. Only the five Stage 9 read-only tool names are available; arguments are restricted to the requested incident/root service. Unknown/mutating operations, extra arguments, and cross-incident/service requests are rejected before execution. Recommendations are returned as text, never executed.

The structured response contains:

- incidentId: the requested incident UUID.
- probableCause: an LLM-generated hypothesis, not a verified root cause.
- confidence: LOW, MEDIUM, or HIGH; incomplete diagnostic coverage forces LOW.
- evidence: server-captured observations with IDs, tool names, and tool data. The model references evidence IDs; it cannot author or replace these observed facts. Fabricated or mismatched references cause a failed response, not fake evidence.
- recommendedActions: proposed human-reviewed next steps, separate from observations.
- limitations: coverage and interpretation caveats.

Schema and evidence-reference validation do **not** mathematically prove the model's free-text reasoning. Human review is still required. Stage 9 related events remain root-service/time-window matches, and current health can differ from health at the original incident time. No fact is manufactured when diagnostics or model output is unavailable.

### Start in a fifth Windows PowerShell terminal

Keep incident-service (8080), diagnostics-mcp-server (8084), and incident-service's existing infrastructure running. Event-service and the simulator are only needed to generate additional data, not to analyze an existing incident.

OpenAI requests send diagnostic operational data, including selected incident/event text, to OpenAI and may incur charges. Choose an account-accessible Chat Completions model that supports function calling. Model selection is explicit rather than silently choosing a model/cost for you.

```powershell
cd 'C:\Users\Shiva Sravani\Desktop\incident-response'
$env:DIAGNOSTICS_MCP_BASE_URL = 'http://127.0.0.1:8084'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8085'
$env:OPENAI_MODEL = Read-Host 'OpenAI Chat Completions model ID available to your account'
$secret = Read-Host 'OpenAI API key (hidden input)' -AsSecureString
$env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
Remove-Variable secret
.\mvnw.cmd -pl ai-analysis-service spring-boot:run
```

Do not paste a real key into source, README, shell commands/history, screenshots, or chat. Set it only in the launching process/IDE environment. Both root `.env.example` and `ai-analysis-service/.env.example` contain blank credential placeholders; real `.env` files are ignored. As with the other modules, Spring Boot does not automatically load `.env`. Remove the key from the terminal when finished:

```powershell
Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
```

Only the AI application's read-only Actuator health endpoint is exposed; sensitive management endpoints and JMX are disabled. Actuator UP does not independently establish MCP availability, OpenAI authorization, billing access, or successful model output.

### Request an analysis in Postman

1. GET `http://localhost:8080/api/incidents` and copy an actual incident id, not a ServiceEvent eventId.
2. Use POST `http://127.0.0.1:8085/api/analysis/incidents/PASTE_INCIDENT_ID`, no request body.
3. This request can call the real OpenAI API. A successful result is HTTP 200 with the fields above. Inspect observed evidence and limitations before accepting the probable cause or recommendations.

The AI service handles MCP initialization/session headers internally. Unlike manual Stage 9 calls, this REST endpoint needs no MCP headers or tools/call JSON. No analysis/incident is stored or modified by this request.

| Response | Meaning |
| --- | --- |
| 400 | Malformed/shortened incident UUID; no MCP/model call |
| 404 | Incident not found through MCP; no model call |
| 503 | MCP/diagnostic evidence or LLM unavailable; sanitized ProblemDetail |
| 502 | Malformed model output, unsupported evidence references, disallowed tool calls, or exhausted investigation budget |

Errors do not return raw provider responses, credentials, connection details, or stack traces. There is no silent fallback to an invented analysis.

### Diagnose model failures safely

`LLM_UNAVAILABLE` is deliberately generic: it can represent a provider exception, a missing generation, or an empty assistant response. After restarting the AI service and reproducing the request once, inspect its terminal for one of these diagnostic markers:

- `MODEL_CALL_FAILED`: exception class and upstream HTTP status when available; network/client errors can have an unknown status.
- `MODEL_EMPTY_RESPONSE`: an allowlisted finish reason, `unknown`, or `missing_result`.

These warnings do not include exception messages, stack traces, provider bodies/headers, prompts, credentials, or incident data. Share only the diagnostic marker line when troubleshooting. An accepted API key or an UP health endpoint does not establish that a particular model request succeeds. Do not enable request-body/header wire logging to investigate authentication failures.

### Bounded configuration

| Environment setting | Default / requirement |
| --- | --- |
| OPENAI_API_KEY | Required for real OpenAI use; no key default |
| OPENAI_MODEL | Explicit Chat Completions model ID; no model default |
| DIAGNOSTICS_MCP_BASE_URL | Required MCP origin only, without /mcp, credentials, query, or fragment |
| OPENAI_REQUEST_TIMEOUT | 60s per provider request |
| OPENAI_MAX_COMPLETION_TOKENS | 2048 per model response |
| ANALYSIS_MCP_CONNECT_TIMEOUT / ANALYSIS_MCP_REQUEST_TIMEOUT | 3s / 15s; positive, at most 60s |
| ANALYSIS_MAX_MODEL_ROUNDS | 3; permitted 1..5 |
| ANALYSIS_MAX_TOOL_CALLS | 8 including mandatory preflight; permitted 4..20 |
| ANALYSIS_MAX_EVIDENCE_CHARACTERS | 64000 total; permitted 1024..256000 |
| ANALYSIS_MAX_MODEL_RESPONSE_CHARACTERS | 16000; permitted 1024..64000 |

Provider retries are disabled to avoid hidden repeated model charges. Limits bound individual calls/rounds, not a single global HTTP deadline; configure your calling client's timeout accordingly. Oversized evidence is rejected instead of silently clipping facts. These limits and read-only checks are enforced in code, not solely by prompt wording. The API remains unauthenticated and loopback-bound for trusted local development; production authentication, rate limiting, and security controls are not implemented here.

### Automated tests: no real OpenAI requests

```powershell
.\mvnw.cmd -pl ai-analysis-service test
.\mvnw.cmd -pl ai-analysis-service clean verify
```

Unit tests and REST-only integration tests mock ChatModel, disable the OpenAI provider in their HTTP contexts, and need no API key. They cover valid grounded analysis, actual bounded tool continuations, missing incidents, MCP/model outages, structured output validation, unsupported evidence, read-only scope, and the REST response/error mapping. Separate gateway tests exercise the real MCP client against a private local protocol fixture.

OpenAiClientContractTest additionally exercises the real Spring AI/OpenAI SDK against an isolated loopback HTTP fixture with synthetic evidence and an explicit noncredential placeholder. It verifies successful analysis and a tool-call continuation while preserving the configured model, provider endpoint, token limit, storage setting, and tool allowlist. It never contacts OpenAI/OpenRouter or uses a real key. Provider-specific request options are copied from the configured ChatModel; replacing them with generic tool options can cause a ClassCastException in Spring AI 2.0.1 before any provider request.

These tests do not require Docker or your running applications. The complete reactor still needs Docker Desktop for earlier infrastructure tests:

```powershell
.\mvnw.cmd test
```

### Opt-in manual integration with real OpenAI

Only this explicitly selected Maven profile runs LiveOpenAiAnalysisIT. It requires OPENAI_API_KEY, OPENAI_MODEL, DIAGNOSTICS_MCP_BASE_URL, and an existing MANUAL_ANALYSIS_INCIDENT_ID. It starts a private AI HTTP listener, but calls your configured real diagnostics server and real OpenAI model. It can transmit operational data and incur charges; **do not run it in normal CI**. No real provider call is required to pass ordinary test/verify.

In a terminal configured with the hidden-input key setup above (without another foreground command), set the incident ID, then run this command only when you intend real calls:

```powershell
$env:MANUAL_ANALYSIS_INCIDENT_ID = Read-Host 'Existing incident UUID for the live manual test'
.\mvnw.cmd -pl ai-analysis-service -Pmanual-openai verify
```

The integration test fails clearly for missing opt-in/required settings rather than silently pretending it called OpenAI. Its live IT naming is excluded from ordinary Surefire execution, with an additional explicit-profile guard. No live OpenAI integration run is claimed by automated verification.

## Stage 11: Config-driven API Gateway

api-gateway is the sixth independent application. It uses Spring Cloud Gateway Server WebFlux with embedded Netty, not a Spring MVC proxy. The **gateway-only** Spring Cloud BOM **2025.1.4** supplies Gateway **5.0.4** through `spring-cloud-starter-gateway-server-webflux`. Java 21, Spring Boot 4.0.9, and JUnit 5.13.4 remain pinned; no shared framework upgrade is introduced.

```text
Client / Postman -> api-gateway (127.0.0.1:8081)
  /api/events/**                         -> event-service (8082) -> Kafka
  /api/incidents/** and /api/services/** -> incident-service (8080) -> PostgreSQL / Redis
  /api/analysis/**                       -> ai-analysis-service (8085)
                                            -> diagnostics MCP (8084) -> incident-service (8080)
                                            -> configured model provider
  /simulate/**                           -> service-simulator (8083) -> event-service
```

The gateway forwards HTTP requests only. It never directly accesses PostgreSQL, Redis, Kafka, MCP, or an LLM. Existing internal ports and service-to-service URLs are unchanged; the simulator still calls event-service directly, diagnostics still calls incident-service directly, and analysis still uses MCP directly. Clients can continue using the existing service ports. This is a convenient entry point, **not network isolation or authentication**.

### Start in a new Windows PowerShell terminal

Use a separate terminal at the repository root. All four destination variables are required, even if you only plan to exercise one route. Set each explicitly; variables from the other service terminals do not carry over:

```powershell
Set-Location 'C:\Users\Shiva Sravani\Desktop\incident-response'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '8081'
$env:GATEWAY_EVENT_SERVICE_URL = 'http://127.0.0.1:8082'
$env:GATEWAY_INCIDENT_SERVICE_URL = 'http://127.0.0.1:8080'
$env:GATEWAY_ANALYSIS_SERVICE_URL = 'http://127.0.0.1:8085'
$env:GATEWAY_SIMULATOR_URL = 'http://127.0.0.1:8083'
$env:GATEWAY_CONNECT_TIMEOUT_MS = '2000'
$env:GATEWAY_RESPONSE_TIMEOUT = '5m'
.\mvnw.cmd -pl api-gateway spring-boot:run
```

Keep this terminal open; Ctrl+C stops only the gateway. Spring Boot does not load root or module `.env` files automatically. No database credentials, broker/cache settings, MCP URL, model name, or API key are needed in this terminal. Start the downstream applications separately using their earlier sections when you need their routes. The gateway does not launch them or replace their infrastructure prerequisites.

SERVER_ADDRESS defaults to `127.0.0.1` and SERVER_PORT to `8081`. The four destination URLs above are local examples, not fallback defaults. If a downstream port changes, update its corresponding URL before restarting the gateway. GATEWAY_CONNECT_TIMEOUT_MS defaults to `2000` milliseconds; GATEWAY_RESPONSE_TIMEOUT defaults to `5m` to accommodate bounded analysis calls. Configure a caller's own timeout accordingly. Increasing a gateway timeout does not change downstream budgets or guarantee provider success.

### Routing table

Routes are configured in `api-gateway/src/main/resources/application.yaml`. Paths and query parameters are forwarded without stripping prefixes or rewriting paths; the existing downstream request/response contracts still apply.

| Incoming gateway path | Destination variable | Local example |
| --- | --- | --- |
| `/api/events/**` | GATEWAY_EVENT_SERVICE_URL | `http://127.0.0.1:8082` |
| `/api/incidents/**` | GATEWAY_INCIDENT_SERVICE_URL | `http://127.0.0.1:8080` |
| `/api/services/**` | GATEWAY_INCIDENT_SERVICE_URL | `http://127.0.0.1:8080` |
| `/api/analysis/**` | GATEWAY_ANALYSIS_SERVICE_URL | `http://127.0.0.1:8085` |
| `/simulate/**` | GATEWAY_SIMULATOR_URL | `http://127.0.0.1:8083` |

There is no catch-all proxy, MCP proxy, or downstream Actuator proxy. Only the gateway's own read-only `/actuator/health` is exposed. Gateway administration, metrics, environment/configuration endpoints, and JMX exposure are not enabled. No automatic retry filters, authentication, rate limits, service discovery, or circuit breakers are added.

### Safe GET smoke checks in Postman

These are instructions for manual verification, **not a claim that the agent ran a smoke test against live applications**. Use GET and no request body:

1. With the gateway running, request `http://127.0.0.1:8081/actuator/health`. Expect HTTP 200 and status UP for a healthy gateway process. This does **not** prove any destination is running, that its database/broker/cache is available, or that MCP/OpenAI calls will succeed.
2. With incident-service and its existing infrastructure running, request `http://127.0.0.1:8081/api/incidents`. Expect HTTP 200 and the existing incident array, which may be empty. Optional: GET `http://127.0.0.1:8081/api/incidents?status=OPEN` to check query forwarding without changing data.
3. GET `http://127.0.0.1:8081/api/services`. Expect HTTP 200 and the existing service inventory array, which may be empty. This is event-derived operational health, not a gateway reachability probe.
4. If the inventory already contains a service, copy its exact name and GET its `/api/services/{serviceName}/health` and `/api/services/{serviceName}/events?page=0&size=20` through port 8081. Unknown services return the downstream 404; do not generate events just to make this read-only smoke check pass.
5. GET `http://127.0.0.1:8081/actuator/env`, `/actuator/metrics`, `/actuator/gateway/routes`, and `/mcp` (each on the same gateway origin). These unexposed/unrouted paths should return 404, not downstream administration or MCP data.

**Do not use POST as a harmless connectivity check.** POST `/api/events` and POST `/simulate/**` can publish real development events and trigger incident creation. POST `/api/analysis/incidents/{incidentId}` can send operational data to the real model provider and incur charges. Forwarding those requests does not make them safer; only use the earlier stage contracts when you intentionally want those effects. There is still no event-retrieval GET endpoint or analysis-list GET endpoint.

### Downstream failures and health semantics

A destination can be unavailable while gateway health remains UP. Requests to that destination then fail with an HTTP error; connection failures and response timeouts are not successful forwarding, and failure status can depend on the failure type. Successful HTTP transport also does not prove asynchronous Kafka consumption, incident persistence, or correct model reasoning. Inspect the routed response using the downstream stage's documented semantics.

The gateway does not synthesize a successful fallback, and no Retry filter is configured. A timeout or disconnected caller does not prove that a downstream POST had no effect, nor does it cancel work already accepted downstream. Do not blindly resend an event, rerun a simulation, or repeat a potentially chargeable analysis. Preserve event IDs and check existing evidence where appropriate; existing eventId idempotency does not deduplicate a new simulator invocation.

### Gateway-only tests and verification

From the repository root in Windows PowerShell:

```powershell
.\mvnw.cmd -pl api-gateway test
.\mvnw.cmd -pl api-gateway clean verify
```

Gateway tests use private loopback downstream HTTP stubs, not your running applications. They need no Docker, API key, real MCP server, or live model/provider calls. Full reactor tests still require Docker Desktop for earlier modules' infrastructure tests:

```powershell
.\mvnw.cmd test
```

No Compose application service or infrastructure change is introduced. This unauthenticated gateway is for trusted local development; binding it to loopback does not secure existing downstream listeners. Production authentication, network controls, and deployment remain outside Stage 11.

## Stage 12: Bounded diagnostic-read resilience

Stage 12 adds Spring Cloud CircuitBreaker and Resilience4j **only at two read boundaries**. The Spring Cloud BOM **2025.1.4** is imported locally by diagnostics-mcp-server and ai-analysis-service, matching the gateway's existing BOM. It resolves Spring Cloud CircuitBreaker **5.0.3** and Resilience4j **2.3.0**. Java 21, Spring Boot 4.0.9, JUnit 5.13.4, and Spring AI 2.0.1 stay pinned. No root-wide Spring Cloud BOM or infrastructure change is introduced. This section supersedes the Stage 9 no-automatic-retry description only for the diagnostic reads below; Stage 11's gateway no-Retry policy remains unchanged.

| Process / named circuit | Protected work | Retry policy |
| --- | --- | --- |
| diagnostics-mcp-server / `incident-service-read` | One incident-service REST GET, including one history page | Transient I/O and HTTP 408, 429, 500, 502, 503, 504 only; a fresh HTTP request on every attempt |
| ai-analysis-service / `diagnosticsMcpReads` | Scoped calls to the five static Stage 9 read-only tools | Typed transient I/O/timeout failures only; no retry of a valid MCP tool-error envelope |

Although MCP uses HTTP POST, these five allowlisted tools are semantically read-only: `get_incident`, `get_service_health`, `get_error_frequency`, `get_recent_errors`, and `get_related_events`. This does **not** authorize retrying arbitrary POSTs. Scope, canonical UUID, allowlist, and tool-budget checks run before resilience. MCP SDK 2 does not preserve typed raw HTTP 5xx status in this path: those failures return a controlled error, not a guessed retry based on raw message text. A valid MCP tool-error envelope is not retried, even if it reports downstream unavailability; this avoids multiplying incident-service GET retries.

Domain 404s, application-payload validation failures, and authentication/authorization errors are not retried and do not count as availability failures. A malformed MCP transport/protocol response can still count as an unavailable dependency; its raw text is never used to guess whether to retry. Retries never restart a whole history scan, diagnostic tool on the diagnostics server, analysis request, or model round. There are no new retries for event publication, simulator writes, gateway routes, or model/provider calls, and no Kafka, Redis, or database policy changes. Provider retries remain disabled: no hidden repeated paid model calls.

### Deadlines, concurrency, and circuit states

Each process has its own in-memory circuit; state is not shared across applications or persisted. CircuitBreaker surrounds Retry, and a TimeLimiter bounds the **full logical operation**, including attempts and backoff. The default is **two total attempts (one retry)** with a fixed 100ms delay. Execution uses an explicit fixed executor (four workers by default) with **zero queue**; excess work fails in a controlled way rather than accumulating. Spring Cloud bulkhead autowrapping is disabled so it does not add another executor or queue.

- **CLOSED:** calls are allowed. A count-based window keeps the last ten logical call outcomes; at least five recorded calls are needed before a failure rate of 50% or more opens the circuit. Individual retry attempts are not separate circuit calls.
- **OPEN:** protected work is rejected without contacting the dependency. The default wait before a recovery probe is ten seconds.
- **HALF_OPEN:** one probe is permitted by default. Success closes the circuit; an availability failure opens it again.

The diagnostics deadline defaults to **8s per REST GET**, including retries and delay—not per complete paginated scan. The AI deadline defaults to **20s per scoped diagnostic operation**. MCP initialize plus tool discovery is also bounded by 20s by default and participates in the circuit, so repeated initialization failures can open it. Initialization has **no application retry** because it creates protocol session state. Session cleanup is immediate/local best effort; it must not turn an outage into another long network wait.

These are **per-boundary deadlines, not one global HTTP analysis deadline**. Existing HTTP connection/read timeouts, MCP transport timeouts, investigation budgets, and model request timeout still apply. A TimeLimiter timeout cancels its future but cannot guarantee that an upstream handler has stopped. Remaining local in-flight work is bounded by the executor limit and transport timeouts; a timeout is not proof that all remote work was cancelled.

### External configuration

Set optional overrides in the **same PowerShell terminal that starts the relevant application**, before startup; for example, `$env:ANALYSIS_RESILIENCE_OPERATION_TIMEOUT = '20s'`. Spring Boot does not load `.env` automatically. Root `.env.example` documents both boundaries; `ai-analysis-service/.env.example` documents only the AI boundary. Leave key/model placeholders blank and supply real credentials only through the launching environment.

The table maps `diagnostics.resilience` and `analysis.resilience` properties to their exact environment names:

| Property suffix (under either prefix) | Diagnostics environment variable | Analysis environment variable | Default diagnostics / analysis |
| --- | --- | --- | --- |
| `operation-timeout` | `DIAGNOSTICS_RESILIENCE_OPERATION_TIMEOUT` | `ANALYSIS_RESILIENCE_OPERATION_TIMEOUT` | `8s` / `20s` |
| `max-attempts` | `DIAGNOSTICS_RESILIENCE_MAX_ATTEMPTS` | `ANALYSIS_RESILIENCE_MAX_ATTEMPTS` | `2` / `2` |
| `retry-delay` | `DIAGNOSTICS_RESILIENCE_RETRY_DELAY` | `ANALYSIS_RESILIENCE_RETRY_DELAY` | `100ms` / `100ms` |
| `sliding-window-size` | `DIAGNOSTICS_RESILIENCE_SLIDING_WINDOW_SIZE` | `ANALYSIS_RESILIENCE_SLIDING_WINDOW_SIZE` | `10` / `10` |
| `minimum-number-of-calls` | `DIAGNOSTICS_RESILIENCE_MINIMUM_NUMBER_OF_CALLS` | `ANALYSIS_RESILIENCE_MINIMUM_NUMBER_OF_CALLS` | `5` / `5` |
| `failure-rate-threshold` | `DIAGNOSTICS_RESILIENCE_FAILURE_RATE_THRESHOLD` | `ANALYSIS_RESILIENCE_FAILURE_RATE_THRESHOLD` | `50` / `50` (percent) |
| `wait-duration-in-open-state` | `DIAGNOSTICS_RESILIENCE_WAIT_DURATION_IN_OPEN_STATE` | `ANALYSIS_RESILIENCE_WAIT_DURATION_IN_OPEN_STATE` | `10s` / `10s` |
| `permitted-calls-in-half-open-state` | `DIAGNOSTICS_RESILIENCE_PERMITTED_CALLS_IN_HALF_OPEN_STATE` | `ANALYSIS_RESILIENCE_PERMITTED_CALLS_IN_HALF_OPEN_STATE` | `1` / `1` |
| `max-concurrent-calls` | `DIAGNOSTICS_RESILIENCE_MAX_CONCURRENT_CALLS` | `ANALYSIS_RESILIENCE_MAX_CONCURRENT_CALLS` | `4` / `4` |

Invalid settings fail startup validation. Attempts must be 1..3 (1 disables retries), concurrency 1..32, and minimum calls must not exceed the sliding window. Timeouts and open-state waits are positive and bounded; retry delay, window size, failure percentage, and half-open probes also have startup bounds. Increasing these settings is not a substitute for investigating a failing dependency.

### Controlled failures and safe verification

An exhausted retry, operation timeout, full executor, or open circuit does not produce stale, empty, or fabricated success evidence. Diagnostics retains its sanitized `DOWNSTREAM_UNAVAILABLE` tool error; the analysis API retains HTTP **503** with `DIAGNOSTICS_UNAVAILABLE`. Missing incidents still return **404**, not an outage fallback. Errors do not expose raw upstream messages, credentials, prompts, destination URLs, or cause stack traces.

Prefer automated tests with private local HTTP/MCP fixtures and mocked model behavior. They can simulate transient failures, timeout, saturation, and circuit recovery without stopping real backends or contacting a model provider. From the repository root in Windows PowerShell, quote the comma-separated module selector:

```powershell
.\mvnw.cmd '-pl' 'diagnostics-mcp-server,ai-analysis-service' test
```

This focused run needs no Docker, running development applications, or real API key. The full reactor includes earlier infrastructure integration tests and requires Docker Desktop:

```powershell
.\mvnw.cmd test
```

These are verification commands, not a claim of test execution or results. No real backend/model outage or paid request is needed for Stage 12 verification.

Optional manual outage observation is for a user-chosen development session only: stop **only the known incident-service or diagnostics MCP application with Ctrl+C in its own terminal**. Do not kill unrelated processes, stop infrastructure, or clear data. A stopped incident-service can be observed through the existing read-only MCP calls without an LLM; an analysis POST may incur model charges if diagnostics recovers, so it is not a harmless probe. Restart the stopped application with its earlier environment. After the circuit wait, a successful permitted probe restores calls; session reconnection may also be needed. Health UP alone still does not prove downstream recovery.

## Current boundaries

Stage 10 adds read-only, evidence-referenced OpenAI incident analysis through the existing diagnostics MCP server. Stage 11 adds only a config-driven HTTP gateway; existing services remain independently accessible and its no-Retry policy is unchanged. Stage 12 adds bounded retries, time limits, and per-process circuit breakers only for diagnostics REST GETs and scoped AI MCP diagnostic reads. No global analysis/model/gateway retry, authentication, rate limiting, or discovery is added. Earlier incident APIs, deterministic detection, health/history, simulator, eventId idempotency, and Kafka/Redis/database policies remain unchanged. Model output never creates/resolves incidents, changes service state, or executes recommendations. Temporal event matches do not prove root cause or implement cross-service incident correlation. There is no production authentication, incident-list pagination, advanced state machine, Kubernetes, or application-container deployment implementation. Existing incidents are not automatically escalated or updated. Java 21, Spring Boot 4.0.9, and JUnit 5.13.4 remain unchanged; Spring AI 2.0.1 remains scoped to the earlier AI/MCP modules. Spring Cloud 2025.1.4 is imported locally by api-gateway and, for Stage 12 resilience, diagnostics-mcp-server and ai-analysis-service—not by the root parent. No commits or pushes are made automatically.
