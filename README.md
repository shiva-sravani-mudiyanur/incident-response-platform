# incident-response-platform

A portfolio-focused distributed incident response platform. The intended system will ingest operational events, correlate failures deterministically, persist incidents, and eventually provide evidence-grounded AI investigations. Only the completed stages described below are implemented.

## Current services

| Service | Default HTTP port | Purpose | Runtime dependencies |
| --- | --- | --- | --- |
| incident-service | 8080 | Manage incidents, persist events, and correlate failures automatically | PostgreSQL, Kafka, Redis |
| event-service | 8082 | Validate operational events, assign missing IDs, publish JSON to Kafka, and acknowledge with 202 | Kafka |
| service-simulator | 8083 | Generate development scenarios through event-service's HTTP API only | event-service over HTTP |

All three modules use Java 21 and Spring Boot 4.0.9, inherited from the root Maven POM. Each runs as its own application in a separate terminal. SERVER_PORT can override its default port. Stage 4 connects event-service and incident-service through Kafka and persists incident_events asynchronously. Stage 5 adds Redis-backed failure correlation and deterministic automatic incident creation without an LLM. Stage 7 adds an HTTP-only scenario generator; it does not implement detection or access Kafka, Redis, PostgreSQL, or the incident API directly.

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
```

Request flow:

```text
HTTP -> IncidentController -> IncidentService -> IncidentRepository -> PostgreSQL
        request DTOs          response DTO mapping                Flyway schema
```

JPA entities are never returned from controllers. Creation assigns a UUID, OPEN status, and UTC creation/update timestamps on the server. The caller supplies title, severity, rootService, and errorCount. Timestamp precision is normalized to PostgreSQL microseconds so a creation response matches subsequent retrieval. Status updates preserve identity, creation time, and other incident fields. All three defined statuses are accepted; no transition restrictions are introduced in this stage.

Stage 2 added PostgreSQL persistence; Stage 4 added KRaft Kafka and a topic initializer to Compose; Stage 5 adds transient Redis correlation state. All three Java applications run natively on Windows, not in containers; Compose still runs infrastructure only. AI, MCP, Gateway, and Kubernetes remain unimplemented. Only basic Actuator health is exposed; custom diagnostic APIs belong to a later stage.

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

If another application uses port 8080, set SERVER_PORT to an available port such as 8081, and use that port in API requests. Keep the application terminal open and use a second terminal for HTTP requests.

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

The root command `.\mvnw.cmd clean verify` builds and tests all three modules, including the Stage 7 simulator. Docker is required for the earlier infrastructure integration suites, not for simulator tests. Compose runs infrastructure only; application containerization is not part of Stage 7.

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

## Current boundaries

Stage 8 adds event-derived operational health and paginated per-service event history to incident-service. Stage 7's HTTP-only local simulator still feeds event-service; events travel asynchronously through Kafka into incident_events, and qualifying per-service failures create incidents through transient Redis correlation. The existing incident REST API and Stage 6 eventId idempotency are unchanged. Shared scenario traceIds do not implement cross-service incident correlation. There is no LLM/AI/MCP investigation, authentication, incident-list pagination, advanced state machine, Gateway, Kubernetes, or application-container deployment implementation. Existing incidents are not automatically escalated or updated. No framework versions have been upgraded, and no commits or pushes are made automatically.
