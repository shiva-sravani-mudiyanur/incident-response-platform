package com.umass.incident.service;

import com.umass.incident.config.CorrelationProperties;
import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import com.umass.incident.model.Incident;
import com.umass.incident.model.IncidentSeverity;
import com.umass.incident.model.IncidentStatus;
import com.umass.incident.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class CorrelationService {
    private static final Logger log = LoggerFactory.getLogger(CorrelationService.class);
    private static final List<IncidentStatus> ACTIVE_STATUSES = List.of(IncidentStatus.OPEN, IncidentStatus.INVESTIGATING);
    private final RedisFailureCounter counter;
    private final IncidentRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CorrelationProperties properties;

    public CorrelationService(RedisFailureCounter counter, IncidentRepository repository, JdbcTemplate jdbc,
                              Clock clock, CorrelationProperties properties) {
        this.counter = counter;
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public void correlate(ServiceEvent event) {
        if (event.severity() != EventSeverity.ERROR && event.severity() != EventSeverity.CRITICAL) return;
        var snapshot = counter.record(event);
        if (snapshot.count() < properties.threshold()) return;

        // Serialize the check/create across application instances, until PostgreSQL commit.
        // A JVM lock or an expiring Redis guard alone would not protect this transaction.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) row -> { },
                "incident-correlation:" + event.serviceName());
        if (repository.existsByRootServiceAndStatusIn(event.serviceName(), ACTIVE_STATUSES)) return;
        var severity = snapshot.hasCritical() ? IncidentSeverity.CRITICAL : IncidentSeverity.HIGH;
        var incident = new Incident(UUID.randomUUID(),
                "Elevated error rate detected in " + event.serviceName(), severity, event.serviceName(),
                Math.toIntExact(snapshot.count()), clock.instant().truncatedTo(ChronoUnit.MICROS));
        repository.save(incident);
        log.info("Correlated incident id={} rootService={} severity={} errorCount={}", incident.getId(),
                event.serviceName().replace('\r', '_').replace('\n', '_'), severity, snapshot.count());
    }
}
