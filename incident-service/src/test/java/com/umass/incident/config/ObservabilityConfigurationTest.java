package com.umass.incident.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigurationTest {
    @Test
    void onlyReadOnlyOperationalEndpointsWithFixedApplicationTagAndJsonConsoleAreConfigured() throws Exception {
        var properties = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();
        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,metrics,prometheus");
        assertThat(properties.getProperty("management.endpoints.access.default")).isEqualTo("none");
        for (String endpoint : new String[]{"health", "info", "metrics", "prometheus"})
            assertThat(properties.getProperty("management.endpoint." + endpoint + ".access")).isEqualTo("read-only");
        assertThat(properties.getProperty("management.metrics.tags.application")).isEqualTo("${spring.application.name}");
        assertThat(properties.getProperty("logging.structured.format.console")).isEqualTo("logstash");
        assertThat(properties.getProperty("logging.structured.json.add.application")).isEqualTo("${spring.application.name}");
        assertThat(properties.getProperty("spring.main.banner-mode")).isEqualTo("off");
        assertThat(getClass().getClassLoader().getResource("io/micrometer/prometheusmetrics/PrometheusMeterRegistry.class"))
                .as("Boot managed Prometheus registry").isNotNull();
    }
}
