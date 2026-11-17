package com.umass.diagnostics.dto;
import java.time.Instant;
/** Boxed counts preserve the distinction between zero and missing downstream fields. */
public record ServiceHealth(String serviceName, String status, Long errorsLastMinute,
                            Long activeIncidentCount, Instant lastEventTimestamp) { }
