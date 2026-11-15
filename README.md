# incident-response-platform

A portfolio-focused distributed incident response platform. The intended system will ingest operational events, correlate failures deterministically, persist incidents, and eventually provide evidence-grounded AI investigations. Only the explicitly requested stage is implemented.

## Stage 1: Incident Service

Implemented with Java 21, Maven, Spring Boot 4.0.9, Spring MVC, Spring Data JPA, Jakarta Validation, PostgreSQL, Flyway, and Actuator. Tests use JUnit Jupiter 5.13.4, Mockito, MockMvc, and PostgreSQL Testcontainers. Framework versions are managed by the parent POM; Spring Boot has not been upgraded.

The Maven parent is named `incident-response-platform`. The existing checkout directory remains `incident-response`; there is no need to rename it to build or run the project.

```text
pom.xml                         Maven parent and module list
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

No Kafka, Redis, AI, MCP, Gateway, Docker deployment files, or Kubernetes configuration is implemented. Actuator is a dependency with only its basic health endpoint exposed; custom operational health APIs belong to a later stage.

## Prerequisites

- A full JDK 21, including `javac` (a Java runtime alone is not sufficient).
- Windows PowerShell and the included Windows Maven wrapper (`.\mvnw.cmd`). A separate Maven installation is optional.
- A reachable PostgreSQL database for running the application.
- A working Docker runtime for the default Testcontainers integration tests. This is a test prerequisite, not a Docker deployment of the platform.

Run the following in Windows PowerShell from the project directory:

```powershell
java -version
javac -version
.\mvnw.cmd -version
docker version
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
- IncidentPersistenceIntegrationTest: actual PostgreSQL migration, repository save/read/filter, and HTTP create/read/list/filter/update plus validation and not-found behavior against a running application.

The integration suite automatically starts a disposable PostgreSQL Testcontainer and stops it after the suite. It does not require production database variables. Integration tests fail rather than silently skipping when Docker is unavailable. They execute during `mvn test` as well as `mvn verify`.

For an explicitly limited unit/controller run without infrastructure:

```powershell
.\mvnw.cmd -pl incident-service '-Dtest=IncidentServiceTest,IncidentControllerTest' test
```

This focused command is not a substitute for running the full suite.

### Optional external test database

If the Java process cannot reach Docker, the same integration tests can use a dedicated disposable PostgreSQL database. Set all three variables in the current PowerShell session. Get-Credential prompts for the database username and password without putting the password in command history:

```powershell
$env:TEST_DATABASE_URL = Read-Host 'Test JDBC URL (jdbc:postgresql://host:port/database)'
$credentials = Get-Credential -Message 'Disposable test database credentials'
$env:TEST_DATABASE_USERNAME = $credentials.UserName
$env:TEST_DATABASE_PASSWORD = $credentials.GetNetworkCredential().Password
Remove-Variable credentials
.\mvnw.cmd clean verify
```

Do not point tests at a production/shared database. Flyway applies the schema, and tests create and delete their own incident records. No credentials are stored in the repository. Remove these session variables to return to Testcontainers mode:

```powershell
Remove-Item Env:TEST_DATABASE_URL, Env:TEST_DATABASE_USERNAME, Env:TEST_DATABASE_PASSWORD -ErrorAction SilentlyContinue
```

### JUnit 5 compatibility

Spring Framework 7's SpringExtension requires JUnit 6. To retain the requested JUnit 5 while using Spring Boot 4.0.9, controller tests use standalone MockMvc with the real controller/advice/validation and a mocked service. Integration tests start and close a real Spring Boot application directly via SpringApplicationBuilder and use an HTTP client plus the real repository. They do not use @WebMvcTest or @SpringBootTest. Mockito is attached as a test JVM agent, avoiding dynamic-agent attachment warnings on Java 21.

## Run the service

Create an empty PostgreSQL database and a database user with schema creation/migration permissions using your preferred PostgreSQL administration tool. Set runtime configuration in PowerShell, not in source files:

```powershell
$env:DATABASE_URL = Read-Host 'JDBC URL (jdbc:postgresql://host:port/database)'
$credentials = Get-Credential -Message 'Application database credentials'
$env:DATABASE_USERNAME = $credentials.UserName
$env:DATABASE_PASSWORD = $credentials.GetNetworkCredential().Password
Remove-Variable credentials
$env:SERVER_PORT = '8080'
.\mvnw.cmd -pl incident-service spring-boot:run
```

Flyway applies `V1__create_incidents.sql` on startup. Hibernate validates the resulting schema; it does not create or update tables. The application intentionally requires database configuration rather than guessing a connection URL or password. Spring Boot does not automatically load `.env` files; set process environment variables as above or configure them in your IDE's run configuration. `.env` and `.env.*` are ignored by Git. These PowerShell variables apply only to the current session and its child processes, not every terminal. Close the session or remove the password variables when finished.

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

## Current boundaries

This stage is synchronous and has no authentication, pagination, advanced state machine, event ingestion, automatic correlation, or AI investigation. Those capabilities are not implied by the architecture goal. No commits or remote repository have been created automatically.
