package com.umass.analysis;

import com.sun.net.httpserver.HttpServer;
import com.umass.analysis.resilience.McpReadResilience;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Real API/MCP transport outage contract; model is mocked and provider calls are impossible. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpUnavailableIntegrationTest {
    private static final String ID = "93a40209-8dc9-4c4d-9162-a298ae31306f";
    private final AtomicInteger initializations = new AtomicInteger();
    private HttpServer server;
    private ConfigurableApplicationContext app;
    private HttpClient http;
    private String origin;
    private java.util.concurrent.ExecutorService fixtureExecutor;
    private volatile long responseDelayMillis;

    @BeforeAll void startPrivateOutage() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixtureExecutor = java.util.concurrent.Executors.newFixedThreadPool(4);
        server.setExecutor(fixtureExecutor);
        server.createContext("/mcp", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("POST")) {
                    var request = JsonMapper.builder().build().readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                    if ("initialize".equals(request.get("method"))) initializations.incrementAndGet();
                }
                try { Thread.sleep(responseDelayMillis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                byte[] bytes = "private upstream body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(503, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        app = new SpringApplicationBuilder(AiAnalysisServiceApplication.class, NoProvider.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.ai.model.chat=none",
                "--spring.ai.openai.chat.api-key=", "--spring.ai.openai.chat.model=offline-unused",
                "--DIAGNOSTICS_MCP_BASE_URL=http://127.0.0.1:" + server.getAddress().getPort(),
                "--analysis.resilience.minimum-number-of-calls=2", "--analysis.resilience.sliding-window-size=2",
                "--analysis.resilience.wait-duration-in-open-state=30s", "--analysis.resilience.operation-timeout=2s",
                "--spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false",
                "--spring.cloud.circuitbreaker.resilience4j.disable-time-limiter=true");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        origin = "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port");
    }

    @AfterAll void closePrivateResources() {
        if (http != null) http.close();
        if (app != null) app.close();
        if (server != null) server.stop(0);
        if (fixtureExecutor != null) fixtureExecutor.shutdownNow();
    }

    @BeforeEach void resetPrivateFailureState() {
        responseDelayMillis = 0;
        initializations.set(0);
        app.getBean(Resilience4JCircuitBreakerFactory.class).getCircuitBreakerRegistry()
                .find(McpReadResilience.CIRCUIT_ID).ifPresent(CircuitBreaker::reset);
        reset(app.getBean(ChatModel.class));
    }

    @Test void slowMcpReturnsControlledHttpErrorWithinOverallDeadline() throws Exception {
        responseDelayMillis = 4000;
        long started = System.nanoTime();
        var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/analysis/incidents/" + ID))
                .timeout(Duration.ofSeconds(6)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("DIAGNOSTICS_UNAVAILABLE").doesNotContain("private", "127.0.0.1", "stackTrace");
        assertThat(initializations).hasValue(1);
        verifyNoInteractions(app.getBean(ChatModel.class));
    }

    @Test void unavailableMcpOpensCircuitAndReturnsControlledApiErrorsWithoutCallingModel() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            var response = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/analysis/incidents/" + ID))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("DIAGNOSTICS_UNAVAILABLE").doesNotContain("private", "127.0.0.1", "stackTrace");
        }
        var registry = app.getBean(Resilience4JCircuitBreakerFactory.class).getCircuitBreakerRegistry();
        assertThat(app.getBean(Resilience4JCircuitBreakerFactory.class).getBulkheadProvider()).isNull();
        var executor = app.getBean("mcpReadExecutor", java.util.concurrent.ThreadPoolExecutor.class);
        assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
        assertThat(executor.getQueue().remainingCapacity()).isZero();
        assertThat(registry.circuitBreaker(McpReadResilience.CIRCUIT_ID).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(initializations).hasValue(2);
        verifyNoInteractions(app.getBean(ChatModel.class));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoProvider {
        @Bean ChatModel mockModel() { return mock(ChatModel.class); }
    }
}
