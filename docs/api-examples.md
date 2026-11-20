# API examples

These requests intentionally use Windows PowerShell. In Postman, select the same method and URL; for JSON requests use **Body → raw → JSON** and `Content-Type: application/json`. Simulation and analysis POSTs have no request body.

The examples assume the gateway's default host port. For another Compose project or Kubernetes port-forward, change the origin once and obtain incident IDs from that same deployment.

```powershell
$gateway = 'http://127.0.0.1:8081'
```

POSTs are not connectivity probes. Events/simulations can create records; analysis can invoke a paid provider. Do not automatically repeat an uncertain write or model request.

## Event ingestion

`POST /api/events` returns **202** with only `eventId` after Kafka acknowledgement. It does not confirm consumer processing or incident creation.

```powershell
$eventId = [Guid]::NewGuid().ToString()
$event = @{
    eventId = $eventId
    serviceName = 'example-service'
    eventType = 'DATABASE_ERROR'
    severity = 'ERROR'
    message = 'Database connection failed'
    timestamp = [DateTime]::UtcNow.ToString('o')
    traceId = [Guid]::NewGuid().ToString()
    metadata = @{ database = 'example-database' }
} | ConvertTo-Json -Depth 3
$accepted = Invoke-RestMethod -Uri "$gateway/api/events" -Method Post -ContentType 'application/json' -Body $event
$accepted
```

| Field | Contract |
| --- | --- |
| `eventId` | Optional UUID; generated if omitted/null. Supply and retain one when a publication may need a deliberate retry. |
| `serviceName` | Required, nonblank, up to 100 characters. |
| `eventType` | `SERVICE_STARTED`, `SERVICE_STOPPED`, `REQUEST_FAILED`, `DATABASE_ERROR`, `DEPENDENCY_ERROR`, or `TIMEOUT`. |
| `severity` | `INFO`, `WARNING`, `ERROR`, or `CRITICAL`; not the incident severity enum. |
| `message` | Required, nonblank, up to 4,000 characters. |
| `timestamp` | Required ISO-8601 instant; the service does not generate it. |
| `traceId` | Optional string, up to 128 characters. |
| `metadata` | Optional string-to-string map, at most 50 entries; nonblank keys up to 100 characters and nonnull values up to 1,000 characters. |

Enums are case-sensitive; unknown names and numeric ordinals return 400. Persisted string fields reject NUL characters. Invalid JSON, UUIDs, timestamps and validation failures return controlled 400 responses. Unconfirmed/failed Kafka publication returns 503; a timeout does not prove Kafka rejected the record.

For a deliberate duplicate check, resend the same `$event` body, preserving `$eventId`. Both ingestion attempts can return 202, but the consumer must retain one original `incident_events` row and count the event once. Reusing an ID with a different valid payload preserves the first committed payload; use a new ID for a genuinely new event. This is not an exactly-once guarantee across PostgreSQL and Redis.

There is no standalone event-retrieval API. Use the service history endpoint after asynchronous consumption; unknown services return 404 until observed or referenced by an incident.

## Simulation scenarios

Choose one intentionally; running all four writes multiple waves of events.

| POST path | Default events |
| --- | --- |
| `/simulate/normal` | Three INFO startup events: order-service, payment-service, inventory-service. |
| `/simulate/database-failure` | Five ERROR database events for order-service. |
| `/simulate/payment-failure` | Five ERROR timeout events for payment-service. |
| `/simulate/cascade-failure` | Five CRITICAL order events, five ERROR payment events, then five ERROR inventory events; default 250ms between waves. |

```powershell
$run = Invoke-RestMethod -Uri "$gateway/simulate/database-failure" -Method Post -TimeoutSec 90
$run | ConvertTo-Json -Depth 3
```

A successful **200** response contains `scenario` (for example `DATABASE_FAILURE`), `traceId`, `eventCount`, and `eventIds`. Each invocation generates new event IDs and one shared trace ID. Success means every event received an upstream 202, not that the consumer finished.

An upstream failure returns **502** with `scenario`, `traceId`, `acceptedEventCount`, and `acceptedEventIds` describing confirmed partial progress. There is no rollback or automatic retry. Rerunning a scenario creates a new wave; it is not duplicate delivery of the old event IDs. Fixed business-service names are simulated identities, not additional implemented business applications.

## Incidents: create, retrieve, filter, update

Automatic incidents come from the event pipeline. The separate manual API also permits a caller to create an incident; this is not an LLM tool.

```powershell
$create = @{
    title = 'Database failures in example-manual-service'
    severity = 'HIGH'
    rootService = 'example-manual-service'
    errorCount = 5
} | ConvertTo-Json
$incident = Invoke-RestMethod -Uri "$gateway/api/incidents" -Method Post -ContentType 'application/json' -Body $create
$incidentId = $incident.id
Invoke-RestMethod "$gateway/api/incidents/$incidentId"
Invoke-RestMethod "$gateway/api/incidents?status=OPEN"
```

Creation returns **201**, a `Location` header and a DTO containing `id`, `title`, `status`, `severity`, `rootService`, `createdAt`, `updatedAt`, and `errorCount`. The server assigns UUID, OPEN status and UTC timestamps. Title/root service must be nonblank and no longer than 200/100 characters; error count must be nonnegative. Incident severity is `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL`.

```powershell
$update = @{ status = 'INVESTIGATING' } | ConvertTo-Json
Invoke-RestMethod -Uri "$gateway/api/incidents/$incidentId/status" -Method Patch -ContentType 'application/json' -Body $update
Invoke-RestMethod "$gateway/api/incidents/$incidentId"
```

Status values are `OPEN`, `INVESTIGATING`, and `RESOLVED`. Updates preserve incident identity and creation time. No restricted transition state machine is implemented. Lists are JSON arrays without pagination or a guaranteed sort order. Invalid input returns 400; a well-formed missing incident UUID returns 404. The application returns DTOs, never JPA entities.

Resolving an incident does not clear the Redis window; a fresh qualifying event can create another incident while its threshold is still met. Do not resolve retained verification incidents merely to rerun a creation demo.

## Service inventory, health and event history

```powershell
Invoke-RestMethod "$gateway/api/services"
Invoke-RestMethod "$gateway/api/services/order-service/health"
Invoke-RestMethod "$gateway/api/services/order-service/events?page=0&size=20"
```

- Inventory derives known names from persisted events and incident roots; an empty database returns `[]`.
- Health returns `serviceName`, `status`, `errorsLastMinute`, `activeIncidentCount`, and nullable `lastEventTimestamp`.
- With defaults, an active HIGH/CRITICAL incident or at least five recent errors means DOWN; two through four errors means DEGRADED; otherwise HEALTHY. Active LOW/MEDIUM incidents count but do not force DOWN.
- This is event/incident-derived operational health, not a heartbeat or network probe. An unresolved severe incident can keep a service DOWN after its error window expires.
- History returns `serviceName`, `events`, `page`, `size`, `totalElements`, and `totalPages`. Events contain `eventId`, `serviceName`, `eventType`, `severity`, `message`, `timestamp`, `traceId`, and `processedAt`; not metadata.
- History defaults to page 0/size 20, accepts sizes 1–100, and sorts by source timestamp descending, processing time descending, then event UUID ascending. An out-of-range page returns an empty array; invalid pagination returns 400.
- Unknown services return 404. PostgreSQL/Redis query outages return sanitized 503, not fabricated HEALTHY/empty success.

## AI analysis

Obtain an actual incident UUID from the same gateway. An event UUID is not an incident UUID.

```powershell
$incidentId = Read-Host 'Existing incident UUID from this deployment'
$analysis = Invoke-RestMethod -Uri "$gateway/api/analysis/incidents/$incidentId" -Method Post -TimeoutSec 300
$analysis | ConvertTo-Json -Depth 12
```

This can send operational evidence to the configured provider and incur charges. Do not use it as a health probe or blindly repeat a timed-out request. No analysis is persisted and no recommendation is executed.

| Status | Meaning |
| --- | --- |
| 200 | Validated analysis with server-captured evidence, a hypothesis and recommendations. |
| 400 | Invalid/canonical-UUID input failure; no model call. |
| 404 | Incident absent through MCP; no model call. |
| 503 | Required diagnostic evidence or model unavailable; no invented fallback. |
| 502 | Invalid model output/evidence references, prohibited tool request or exhausted investigation budget. |

The response has `incidentId`, `probableCause`, `confidence`, `evidence`, `recommendedActions`, and `limitations`. Evidence entries contain `id`, `toolName`, and `data`; the internal model draft's `evidenceIds` are not a public response field. Free-text reasoning is still a hypothesis requiring human review.

## Manual MCP requests

Use a trusted Streamable HTTP MCP client, or Postman, directly against `http://127.0.0.1:8084/mcp`. The gateway does not proxy MCP. No LLM/provider key is required to invoke diagnostic tools.

For each Postman POST, use request headers `Content-Type: application/json` and `Accept: application/json, text/event-stream`. Start with:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"postman-check","version":"1.0"}}}
```

Read the response's `Mcp-Session-Id` header and negotiated `protocolVersion`. Add request headers `Mcp-Session-Id` and `MCP-Protocol-Version` with those values on subsequent requests. Do not copy another client's session. The transport can return SSE events containing JSON-RPC data instead of a plain JSON body.

Send the initialized notification, with no `id`:

```json
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

List tools:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

Invoke a tool for a known service:

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_service_health","arguments":{"serviceName":"order-service"}}}
```

Other tool names are `get_incident` and `get_related_events` (argument `incidentId`), plus `get_recent_errors` and `get_error_frequency` (argument `serviceName`). Tool results carry `success`, `data`, and `error` in structured content and equivalent text. Check both MCP `isError` and `structuredContent.success`; HTTP success alone does not prove tool success. Stable tool errors are `INVALID_INPUT`, `NOT_FOUND`, `DOWNSTREAM_UNAVAILABLE`, and `INVALID_DOWNSTREAM_RESPONSE`.

The MCP server obtains data only through incident-service REST APIs. Related events are root-service/time-window matches, not proven causality or cross-service correlation. Read `truncated` and scan counts: bounded or inconsistent history cannot prove that no matching events exist.

## Management and load-balancing endpoint

The four read-only Actuator endpoints are `/actuator/health`, `/actuator/info`, `/actuator/metrics`, and `/actuator/prometheus`, on each application's own port. Gateway Actuator describes the gateway, not downstream services. Sensitive endpoints, discovery and JMX are disabled. Actuator UP is not provider authorization or a complete pipeline check.

`GET /api/instance` exists directly on incident-service and returns `serviceName`, natural OS `hostname`, and `timestamp` with `Cache-Control: no-store`. There is no gateway route for it; use the [Kubernetes Service-DNS demo](../kubernetes/LOAD_BALANCING.md).
