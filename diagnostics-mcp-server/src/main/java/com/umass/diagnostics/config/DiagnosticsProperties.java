package com.umass.diagnostics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "diagnostics")
public record DiagnosticsProperties(URI incidentServiceBaseUrl, Duration connectTimeout, Duration readTimeout,
                                    int historyPageSize, int maxHistoryPages, Duration recentErrorWindow,
                                    Duration relatedLookback, Duration relatedLookahead) {
    public DiagnosticsProperties {
        if (incidentServiceBaseUrl == null || incidentServiceBaseUrl.getHost() == null
                || !("http".equalsIgnoreCase(incidentServiceBaseUrl.getScheme())
                     || "https".equalsIgnoreCase(incidentServiceBaseUrl.getScheme()))
                || incidentServiceBaseUrl.getUserInfo() != null || incidentServiceBaseUrl.getQuery() != null
                || incidentServiceBaseUrl.getFragment() != null
                || !(incidentServiceBaseUrl.getPath().isEmpty() || "/".equals(incidentServiceBaseUrl.getPath()))
                || incidentServiceBaseUrl.getPort() == 0 || incidentServiceBaseUrl.getPort() > 65535) {
            throw new IllegalArgumentException("incident-service-base-url must be an HTTP(S) origin without credentials, path, query, or fragment");
        }
        if (historyPageSize < 1 || historyPageSize > 100 || maxHistoryPages < 1 || maxHistoryPages > 10) {
            throw new IllegalArgumentException("history-page-size must be 1..100 and max-history-pages must be 1..10");
        }
        requireDuration(connectTimeout, Duration.ofSeconds(30), false, "connect-timeout");
        requireDuration(readTimeout, Duration.ofSeconds(30), false, "read-timeout");
        requireDuration(recentErrorWindow, Duration.ofDays(1), false, "recent-error-window");
        requireDuration(relatedLookback, Duration.ofDays(1), false, "related-lookback");
        requireDuration(relatedLookahead, Duration.ofDays(1), true, "related-lookahead");
    }

    private static void requireDuration(Duration value, Duration maximum, boolean allowZero, String name) {
        if (value == null || value.isNegative() || (!allowZero && value.isZero()) || value.compareTo(maximum) > 0
                || (!value.isZero() && value.toMillis() == 0)) {
            throw new IllegalArgumentException(name + " is outside its permitted duration range");
        }
    }
}
