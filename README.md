# incident-response-platform

A portfolio-focused distributed incident response platform. The intended system will ingest operational events, correlate failures deterministically, persist incidents, and eventually provide evidence-grounded AI investigations. Only the explicitly requested stage is implemented.

## Stages 1–2: Incident Service and PostgreSQL Persistence

Implemented with Java 21, Maven, Spring Boot 4.0.9, Spring MVC, Spring Data JPA, Jakarta Validation, PostgreSQL, Flyway, and Actuator. Tests use JUnit Jupiter 5.13.4, Mockito, MockMvc, and PostgreSQL Testcontainers. Framework versions are managed by the parent POM; Spring Boot has not been upgraded.

The Maven parent is named `incident-response-platform`. The existing checkout directory remains `incident-response`; there is no need to rename it to build or run the project.

```text
pom.xml                         Maven parent and module list
docker-compose.yml              Local PostgreSQL with health check and persistent volume
.env.example                    Optional Compose configuration template; no real password
incident-service/
  pom.xml                       Service dependencies and executable JAR build
  src/main/java/com/umass/incident/
    controller/                 HTTP requests and responses only
    service/                    Creation, lookup, filtering, and status updates
    repository/                 Spring Data JPA persistence
    model/                      Incident entity and enums
    dto/                        Validated request and response contracts
    config/                     Injectable UTC clock
    exception/                  Controlled API errors
  src/main/resources/
    application.yaml            Environment-based runtime configuration
    db/migration/               Flyway-managed PostgreSQL schema
  src/test/java/                Unit, controller, and real PostgreSQL/HTTP tests
```

Request flow:

```text
HTTP -> IncidentController -> IncidentService -> IncidentRepository -> PostgreSQL
        request DTOs          response DTO mapping                Flyway schema
```

JPA entities are never returned from controllers. Creation assigns a UUID, OPEN status, and UTC creation/update timestamps on the server. The caller supplies title, severity, rootService, and errorCount. Timestamp precision is normalized to PostgreSQL microseconds so a creation response matches subsequent retrieval. Status updates preserve identity, creation time, and other incident fields. All three defined statuses are accepted; no transition restrictions are introduced in this stage.

Stage 2 adds a PostgreSQL-only Docker Compose service. The Java application still runs natively on Windows; it is not containerized. No Kafka, Redis, AI, MCP, Gateway, or Kubernetes configuration is implemented. Actuator is a dependency with only its basic health endpoint exposed; custom operational health APIs belong to a later stage.

## Prerequisites

- A full JDK 21, including `javac` (a Java runtime alone is not sufficient).
- Windows PowerShell and the included Windows Maven wrapper (`.\mvnw.cmd`). A separate Maven installation is optional.
- Docker Desktop with Docker Compose, for the development PostgreSQL service and real PostgreSQL Testcontainers integration tests. No local PostgreSQL installation is needed.

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

Equivalent with Maven installed:

```powershell
mvn clean verify
```

The default suite includes:

- IncidentServiceTest: creation, retrieval, not found, status update, filtering, unfiltered listing, missing-update protection, and timestamp precision.
- IncidentControllerTest: creation, retrieval, listing/filtering, status update, validation, malformed JSON/UUID/enums, missing incidents, and invalid updates.
- IncidentPersistenceIntegrationTest: actual PostgreSQL migration and DB_* configuration, repository save/retrieve/filter, persisted service status updates, transaction commit, rollback after a flushed insert, rollback after a failed flushed update, and real HTTP lifecycle/error behavior.

The integration suite always starts a real disposable PostgreSQL Testcontainer and stops it after the suite. It uses random mapped ports and generated test credentials, passing the five DB_* settings into the application under test. It never uses H2, your Compose database, or your Windows PostgreSQL data. There is no external-database fallback. Integration tests fail rather than silently skipping when Docker is unavailable. They execute during `mvn test` as well as `mvn verify`; you do not need to run `docker compose up` first.

For an explicitly limited unit/controller run without infrastructure:

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=IncidentServiceTest,IncidentControllerTest' test
```

This focused command is not a substitute for running the full suite.

### JUnit 5 compatibility

Spring Framework 7's SpringExtension requires JUnit 6. To retain the requested JUnit 5 while using Spring Boot 4.0.9, controller tests use standalone MockMvc with the real controller/advice/validation and a mocked service. Integration tests start and close a real Spring Boot application directly via SpringApplicationBuilder and use an HTTP client plus the real repository. They do not use @WebMvcTest or @SpringBootTest. Mockito is attached as a test JVM agent, avoiding dynamic-agent attachment warnings on Java 21.

## Start PostgreSQL and run incident-service

Start Docker Desktop in Linux-container mode. From Windows PowerShell, open the repository root (the directory containing docker-compose.yml). Configure these five required settings in that same terminal:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '5433'
$env:DB_NAME = 'incident_response'
$credentials = Get-Credential -UserName 'incident_app' -Message 'Choose credentials for the Docker development database'
$env:DB_USERNAME = $credentials.UserName
$env:DB_PASSWORD = $credentials.GetNetworkCredential().Password
Remove-Variable credentials
```

These are local-development examples. DB_HOST identifies the database host, DB_PORT is its published host port, DB_NAME is the database name, and DB_USERNAME/DB_PASSWORD are its login credentials. No values are hardcoded in application.yaml. DB_PORT=5433 avoids the Windows PostgreSQL service commonly listening on 5432; use another available port if needed. The old DATABASE_URL, DATABASE_USERNAME, and DATABASE_PASSWORD settings have been replaced by DB_*.

Start and check PostgreSQL:

```powershell
docker compose config --quiet
docker compose up --wait --wait-timeout 120
docker compose ps
docker compose logs postgres
```

The postgres service must show healthy. Compose publishes it only on the local loopback interface, maps the configured host port to container port 5432, and mounts a named volume at PostgreSQL 17's data directory. The health check uses pg_isready over TCP so Compose waits for the final database server, not just its initialization process. `up --wait` runs in the background and returns after readiness; plain `docker compose up` also starts PostgreSQL but stays attached to its logs.

On the first startup with an empty volume, the PostgreSQL image creates DB_NAME and the DB_USERNAME login using DB_PASSWORD. No manual database creation in pgAdmin is required. These Docker credentials are independent of any existing Windows PostgreSQL credentials. This is a local-development database; the image's initialization user is an administrator and should not be treated as a production least-privilege setup.

Keep the same PowerShell session and start the application:

```powershell
$env:SERVER_PORT = '8080'
.\mvnw.cmd -pl incident-service spring-boot:run
```

If another application uses port 8080, set SERVER_PORT to an available port such as 8081, and use that port in API requests. Keep the application terminal open and use a second terminal for HTTP requests.

Flyway applies `V1__create_incidents_table.sql` when incident-service starts; Compose alone does not create the incidents table. Hibernate uses `ddl-auto=validate`: it checks the migrated schema and never creates or updates tables. A configuration/migration/connection failure prevents startup rather than silently using another database.

### Optional Compose .env file

For Compose configuration saved between sessions, copy the example locally:

```powershell
Copy-Item .env.example .env
notepad .env
```

Set your own nonempty DB_PASSWORD in .env. Never commit it; .env and .env.* are ignored, while the password-free .env.example is safe to track. Docker Compose loads .env, but Spring Boot does not. When the application runs outside Docker, set the same five DB_* environment variables in PowerShell as above or in your IDE's run configuration. Shell environment values override Compose .env values, so keep them consistent. Passwords should not be pasted into commands or source files; do not share rendered `docker compose config` output because it includes resolved secrets. Use `config --quiet` for validation.

Environment variables belong to the current PowerShell session and its child processes, not every terminal. Close the session or remove password variables when finished.

### Data persistence and stopping

Stop incident-service with Ctrl+C. From a terminal with the same Compose configuration:

```powershell
docker compose down
```

The named postgres_data volume is retained, so the incidents remain after the next `docker compose up --wait` and application restart. Do not use `down --volumes` unless you intentionally want to delete the Docker database. Changing DB_NAME, DB_USERNAME, or DB_PASSWORD does not reinitialize an existing volume; retain the original credentials or perform a deliberate database/user migration. This Compose setup neither imports nor modifies an existing Windows PostgreSQL database.

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

## Current boundaries

This stage remains synchronous and has no authentication, pagination, advanced state machine, event ingestion, automatic correlation, or AI investigation. Those capabilities are not implied by the architecture goal. Stage 2 adds only PostgreSQL Compose infrastructure, DB_* configuration, migration naming, and persistence/transaction verification. No framework versions have been changed, and no commits or pushes are made automatically.
