package com.umass.event;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Boot/HTTP management contract, with no Kafka publication or Docker dependency. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventObservabilityApplicationTest {
    private ConfigurableApplicationContext application;
    private HttpClient client;
    private String baseUrl;


    @BeforeAll
    void startApplication() {
        application = new SpringApplicationBuilder(EventServiceApplication.class).run(
                "--server.port=0", "--KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:1",
                // Native Windows JDK CPU sampling blocks in getCpuLoad0 on this host;
                // this test covers custom meters, not the JDK/platform CPU implementation.
                "--management.metrics.enable.system.cpu=false", "--management.metrics.enable.process.cpu=false");
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    void stopApplication() {
        if (client != null) client.close();
        if (application != null) application.close();
    }

    @Test
    void configurationOptsInOnlyFourReadOnlyEndpointsAndBootLogstash() {
        var env = application.getEnvironment();
        assertThat(env.getProperty("management.endpoints.access.default")).isEqualTo("none");
        assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,metrics,prometheus");
        for (String endpoint : Set.of("health", "info", "metrics", "prometheus")) {
            assertThat(env.getProperty("management.endpoint." + endpoint + ".access")).isEqualTo("read-only");
        }
        assertThat(env.getProperty("management.metrics.tags.application")).isEqualTo("event-service");
        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("logstash");
    }

    @Test
    void exposesOnlyReadOnlyAllowlistedEndpointsWithoutDiscovery() throws Exception {
        var response = get("/actuator");
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/metrics").statusCode()).isEqualTo(200);
        for (String forbidden : Set.of("env", "configprops", "loggers", "beans", "mappings", "shutdown", "heapdump", "threaddump")) {
            assertThat(get("/actuator/" + forbidden).statusCode()).as(forbidden).isEqualTo(404);
        }
        for (String endpoint : Set.of("health", "info", "metrics", "prometheus")) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/" + endpoint))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
        }
    }

    @Test
    void prometheusAndMetricsHaveOnlyFixedApplicationAndOutcomeDimensions() throws Exception {
        var registry = application.getBean(MeterRegistry.class);
        assertThat(registry.getMeters().stream().filter(m -> m.getId().getName().startsWith("event.")))
                .hasSize(5).allSatisfy(meter -> {
                    assertThat(meter.getId().getTag("application")).isEqualTo("event-service");
                    if (meter.getId().getName().equals("event.publish.duration")) {
                        assertThat(meter.getId().getTags()).hasSize(2);
                        assertThat(meter.getId().getTag("outcome")).isIn("success", "error");
                    } else assertThat(meter.getId().getTags()).hasSize(1);
                });
        var prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("event_received_total{application=\"event-service\"}",
                "event_published_total{application=\"event-service\"}", "event_publish_errors_total{application=\"event-service\"}",
                "event_publish_duration_seconds_count{application=\"event-service\",outcome=\"success\"}",
                "event_publish_duration_seconds_count{application=\"event-service\",outcome=\"error\"}");
        assertThat(get("/actuator/metrics/event.publish.duration").statusCode()).isEqualTo(200);
    }

    @Test
    void invalidRequestDoesNotCountAsReceived() throws Exception {
        var registry = application.getBean(MeterRegistry.class);
        double before = registry.get("event.received").counter().count();
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/events"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}" )).build();
        assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(registry.get("event.received").counter().count()).isEqualTo(before);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
