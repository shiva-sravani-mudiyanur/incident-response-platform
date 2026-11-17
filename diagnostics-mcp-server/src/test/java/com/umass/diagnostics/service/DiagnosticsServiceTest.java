package com.umass.diagnostics.service;

import com.umass.diagnostics.config.DiagnosticsProperties;
import com.umass.diagnostics.dto.*;
import com.umass.diagnostics.exception.DiagnosticException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagnosticsServiceTest {
    static final String ID = IncidentServiceClientTest.ID;
    static final Instant NOW = Instant.parse("2026-11-17T09:00:00Z");
    IncidentServiceClient client;
    DiagnosticsService service;
    @BeforeEach void setup() {
        client = mock(IncidentServiceClient.class);
        service = new DiagnosticsService(client, properties(2), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    static DiagnosticsProperties properties(int maxPages) {
        return new DiagnosticsProperties(URI.create("http://incident.test"), Duration.ofSeconds(2), Duration.ofSeconds(5),
                2, maxPages, Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"", " ", "1-1-1-1-1", " 11111111-1111-1111-1111-111111111111", "11111111-1111-1111-1111-111111111111 ", "http://evil", "11111111111111111111111111111111"})
    void rejectsInvalidIncidentIdBeforeHttp(String id) {
        assertError("INVALID_INPUT", service.getIncident(id));
        assertError("INVALID_INPUT", service.getRelatedEvents(id));
        verifyNoInteractions(client);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"", "  ", ".", "..", "a/b", "a\\b", "a\nb", "a\u007fb", "http://evil"})
    void rejectsInvalidServiceNameBeforeHttp(String name) {
        assertError("INVALID_INPUT", service.getServiceHealth(name));
        assertError("INVALID_INPUT", service.getErrorFrequency(name));
        verifyNoInteractions(client);
    }
    @Test void rejectsOverlongServiceNameBeforeHttp() {
        assertError("INVALID_INPUT", service.getServiceHealth("x".repeat(101)));
        verifyNoInteractions(client);
    }
    @Test void returnsIncidentWithoutChangingLiteralId() {
        var incident = incident();
        when(client.getIncident(ID)).thenReturn(incident);
        assertEquals(incident, service.getIncident(ID).data());
        verify(client).getIncident(ID);
    }
    @Test void returnsHealthWithoutTrimmingServiceName() {
        var health = new ServiceHealth(" orders ", "DEGRADED", 30L, 0L, null);
        when(client.getServiceHealth(" orders ")).thenReturn(health);
        assertEquals(health, service.getServiceHealth(" orders ").data());
        verify(client).getServiceHealth(" orders ");
    }
    @Test void derivesFrequencyFromRollingHealthCountWithoutHistoryScan() {
        when(client.getServiceHealth("orders")).thenReturn(new ServiceHealth("orders", "DEGRADED", 30L, 0L, null));
        var result = service.getErrorFrequency("orders");
        assertTrue(result.success());
        assertNull(result.error());
        assertEquals("orders", result.data().serviceName());
        assertEquals(60, result.data().windowSeconds());
        assertEquals(30, result.data().errorsLastMinute());
        assertEquals(0.5, result.data().errorsPerSecond());
        verify(client).getServiceHealth("orders");
        verifyNoMoreInteractions(client);
    }
    @Test void convertsDownstreamFailuresToSafeResult() {
        when(client.getIncident(ID)).thenThrow(new DiagnosticException("NOT_FOUND", "Requested resource was not found."));
        assertError("NOT_FOUND", service.getIncident(ID));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"", "a/b", ".."})
    void rejectsInvalidRecentServiceBeforeHttp(String name) {
        assertError("INVALID_INPUT", service.getRecentErrors(name));
        verifyNoInteractions(client);
    }
    @Test void scansPastOldProcessedTimesAndUsesExclusiveLowerInclusiveUpperBoundary() {
        var old = event(1, "ERROR", NOW.minusSeconds(61));
        var lower = event(2, "ERROR", NOW.minusSeconds(60));
        var inside = event(3, "CRITICAL", NOW.minusSeconds(1));
        var upper = event(4, "ERROR", NOW);
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,4,old,lower));
        when(client.getServiceEvents("orders",1,2)).thenReturn(page(1,4,inside,upper));
        var result = service.getRecentErrors("orders");
        assertTrue(result.success());
        assertEquals(java.util.List.of(inside,upper), result.data().events());
        assertEquals(NOW.minusSeconds(60), result.data().windowStart());
        assertEquals(NOW, result.data().windowEnd());
        assertEquals("PROCESSED_AT", result.data().timeBasis());
        assertEquals(4,result.data().scannedEvents());
        assertEquals(2,result.data().scannedPages());
        assertFalse(result.data().truncated());
        assertFalse(result.data().hasMore());
    }
    @Test void recentErrorsDeduplicateIdsExcludeOtherSeveritiesAndFutureProcessing() {
        service = new DiagnosticsService(client, properties(3), Clock.fixed(NOW, ZoneOffset.UTC));
        var error = event(1,"ERROR",NOW);
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,5,error,error));
        when(client.getServiceEvents("orders",1,2)).thenReturn(page(1,5,event(2,"INFO",NOW),event(3,"WARNING",NOW)));
        when(client.getServiceEvents("orders",2,2)).thenReturn(page(2,5,event(4,"CRITICAL",NOW.plusNanos(1))));
        var result = service.getRecentErrors("orders").data();
        assertEquals(java.util.List.of(error),result.events());
        assertEquals(5,result.scannedEvents());
        assertFalse(result.truncated());
    }
    @Test void pageCapReportsIncompleteCoverageEvenIfNoMatchesWereFound() {
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,6,event(1,"INFO",NOW),event(2,"INFO",NOW)));
        when(client.getServiceEvents("orders",1,2)).thenReturn(page(1,6,event(3,"INFO",NOW),event(4,"INFO",NOW)));
        var result = service.getRecentErrors("orders").data();
        assertTrue(result.events().isEmpty());
        assertTrue(result.truncated());
        assertTrue(result.hasMore());
        assertEquals(2,result.scannedPages());
        verify(client,never()).getServiceEvents("orders",2,2);
    }
    @Test void emptyHistoryIsACompleteSinglePageScan() {
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,0));
        var result = service.getRecentErrors("orders").data();
        assertTrue(result.events().isEmpty());
        assertFalse(result.truncated());
        assertEquals(0,result.scannedEvents());
        assertEquals(1,result.scannedPages());
    }
    @Test void changingHistoryTotalsNeverClaimCompleteCoverage() {
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,4,event(1,"ERROR",NOW),event(2,"ERROR",NOW)));
        when(client.getServiceEvents("orders",1,2)).thenReturn(page(1,3,event(3,"ERROR",NOW)));
        var result = service.getRecentErrors("orders").data();
        assertTrue(result.truncated());
        assertFalse(result.hasMore());
        assertEquals(3,result.scannedEvents());
    }
    @Test void failedLaterPageDoesNotBecomePartialSuccess() {
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,3,event(1,"ERROR",NOW),event(2,"ERROR",NOW)));
        when(client.getServiceEvents("orders",1,2)).thenThrow(new DiagnosticException("DOWNSTREAM_UNAVAILABLE","Incident service is unavailable."));
        assertError("DOWNSTREAM_UNAVAILABLE",service.getRecentErrors("orders"));
    }
    @Test void relatedEventsIncludeAllSeveritiesOnBothInclusiveProcessingBoundaries() {
        when(client.getIncident(ID)).thenReturn(incident());
        var lower = event(1,"INFO",NOW.minusSeconds(60));
        var warning = event(2,"WARNING",NOW);
        var error = event(3,"ERROR",NOW);
        var upper = event(4,"CRITICAL",NOW.plusSeconds(60));
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,4,lower,warning));
        when(client.getServiceEvents("orders",1,2)).thenReturn(page(1,4,error,upper));
        var result = service.getRelatedEvents(ID);
        assertTrue(result.success());
        assertEquals(java.util.List.of(lower,warning,error,upper),result.data().events());
        assertEquals(ID,result.data().incidentId());
        assertEquals("orders",result.data().rootService());
        assertEquals("ROOT_SERVICE_TIME_WINDOW",result.data().relationshipType());
        assertTrue(result.data().explanation().contains("not proven causality"));
        assertEquals("PROCESSED_AT",result.data().timeBasis());
        assertEquals(NOW.minusSeconds(60),result.data().windowStart());
        assertEquals(NOW.plusSeconds(60),result.data().windowEnd());
        assertEquals(4,result.data().scannedEvents());
        assertEquals(2,result.data().scannedPages());
        assertFalse(result.data().truncated());
        var order = inOrder(client);
        order.verify(client).getIncident(ID);
        order.verify(client).getServiceEvents("orders",0,2);
        order.verify(client).getServiceEvents("orders",1,2);
        verifyNoMoreInteractions(client);
    }
    @Test void relatedEventsExcludeProcessingOutsideWindow() {
        when(client.getIncident(ID)).thenReturn(incident());
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,2,
                event(1,"ERROR",NOW.minusSeconds(60).minusNanos(1)),event(2,"INFO",NOW.plusSeconds(60).plusNanos(1))));
        assertTrue(service.getRelatedEvents(ID).data().events().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"NOT_FOUND","INVALID_DOWNSTREAM_RESPONSE","DOWNSTREAM_UNAVAILABLE"})
    void relatedEventsStopImmediatelyWhenIncidentRetrievalFails(String code) {
        when(client.getIncident(ID)).thenThrow(new DiagnosticException(code,"Safe local message."));
        assertError(code,service.getRelatedEvents(ID));
        verify(client).getIncident(ID);
        verifyNoMoreInteractions(client);
    }
    @Test void relatedEventsShareTheScanCapAndDeduplication() {
        when(client.getIncident(ID)).thenReturn(incident());
        var event = event(1,"INFO",NOW);
        when(client.getServiceEvents("orders",0,2)).thenReturn(page(0,6,event,event));
        when(client.getServiceEvents("orders",1,2)).thenReturn(page(1,6,event,event));
        var result = service.getRelatedEvents(ID).data();
        assertEquals(java.util.List.of(event),result.events());
        assertEquals(4,result.scannedEvents());
        assertEquals(2,result.scannedPages());
        assertTrue(result.truncated());
        assertTrue(result.hasMore());
        verify(client,never()).getServiceEvents("orders",2,2);
    }
    @Test void unrepresentableIncidentTimeWindowIsAnInvalidResponseWithoutHistoryCalls() {
        when(client.getIncident(ID)).thenReturn(new IncidentDetails(ID,"Outage","OPEN","HIGH","orders",Instant.MAX,Instant.MAX,2));
        assertError("INVALID_DOWNSTREAM_RESPONSE",service.getRelatedEvents(ID));
        verify(client).getIncident(ID);
        verifyNoMoreInteractions(client);
    }
    static ServiceEvent event(int id, String severity, Instant processed) {
        // Source order is deliberately independent of processing time.
        return new ServiceEvent("00000000-0000-0000-0000-%012d".formatted(id), "orders", "TIMEOUT", severity,
                "Event", NOW.minusSeconds(id * 100L), null, processed);
    }
    static ServiceEventsPage page(int page,long total,ServiceEvent... events) {
        return new ServiceEventsPage("orders",java.util.List.of(events),page,2,total,(int)((total+1)/2));
    }
    static IncidentDetails incident() {
        return new IncidentDetails(ID, "Outage", "OPEN", "HIGH", "orders", NOW, NOW, 2);
    }
    static void assertError(String code, DiagnosticResult<?> result) {
        assertFalse(result.success());
        assertNull(result.data());
        assertNotNull(result.error());
        assertEquals(code, result.error().code());
    }
}
