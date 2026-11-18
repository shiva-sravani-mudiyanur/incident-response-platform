package com.umass.diagnostics.config;

import com.sun.net.httpserver.HttpServer;
import com.umass.diagnostics.exception.DiagnosticException;
import com.umass.diagnostics.service.IncidentReadPolicy;
import com.umass.diagnostics.service.IncidentServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsRedirectTest {
    @Test void configuredHttpClientDoesNotFollowOrRetryRedirects() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger redirected = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            reads.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext(DiagnosticsResilienceConfiguration.class, IncidentReadPolicy.class)) {
            var properties = new DiagnosticsProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), 100, 5, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ZERO);
            var rest = new DiagnosticsConfiguration().incidentServiceRestClient(RestClient.builder(), properties);
            var client = new IncidentServiceClient(rest, context.getBean(IncidentReadPolicy.class));
            assertEquals("INVALID_DOWNSTREAM_RESPONSE", assertThrows(DiagnosticException.class,
                    () -> client.getIncident("11111111-1111-1111-1111-111111111111")).code());
            assertEquals(1, reads.get());
            assertEquals(0, redirected.get());
        } finally { server.stop(0); }
    }
}
