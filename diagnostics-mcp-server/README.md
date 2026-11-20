# Diagnostics read resilience

For the tool architecture and REST boundaries, see [the project README](../README.md#mcp-architecture); for protocol initialization and Postman examples, see [manual MCP requests](../docs/api-examples.md#manual-mcp-requests).

Only `IncidentServiceClient`'s incident, service-health and individual event-page GETs are protected. A tool/history scan is never replayed. There are no write, LLM, Kafka, Redis or database retries.

The module imports Spring Cloud BOM `2025.1.4` (CircuitBreaker `5.0.3`, Resilience4j `2.3.0`); dependency versions remain BOM-managed. Spring Boot, Java, Spring AI and JUnit pins are unchanged.

## Configuration

The typed `diagnostics.resilience` properties fail application startup on invalid bounds. YAML supplies these environment overrides:

| Property | Environment variable | Default | Accepted range |
|---|---|---|---|
| `operation-timeout` | `DIAGNOSTICS_RESILIENCE_OPERATION_TIMEOUT` | `8s` | 1ms–30s |
| `max-attempts` | `DIAGNOSTICS_RESILIENCE_MAX_ATTEMPTS` | `2` | 1–3 **total attempts** |
| `retry-delay` | `DIAGNOSTICS_RESILIENCE_RETRY_DELAY` | `100ms` | zero or 1ms–2s |
| `sliding-window-size` | `DIAGNOSTICS_RESILIENCE_SLIDING_WINDOW_SIZE` | `10` | 1–100 |
| `minimum-number-of-calls` | `DIAGNOSTICS_RESILIENCE_MINIMUM_NUMBER_OF_CALLS` | `5` | 1–window size |
| `failure-rate-threshold` | `DIAGNOSTICS_RESILIENCE_FAILURE_RATE_THRESHOLD` | `50` | finite, greater than 0, at most 100 |
| `wait-duration-in-open-state` | `DIAGNOSTICS_RESILIENCE_WAIT_DURATION_IN_OPEN_STATE` | `10s` | 1ms–5m |
| `permitted-calls-in-half-open-state` | `DIAGNOSTICS_RESILIENCE_PERMITTED_CALLS_IN_HALF_OPEN_STATE` | `1` | 1–32 |
| `max-concurrent-calls` | `DIAGNOSTICS_RESILIENCE_MAX_CONCURRENT_CALLS` | `4` | 1–32 |

Existing connect/read timeouts remain in force. A dedicated Spring Cloud `Resilience4JCircuitBreakerFactory` is configured through a `Customizer`; its mandatory TimeLimiter encloses the complete retry sequence and response validation. The factory does not take arbitrary generic Resilience4j property overrides that could disable the deadline. The Cloud compatibility verifier remains enabled. The optional Cloud bulkhead auto-wrapper is disabled; concurrency is enforced by a fixed `ThreadPoolExecutor`, zero-capacity `SynchronousQueue`, and abort-on-saturation policy.

## Failure semantics

A fresh GET is built for every attempt. Known HTTP 408/429/500/502/503/504 and transient connection/socket/timeouts can retry; arbitrary upstream `Retry-After` is not honored. Other statuses, 404s, malformed/invalid responses and interrupted/cancelled work do not retry. Redirect following is disabled. Exhaustion or deadline expiration records one circuit failure, not one failure per attempt. Domain/invalid responses, cancellation and local saturation are ignored by the circuit. Open circuits make no outbound call; successful half-open probes close the circuit.

Only locally authored `NOT_FOUND`, `DOWNSTREAM_UNAVAILABLE` and `INVALID_DOWNSTREAM_RESPONSE` errors cross the client boundary. No raw upstream body, URL, credential, exception cause or fallback evidence is returned.

The TimeLimiter cancels its Future on timeout, but cancellation does **not** guarantee that a blocking socket read or the remote server aborts. Interrupts are preserved. A per-logical-call cancellation flag and monotonic deadline guard every attempt, preventing a late retry after the caller has returned, even if the transport ignores interruption. Leftover workers are bounded by the fixed executor, not an unbounded queue; later callers fail safely when all workers remain occupied.

## Offline verification

From the repository root on Windows:

```powershell
.\mvnw.cmd -B -ntp -pl diagnostics-mcp-server test
.\mvnw.cmd -B -ntp -pl diagnostics-mcp-server dependency:tree "-Dincludes=org.springframework.cloud:*,io.github.resilience4j:*"
```

Tests use private loopback HTTP fixtures, never a running incident service or a provider. They cover transient recovery for all three GETs, HTTP/error allowlists, real socket-body timeout/retry, delayed/trickling body deadlines, circuit opening/no-outbound short-circuit/recovery, invalid/domain exclusion, bounded executor saturation, interruption and uninterruptible leftovers, property binding and invalid startup bounds. Existing MCP integration/contract tests remain enabled.
