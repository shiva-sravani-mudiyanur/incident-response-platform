package com.umass.analysis.service;

import com.umass.analysis.config.AnalysisProperties;
import com.umass.analysis.mcp.DiagnosticsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IncidentAnalysisServiceTest {
    static final String ID = "93a40209-8dc9-4c4d-9162-a298ae31306f";
    ChatModel model;
    DiagnosticsGateway gateway;
    DiagnosticsGateway.Session session;
    IncidentAnalysisService service;
    AnalysisProperties properties;

    @BeforeEach void setup() {
        model = mock(ChatModel.class);
        gateway = mock(DiagnosticsGateway.class);
        session = mock(DiagnosticsGateway.Session.class);
        properties = properties(3, 8, 64000, 16000);
        service = new IncidentAnalysisService(model, gateway, properties, JsonMapper.builder().build());
    }

    @Test void capturesMandatoryDiagnosticsBeforeSynthesizingServerOwnedEvidence() {
        preflight();
        when(model.call(any(Prompt.class))).thenReturn(answer(draft()));
        var result = service.analyze(ID);
        assertThat(result.incidentId()).isEqualTo(ID);
        assertThat(result.probableCause()).isEqualTo("A dependency may be failing.");
        assertThat(result.confidence().name()).isEqualTo("MEDIUM");
        assertThat(result.evidence()).extracting(e -> e.id()).containsExactly("E1", "E2", "E3");
        assertThat(result.evidence().getFirst().data()).containsEntry("rootService", "checkout");
        var order = inOrder(gateway, session, model);
        order.verify(gateway).open(ID);
        order.verify(session).call("get_incident", Map.of("incidentId", ID));
        order.verify(session).call("get_related_events", Map.of("incidentId", ID));
        order.verify(session).call("get_service_health", Map.of("serviceName", "checkout"));
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        order.verify(model).call(prompt.capture());
        order.verify(session).close();
        assertThat(prompt.getValue().getSystemMessage().getText()).contains("Observed Evidence", "Probable Cause", "Recommendations", "untrusted", "causality", "LOW",
                "retrieve the incident", "inspect related events", "inspect affected service health");
        assertThat(prompt.getValue().getContents()).contains("E1", "rootService", "evidenceIds", "93a40209");
        assertThat(((ToolCallingChatOptions) prompt.getValue().getOptions()).getToolCallbacks())
                .extracting(c -> c.getToolDefinition().name()).containsExactlyInAnyOrder(
                        "get_incident", "get_related_events", "get_service_health", "get_recent_errors", "get_error_frequency");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", " 93a40209-8dc9-4c4d-9162-a298ae31306f", "93A40209-8DC9-4C4D-9162-A298AE31306F"})
    void rejectsNonCanonicalIdsBeforeOpeningMcp(String id) {
        assertThatThrownBy(() -> service.analyze(id)).isInstanceOfSatisfying(com.umass.analysis.exception.AnalysisException.class, ex -> {
            assertThat(ex.status().value()).isEqualTo(400);
            assertThat(ex.code()).isEqualTo("INVALID_INCIDENT_ID");
        });
        verifyNoInteractions(gateway, model);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 503})
    void mandatoryMcpFailureNeverCallsModelAndSanitizesErrors(int status) {
        preflight();
        when(session.call("get_incident", Map.of("incidentId", ID))).thenThrow(new com.umass.analysis.exception.AnalysisException(
                org.springframework.http.HttpStatus.valueOf(status), "NOT_FOUND", "secret-upstream-body"));
        assertError(() -> service.analyze(ID), status, status == 404 ? "INCIDENT_NOT_FOUND" : "DIAGNOSTICS_UNAVAILABLE");
        verifyNoInteractions(model);
        verify(session).close();
    }

    @Test void mapsModelExceptionsToSafeUnavailableResponse() {
        preflight();
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("sk-secret upstream body"));
        assertError(() -> service.analyze(ID), 503, "LLM_UNAVAILABLE");
        verify(session).close();
    }

    @Test void logsOnlySafeMetadataForWrappedProviderFailure() {
        preflight();
        var providerFailure = mock(com.openai.errors.BadRequestException.class);
        when(providerFailure.statusCode()).thenReturn(400);
        when(providerFailure.getMessage()).thenReturn("private-provider-body secret-key");
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private-prompt", providerFailure));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IncidentAnalysisService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertError(() -> service.analyze(ID), 503, "LLM_UNAVAILABLE");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("MODEL_CALL_FAILED exceptionType=BadRequestException httpStatus=400");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getArgumentArray()).allSatisfy(argument -> {
                    assertThat(argument).isNotInstanceOf(Throwable.class);
                    assertThat(String.valueOf(argument)).doesNotContain("private", "secret", ID);
                });
            });
            verify(providerFailure, never()).body();
            verify(providerFailure, never()).headers();
            verify(providerFailure, never()).getMessage();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("emptyModelResponses")
    void logsOnlySafeFinishReasonForEmptyModelOutput(ChatResponse response, String expectedReason) {
        preflight();
        when(model.call(any(Prompt.class))).thenReturn(response);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(IncidentAnalysisService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertError(() -> service.analyze(ID), 503, "LLM_UNAVAILABLE");
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("MODEL_EMPTY_RESPONSE finishReason=" + expectedReason);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getArgumentArray()).allSatisfy(argument ->
                        assertThat(String.valueOf(argument)).doesNotContain("private", "secret", ID));
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> emptyModelResponses() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(null, "missing_result"),
                org.junit.jupiter.params.provider.Arguments.of(new ChatResponse(List.of()), "missing_result"),
                org.junit.jupiter.params.provider.Arguments.of(new ChatResponse(List.of(new Generation(new AssistantMessage(" "),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build()))), "length"),
                org.junit.jupiter.params.provider.Arguments.of(new ChatResponse(List.of(new Generation(new AssistantMessage(""),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("private-provider-body secret-key").build()))), "unknown"));
    }

    @Test void mapsEmptyModelResponseToUnavailable() {
        preflight();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertError(() -> service.analyze(ID), 503, "LLM_UNAVAILABLE");
    }

    static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(com.umass.analysis.exception.AnalysisException.class, ex -> {
            assertThat(ex.status().value()).isEqualTo(status);
            assertThat(ex.code()).isEqualTo(code);
            assertThat(ex.getMessage()).doesNotContain("secret", "upstream");
            assertThat(ex.getCause()).isNull();
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidDrafts")
    void rejectsMalformedOrFabricatedDrafts(String text) {
        preflight();
        when(model.call(any(Prompt.class))).thenReturn(answer(text));
        assertError(() -> service.analyze(ID), 502, "INVALID_MODEL_RESPONSE");
    }
    static java.util.stream.Stream<String> invalidDrafts() {
        return java.util.stream.Stream.of("not JSON", "{}", draft().replace(ID, "d7f12739-2d47-416a-920c-68ee20165fa4"),
                draft().replace("MEDIUM", "CERTAIN"), draft().replace("\"E2\"", "\"E999\""),
                draft().replace("\"E2\"", "\"E1\""), draft().replace("A dependency may be failing.", " "),
                draft().replace("[\"Inspect dependency health.\"]", "[]"),
                draft().replace("[\"E1\",\"E2\",\"E3\"]", "[1]"),
                draft().replace("\"confidence\":\"MEDIUM\",", ""),
                draft().replace("\"incidentId\":", "\"fabricated\":true,\"incidentId\":"));
    }

    @Test void rejectsOversizedModelResponse() {
        preflight();
        service = new IncidentAnalysisService(model, gateway, properties(3, 8, 64000, 1024), JsonMapper.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(answer(draft().replace("A dependency may be failing.", "x".repeat(1100))));
        assertError(() -> service.analyze(ID), 502, "INVALID_MODEL_RESPONSE");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"errors", "empty", "truncated", "hasMore"})
    void fetchesRecentErrorsWhenCoverageOrHealthRequiresIt(String trigger) {
        preflight();
        if (trigger.equals("errors")) when(session.call("get_service_health", Map.of("serviceName", "checkout")))
                .thenReturn(Map.of("errorsLastMinute", 2));
        else when(session.call("get_related_events", Map.of("incidentId", ID)))
                .thenReturn(Map.of("events", trigger.equals("empty") ? List.of() : List.of(Map.of("message", "timeout")),
                        "truncated", trigger.equals("truncated"), "hasMore", trigger.equals("hasMore")));
        when(session.call("get_recent_errors", Map.of("serviceName", "checkout"))).thenReturn(Map.of("events", List.of()));
        when(model.call(any(Prompt.class))).thenReturn(answer(draft().replace("MEDIUM", "HIGH").replace("\"E1\",\"E2\",\"E3\"", "\"E1\"")));
        var result = service.analyze(ID);
        assertThat(result.evidence()).hasSize(4);
        assertThat(result.evidence().getLast().toolName()).isEqualTo("get_recent_errors");
        if (trigger.equals("truncated") || trigger.equals("hasMore")) {
            assertThat(result.confidence().name()).isEqualTo("LOW");
            assertThat(result.limitations()).isNotEmpty();
        }
    }

    @Test void rejectsOversizedEvidenceBeforeModelRatherThanRewritingObservedFacts() {
        preflight();
        when(session.call("get_related_events", Map.of("incidentId", ID)))
                .thenReturn(Map.of("events", List.of(Map.of("message", "timeout")), "largeField", "x".repeat(5000)));
        service = new IncidentAnalysisService(model, gateway, properties(3, 8, 1024, 16000), JsonMapper.builder().build());
        assertError(() -> service.analyze(ID), 503, "DIAGNOSTICS_UNAVAILABLE");
        verifyNoInteractions(model);
    }

    @Test void defensivelyCopiesNestedToolDataWithoutChangingObservedValues() {
        preflight();
        var event = new java.util.LinkedHashMap<String, Object>(Map.of("message", "timeout"));
        when(session.call("get_related_events", Map.of("incidentId", ID))).thenReturn(Map.of("events", List.of(event)));
        when(model.call(any(Prompt.class))).thenReturn(answer(draft()));
        var result = service.analyze(ID);
        event.put("message", "changed after capture");
        assertThat(JsonMapper.builder().build().writeValueAsString(result.evidence())).doesNotContain("changed after capture");
        assertThatThrownBy(() -> result.evidence().getFirst().data().put("id", "changed")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void invalidConfidenceDoesNotReachConverterLoggingWithRawModelText() {
        preflight();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.springframework.ai.converter.BeanOutputConverter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(model.call(any(Prompt.class))).thenReturn(answer(draft().replace("MEDIUM", "BOGUS")
                    .replace("A dependency may be failing.", "private-model-text")));
            assertError(() -> service.analyze(ID), 502, "INVALID_MODEL_RESPONSE");
            assertThat(appender.list).noneSatisfy(event -> assertThat(event.getFormattedMessage()).contains("private-model-text"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test void continuesModelToolCallsThroughScopedSessionAndOriginalAssistantMessage() {
        preflight();
        var assistant = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                "call-1", "function", "get_error_frequency", "{\"serviceName\":\"checkout\"}"))).build();
        when(session.call("get_error_frequency", Map.of("serviceName", "checkout"))).thenReturn(Map.of("errorsPerSecond", 0.5));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(assistant))), answer(draft()));
        var result = service.analyze(ID);
        assertThat(result.evidence().getLast().id()).isEqualTo("E4");
        assertThat(result.evidence().getLast().data()).containsEntry("errorsPerSecond", 0.5);
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().getLast().getInstructions()).contains(assistant);
        var tool = (org.springframework.ai.chat.messages.ToolResponseMessage) prompts.getAllValues().getLast().getInstructions().getLast();
        assertThat(tool.getResponses().getFirst().id()).isEqualTo("call-1");
        assertThat(tool.getResponses().getFirst().responseData()).contains("E4", "errorsPerSecond", "0.5");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("forbiddenTools")
    void rejectsToolPolicyViolationsBeforeCallingSession(AssistantMessage.ToolCall call) {
        preflight();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().toolCalls(List.of(call)).build()))));
        assertError(() -> service.analyze(ID), 502, "INVALID_MODEL_RESPONSE");
        verify(session, times(3)).call(any(), any());
    }
    static java.util.stream.Stream<AssistantMessage.ToolCall> forbiddenTools() {
        return java.util.stream.Stream.of(
                new AssistantMessage.ToolCall("c1", "function", "resolve_incident", "{\"incidentId\":\"" + ID + "\"}"),
                new AssistantMessage.ToolCall("c1", "function", "unknown_tool", "{}"),
                new AssistantMessage.ToolCall("c1", "function", "get_related_events", "{\"incidentId\":\"d7f12739-2d47-416a-920c-68ee20165fa4\"}"),
                new AssistantMessage.ToolCall("c1", "function", "get_service_health", "{\"serviceName\":\"billing\"}"),
                new AssistantMessage.ToolCall("c1", "function", "get_service_health", "{\"serviceName\":\"checkout\",\"extra\":true}"),
                new AssistantMessage.ToolCall("c1", "function", "get_service_health", "{\"serviceName\":1}"),
                new AssistantMessage.ToolCall("c1", "function", "get_service_health", "not JSON"),
                new AssistantMessage.ToolCall("c1", "function", "get_service_health", "{\"serviceName\":\"billing\",\"serviceName\":\"checkout\"}"),
                new AssistantMessage.ToolCall("", "function", "get_service_health", "{\"serviceName\":\"checkout\"}"));
    }

    @Test void rejectsToolContinuationPastRoundLimitWithoutExecutingIt() {
        preflight();
        service = new IncidentAnalysisService(model, gateway, properties(1, 8, 64000, 16000), JsonMapper.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(toolResponse(1), answer(draft()));
        assertError(() -> service.analyze(ID), 502, "INVALID_MODEL_RESPONSE");
        verify(model).call(any(Prompt.class));
        verify(session, times(3)).call(any(), any());
    }

    @Test void rejectsWholeToolBatchExceedingRemainingCallBudget() {
        preflight();
        service = new IncidentAnalysisService(model, gateway, properties(3, 4, 64000, 16000), JsonMapper.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(toolResponse(2), answer(draft()));
        assertError(() -> service.analyze(ID), 502, "INVALID_MODEL_RESPONSE");
        verify(session, times(3)).call(any(), any());
    }
    static ChatResponse toolResponse(int count) {
        var tools = java.util.stream.IntStream.range(0, count).mapToObj(i -> new AssistantMessage.ToolCall(
                "call-" + i, "function", "get_error_frequency", "{\"serviceName\":\"checkout\"}")).toList();
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(tools).build())));
    }

    void preflight() {
        when(gateway.open(ID)).thenReturn(session);
        when(session.call("get_incident", Map.of("incidentId", ID))).thenReturn(Map.of("id", ID, "rootService", "checkout", "title", "errors"));
        when(session.call("get_related_events", Map.of("incidentId", ID))).thenReturn(Map.of("events", List.of(Map.of("message", "timeout")), "truncated", false));
        when(session.call("get_service_health", Map.of("serviceName", "checkout"))).thenReturn(Map.of("serviceName", "checkout", "errorsLastMinute", 0));
    }
    static AnalysisProperties properties(int rounds, int tools, int evidence, int response) {
        return new AnalysisProperties(URI.create("http://localhost:8084"), Duration.ofSeconds(3), Duration.ofSeconds(15), rounds, tools, evidence, response);
    }
    static String draft() { return """
            {"incidentId":"%s","probableCause":"A dependency may be failing.","confidence":"MEDIUM","evidenceIds":["E1","E2","E3"],"recommendedActions":["Inspect dependency health."]}
            """.formatted(ID); }
    static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
