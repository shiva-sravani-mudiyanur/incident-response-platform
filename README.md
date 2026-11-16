# incident-response-platform

A portfolio-focused distributed incident response platform. The intended system will ingest operational events, correlate failures deterministically, persist incidents, and eventually provide evidence-grounded AI investigations. Only the completed stages described below are implemented.

## Current services

| Service | Default HTTP port | Purpose | Runtime dependencies |
| --- | --- | --- | --- |
| incident-service | 8080 | Manage incidents and consume/persist operational events | PostgreSQL, Kafka |
| event-service | 8082 | Validate operational events, assign missing IDs, publish JSON to Kafka, and acknowledge with 202 | Kafka |

Both modules use Java 21 and Spring Boot 4.0.9, inherited from the root Maven POM. Each runs as its own application in a separate terminal. SERVER_PORT can override its default port. Stage 4 connects them through Kafka: posting an event stores an incident_events row asynchronously, but does not create an incident.

## Stages 1–2: Incident Service and PostgreSQL Persistence

Implemented with Java 21, Maven, Spring Boot 4.0.9, Spring MVC, Spring Data JPA, Jakarta Validation, PostgreSQL, Flyway, and Actuator. Tests use JUnit Jupiter 5.13.4, Mockito, MockMvc, and PostgreSQL Testcontainers. Framework versions are managed by the parent POM; Spring Boot has not been upgraded.

The Maven parent is named `incident-response-platform`. The existing checkout directory remains `incident-response`; there is no need to rename it to build or run the project.

```text
pom.xml                         Maven parent and module list
docker-compose.yml              PostgreSQL, KRaft Kafka, and topic initialization
.env.example                    Optional Compose configuration template; no real password
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
```

Request flow:

```text
HTTP -> IncidentController -> IncidentService -> IncidentRepository -> PostgreSQL
        request DTOs          response DTO mapping                Flyway schema
```

JPA entities are never returned from controllers. Creation assigns a UUID, OPEN status, and UTC creation/update timestamps on the server. The caller supplies title, severity, rootService, and errorCount. Timestamp precision is normalized to PostgreSQL microseconds so a creation response matches subsequent retrieval. Status updates preserve identity, creation time, and other incident fields. All three defined statuses are accepted; no transition restrictions are introduced in this stage.

Stage 2 added PostgreSQL persistence; Stage 4 adds KRaft Kafka and a topic initializer to Compose. Both Java applications still run natively on Windows, not in containers. Redis, AI, MCP, Gateway, and Kubernetes remain unimplemented. Only basic Actuator health is exposed; custom diagnostic APIs belong to a later stage.

## Prerequisites

- A full JDK 21, including `javac` (a Java runtime alone is not sufficient).
- Windows PowerShell and the included Windows Maven wrapper (`.\mvnw.cmd`). A separate Maven installation is optional.
- Docker Desktop with Docker Compose, for PostgreSQL/Kafka and real Testcontainers integration tests. No local PostgreSQL or Kafka installation is needed.

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

Integration tests start disposable Kafka and/or PostgreSQL Testcontainers and stop them afterward. They use random mapped ports and generated database credentials, never H2 or a developer database/broker. The database-only regression suite disables listener startup; the dedicated Kafka suite exercises the production listener. Tests fail rather than silently skipping when Docker is unavailable. They execute during `mvn test` and `mvn verify`; no Compose startup or application environment variables are required first.

For an explicitly limited unit/controller run without infrastructure:

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=IncidentServiceTest,IncidentControllerTest' test
.\mvnw.cmd -pl incident-service '-Dtest=EventProcessingServiceTest,EventConsumerTest' test
.\mvnw.cmd -pl event-service '-Dtest=EventServiceTest,EventControllerTest,KafkaEventPublisherTest' test
```

This focused command is not a substitute for running the full suite.

### JUnit 5 compatibility

Spring Framework 7's SpringExtension requires JUnit 6. To retain the requested JUnit 5 while using Spring Boot 4.0.9, controller tests use standalone MockMvc with the real controller/advice/validation and a mocked service. Integration tests start and close a real Spring Boot application directly via SpringApplicationBuilder and use an HTTP client plus the real repository. They do not use @WebMvcTest or @SpringBootTest. Mockito is attached as a test JVM agent, avoiding dynamic-agent attachment warnings on Java 21.

## Start PostgreSQL/Kafka and run incident-service

Start Docker Desktop in Linux-container mode. From Windows PowerShell, open the repository root (the directory containing docker-compose.yml). Configure the database and Kafka settings in that same terminal. Reuse your existing database credentials if its persistent volume already exists:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5433'
$env:DB_NAME = 'incident_response'
$credentials = Get-Credential -UserName 'incident_app' -Message 'Choose credentials for the Docker development database'
$env:DB_USERNAME = $credentials.UserName
$env:DB_PASSWORD = $credentials.GetNetworkCredential().Password
Remove-Variable credentials
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
```

These are local-development examples. DB_HOST identifies the database host, DB_PORT is its published host port, DB_NAME is the database name, and DB_USERNAME/DB_PASSWORD are its login credentials. No values are hardcoded in application.yaml. DB_PORT=5433 avoids the Windows PostgreSQL service commonly listening on 5432; use another available port if needed. The old DATABASE_URL, DATABASE_USERNAME, and DATABASE_PASSWORD settings have been replaced by DB_*.

Start and check both infrastructure services, then create the topic idempotently:

```powershell
docker compose config --quiet
docker compose up --wait --wait-timeout 120 postgres kafka
docker compose run --rm kafka-init
docker compose ps
docker compose logs postgres
```

Both postgres and kafka must show healthy. PostgreSQL is published only on loopback, maps DB_PORT to container port 5432, and uses a persistent named volume. Kafka is a single combined KRaft broker/controller, with no ZooKeeper. Its external listener uses localhost:9092 by default; the internal Compose listener is kafka:19092. The one-shot kafka-init creates service-events with three partitions and replication factor one; it is safe to rerun. Plain `docker compose up` also starts the initializer and stays attached to logs. The explicit two-command sequence above waits only on long-running services before running the short-lived initializer.

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

Set your own nonempty DB_PASSWORD in .env. Never commit it; .env and .env.* are ignored, while .env.example contains no real password. Docker Compose loads .env, but Spring Boot does not. Set DB_* and KAFKA_BOOTSTRAP_SERVERS in the incident-service terminal/IDE, and KAFKA_BOOTSTRAP_SERVERS in the event-service terminal/IDE. Shell environment values override Compose .env values. Do not share rendered `docker compose config` output because it includes resolved secrets; use `config --quiet`. If changing KAFKA_PORT, also update KAFKA_BOOTSTRAP_SERVERS. KAFKA_EVENTS_TOPIC must match between both services and the initializer. Keep KAFKA_CLUSTER_ID unchanged while reusing its volume.

Environment variables belong to the current PowerShell session and its child processes, not every terminal. Close the session or remove password variables when finished.

### Data persistence and stopping

Stop incident-service with Ctrl+C. From a terminal with the same Compose configuration:

```powershell
docker compose down
```

The postgres_data and kafka_data volumes are retained, preserving database rows, Kafka records, and consumer offsets. To restart, repeat the infrastructure commands above and start the applications. Do not use `down --volumes` unless you intentionally want to delete both data stores. Changing DB_NAME, DB_USERNAME, or DB_PASSWORD does not reinitialize an existing volume. This setup neither imports nor modifies Windows PostgreSQL data.

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

Stage 3 introduced validated acceptance. Stage 4 now publishes the normalized ServiceEvent as JSON, keyed by serviceName. A 202 confirms broker acknowledgement, not completed database processing. A failed/unconfirmed publication returns 503 rather than false acceptance. A timeout can occur after Kafka accepted a record, so retries should reuse a supplied eventId. There is no event retrieval endpoint, Redis, correlation, automatic incident creation, or inter-service HTTP call.

### Run event-service on Windows

In a separate Windows PowerShell terminal at the repository root:

```powershell
$env:SERVER_PORT = '8082'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
.\mvnw.cmd -pl event-service spring-boot:run
```

event-service needs Kafka and the topic initialized above, but no DB_* variables or running incident-service. Events remain in Kafka until incident-service consumes them. incident-service runs in its own terminal with its database and Kafka settings. Explicitly setting SERVER_PORT prevents port reuse from another service's environment. Stop either application with Ctrl+C in its own terminal.

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

Remove serviceName or severity and resend: expect 400 with an errors map naming the missing field. An empty body, malformed JSON, invalid UUID/timestamp, or unknown enum also returns a controlled 400 ProblemDetail response. To test ID preservation, include an actual valid eventId UUID and confirm the acknowledgement returns exactly that value. Posting here must not add rows to the incidents table.

### Event-service tests

Run all event-service tests, including real HTTP/Kafka Testcontainers tests (Docker required; no PostgreSQL required):

```powershell
.\mvnw.cmd -pl event-service test
```

Run just the event service/controller unit tests:

```powershell
.\mvnw.cmd -pl event-service '-Dtest=EventServiceTest,EventControllerTest,KafkaEventPublisherTest' test
```

The root command `.\mvnw.cmd clean verify` builds and tests both services with real Kafka/PostgreSQL Testcontainers. Compose runs infrastructure only; application containerization is not part of Stage 4.

## Stage 4: Kafka delivery and database verification

Serialization is configured in KafkaProducerConfiguration/KafkaConsumerConfiguration using Spring Kafka's Jackson 3 serializers and Boot's configured JSON mapper. The producer sends no Java class-name headers; the consumer always targets its own ServiceEvent DTO and ignores incoming type headers. Event-service and incident-service have no Maven dependency on each other. The consumer requires an eventId, even though HTTP requests may omit it: event-service assigns it before publication.

The incident_events table stores event_id (primary key), service_name, event_type, severity, message, timestamp, nullable trace_id, and processed_at. Source and processing instants use PostgreSQL microsecond precision. Metadata travels in Kafka JSON but is not persisted in this initial table. Nothing is added to incidents automatically.

Consumer auto-commit is disabled. RECORD acknowledgement commits after the transactional processing method returns successfully. Database failures propagate and retry indefinitely with a configurable interval; they are not intentionally skipped. Invalid DTOs and malformed JSON are logged with topic/partition/offset and skipped so a poison record does not block following valid events. There is no dead-letter topic in this stage. Logs omit raw payload/message/trace/metadata. Replayed event IDs map to the existing primary-key row; repeated deliveries may update its fields and processing timestamp. This is at-least-once processing, not an exactly-once claim or a transactional outbox.

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

## Current boundaries

Events now travel asynchronously through Kafka into incident_events; the existing incident REST API is unchanged. There is no Redis, incident correlation, automatic incident creation, AI/MCP investigation, authentication, pagination, advanced state machine, Gateway, or Kubernetes implementation. No framework versions have been upgraded, and no commits or pushes are made automatically.
