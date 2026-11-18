package com.umass.diagnostics.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IncidentReadResilienceTest {
    @Test void interruptedCallerCannotTriggerALateRetryAfterItReturns() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var replayed = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        try (var policy = new TestReadPolicy("diagnostics.resilience.retry-delay=0")) {
            Thread caller = new Thread(() -> {
                try {
                    policy.policy.execute(() -> {
                        if (calls.incrementAndGet() > 1) replayed.countDown();
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException ignored) { }
                        throw new IncidentReadPolicy.TransientReadFailure();
                    });
                } catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            caller.start();
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                caller.interrupt();
                caller.join(2000);
                assertFalse(caller.isAlive());
                assertInstanceOf(com.umass.diagnostics.exception.DiagnosticException.class, failure.get());
                assertTrue(interrupted.get());
                release.countDown();
                assertFalse(replayed.await(300, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertEquals(1, calls.get());
            } finally { release.countDown(); caller.interrupt(); caller.join(2000); }
        } finally { release.countDown(); }
    }

    @Test void tricklingBodyCannotExtendTheLogicalDeadline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 30; i++) {
                    out.write(' '); out.flush(); Thread.sleep(30);
                }
                out.write(IncidentServiceClientTest.INCIDENT.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException cancelledConnection) { /* Expected if cancellation closes the socket. */ }
            finally { exchange.close(); }
        });
        server.start();
        try (var policy = new TestReadPolicy("diagnostics.resilience.operation-timeout=300ms")) {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setReadTimeout(java.time.Duration.ofMillis(150));
            var client = new IncidentServiceClient(RestClient.builder().requestFactory(factory)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            long start = System.nanoTime();
            assertEquals("DOWNSTREAM_UNAVAILABLE", assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                    () -> client.getIncident(IncidentServiceClientTest.ID)).code());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < 850);
            assertEquals(1, calls.get());
            var breaker = policy.context.getBean(io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.class).circuitBreaker("incident-service-read");
            assertEquals(1, breaker.getMetrics().getNumberOfFailedCalls());
        } finally { server.stop(0); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"health", "events"})
    void serviceReadsRetryOnlyTheirOwnGet(String operation) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("/api/services/orders/" + operation, exchange.getRequestURI().getPath());
            int status = calls.incrementAndGet() == 1 ? 503 : 200;
            String json = operation.equals("health")
                    ? "{\"serviceName\":\"orders\",\"status\":\"HEALTHY\",\"errorsLastMinute\":0,\"activeIncidentCount\":0}"
                    : "{\"serviceName\":\"orders\",\"events\":[],\"page\":0,\"size\":2,\"totalElements\":0,\"totalPages\":0}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var policy = new TestReadPolicy()) {
            var client = new IncidentServiceClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            if (operation.equals("health")) assertEquals("HEALTHY", client.getServiceHealth("orders").status());
            else assertTrue(client.getServiceEvents("orders", 0, 2).events().isEmpty());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test void saturatedBoundedExecutorRejectsWithoutQueueOrOutboundReplay() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var callers = java.util.concurrent.Executors.newSingleThreadExecutor();
        AtomicInteger rejectedReadCalls = new AtomicInteger();
        try (var policy = new TestReadPolicy("diagnostics.resilience.max-concurrent-calls=1")) {
            var first = callers.submit(() -> policy.policy.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                return true;
            }));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var error = assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                        () -> policy.policy.execute(rejectedReadCalls::incrementAndGet));
                assertEquals("DOWNSTREAM_UNAVAILABLE", error.code());
                assertEquals(0, rejectedReadCalls.get());
                var executor = policy.context.getBean(java.util.concurrent.ThreadPoolExecutor.class);
                assertEquals(1, executor.getMaximumPoolSize());
                assertTrue(executor.getQueue() instanceof java.util.concurrent.SynchronousQueue);
                assertEquals(0, executor.getQueue().size());
                var breaker = policy.context.getBean(io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.class).circuitBreaker("incident-service-read");
                assertEquals(0, breaker.getMetrics().getNumberOfFailedCalls(), "Local saturation is not a remote failure");
            } finally { release.countDown(); }
            assertTrue(first.get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test void realReadTimeoutRetriesOnlyTheFailedGet() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            int call = calls.incrementAndGet();
            byte[] body = IncidentServiceClientTest.INCIDENT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try {
                if (call == 1) Thread.sleep(400);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var policy = new TestReadPolicy("diagnostics.resilience.operation-timeout=2s", "diagnostics.resilience.retry-delay=10ms")) {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setReadTimeout(java.time.Duration.ofMillis(100));
            factory.setConnectTimeout(java.time.Duration.ofSeconds(1));
            var client = new IncidentServiceClient(RestClient.builder().requestFactory(factory)
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            assertEquals(IncidentServiceClientTest.ID, client.getIncident(IncidentServiceClientTest.ID).id());
            assertEquals(2, calls.get());
        } finally { server.stop(0); workers.shutdownNow(); }
    }

    @Test void circuitRecoversAfterConfiguredOpenWaitAndOneSuccessfulProbe() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var available = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = IncidentServiceClientTest.INCIDENT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(available.get() ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var policy = new TestReadPolicy("diagnostics.resilience.minimum-number-of-calls=1",
                "diagnostics.resilience.wait-duration-in-open-state=200ms")) {
            var client = new IncidentServiceClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            assertThrows(com.umass.diagnostics.exception.DiagnosticException.class, () -> client.getIncident(IncidentServiceClientTest.ID));
            var breaker = policy.context.getBean(io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.class).circuitBreaker("incident-service-read");
            assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, breaker.getState());
            assertEquals(2, calls.get());
            assertThrows(com.umass.diagnostics.exception.DiagnosticException.class, () -> client.getIncident(IncidentServiceClientTest.ID));
            assertEquals(2, calls.get());
            available.set(true);
            Thread.sleep(250);
            assertEquals(IncidentServiceClientTest.ID, client.getIncident(IncidentServiceClientTest.ID).id());
            assertEquals(3, calls.get());
            assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, breaker.getState());
        } finally { server.stop(0); }
    }

    @Test void alreadyInterruptedCallerNeverSubmitsARead() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var policy = new TestReadPolicy()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(com.umass.diagnostics.exception.DiagnosticException.class, () -> policy.policy.execute(calls::incrementAndGet));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            Thread.sleep(100);
            assertEquals(0, calls.get());
        }
    }

    @Test void timeoutCannotReplayAnUninterruptibleLeftoverRead() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var replayed = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var policy = new TestReadPolicy("diagnostics.resilience.operation-timeout=100ms", "diagnostics.resilience.retry-delay=0")) {
            assertThrows(com.umass.diagnostics.exception.DiagnosticException.class, () -> policy.policy.execute(() -> {
                if (calls.incrementAndGet() > 1) replayed.countDown();
                boolean waiting = true;
                while (waiting) {
                    try { release.await(); waiting = false; }
                    catch (InterruptedException ignored) { /* Simulate transport ignoring cancellation. */ }
                }
                throw new IncidentReadPolicy.TransientReadFailure();
            }));
            release.countDown();
            assertFalse(replayed.await(300, java.util.concurrent.TimeUnit.MILLISECONDS), "Cancelled work must not retry later");
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 3})
    void configuredAttemptsAndDelayApplyWithinOneCircuitOutcome(int attempts) {
        try (var policy = new TestReadPolicy("diagnostics.resilience.max-attempts=" + attempts,
                "diagnostics.resilience.retry-delay=400ms")) {
            AtomicInteger calls = new AtomicInteger();
            long start = System.nanoTime();
            assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                    () -> policy.policy.execute(() -> {
                        calls.incrementAndGet();
                        throw new IncidentReadPolicy.TransientReadFailure();
                    }));
            assertEquals(attempts, calls.get());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() >= (attempts - 1) * 380L);
            var breaker = policy.context.getBean(io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.class).circuitBreaker("incident-service-read");
            assertEquals(1, breaker.getMetrics().getNumberOfFailedCalls());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"404", "invalid-json", "invalid-domain"})
    void domainAndInvalidResponsesAreNotRetriedOrRecordedByCircuit(String kind) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = (kind.equals("invalid-json") ? "{bad" : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(kind.equals("404") ? 404 : 200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var policy = new TestReadPolicy()) {
            var client = new IncidentServiceClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            for (int i = 0; i < 6; i++) {
                assertEquals(kind.equals("404") ? "NOT_FOUND" : "INVALID_DOWNSTREAM_RESPONSE",
                        assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                                () -> client.getIncident(IncidentServiceClientTest.ID)).code());
            }
            assertEquals(6, calls.get());
            var breaker = policy.context.getBean(io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.class).circuitBreaker("incident-service-read");
            assertEquals(0, breaker.getMetrics().getNumberOfBufferedCalls());
        } finally { server.stop(0); }
    }

    @Test void configuredDeadlineBoundsTheWholeReadIncludingDelayedBody() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = IncidentServiceClientTest.INCIDENT.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try {
                Thread.sleep(1500);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var policy = new TestReadPolicy("diagnostics.resilience.operation-timeout=300ms")) {
            var client = new IncidentServiceClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            long start = System.nanoTime();
            assertEquals("DOWNSTREAM_UNAVAILABLE", assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                    () -> client.getIncident(IncidentServiceClientTest.ID)).code());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void exhaustedReadsOpenCircuitAndOpenCircuitMakesNoOutboundCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try (var policy = new TestReadPolicy()) {
            var client = new IncidentServiceClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            for (int i = 0; i < 5; i++) {
                assertEquals("DOWNSTREAM_UNAVAILABLE", assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                        () -> client.getIncident(IncidentServiceClientTest.ID)).code());
            }
            assertEquals(10, calls.get(), "Each logical read has two total attempts");
            assertThrows(com.umass.diagnostics.exception.DiagnosticException.class,
                    () -> client.getIncident(IncidentServiceClientTest.ID));
            assertEquals(10, calls.get(), "Open circuit must reject without HTTP");
        } finally { server.stop(0); }
    }

    @Test void retriesOne503WithAFreshGetAndReturnsRealIncident() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/incidents/", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            int status = calls.incrementAndGet() == 1 ? 503 : 200;
            byte[] body = (status == 503 ? "secret" : IncidentServiceClientTest.INCIDENT).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var policy = new TestReadPolicy()) {
            var client = new IncidentServiceClient(RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(), policy.policy);
            assertEquals(IncidentServiceClientTest.ID, client.getIncident(IncidentServiceClientTest.ID).id());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }
}
