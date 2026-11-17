package com.umass.diagnostics.dto;
/** Redis-backed incident-service health count, not a history-derived frequency. */
public record ErrorFrequency(String serviceName, int windowSeconds, long errorsLastMinute, double errorsPerSecond) { }
