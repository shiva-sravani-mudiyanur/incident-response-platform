package com.umass.incident.service;

import com.umass.incident.config.CorrelationProperties;
import com.umass.incident.dto.ServiceEvent;
import com.umass.incident.model.EventSeverity;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;

@Component
public class RedisFailureCounter {
    // One atomic sliding-window update; Redis server time avoids application clock skew.
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RECORD_FAILURE = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[3])
            local cutoff = now - window
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', cutoff)
            local added = redis.call('ZADD', KEYS[1], 'NX', now, ARGV[1])
            if added == 1 and ARGV[2] == '1' then
                redis.call('ZADD', KEYS[2], now, ARGV[1])
            end
            redis.call('PEXPIRE', KEYS[1], window)
            redis.call('PEXPIRE', KEYS[2], window)
            -- Health always means the last minute, not the configurable correlation window.
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - 60000)
            redis.call('ZADD', KEYS[3], 'NX', now, ARGV[1])
            redis.call('PEXPIRE', KEYS[3], 60000)
            return {redis.call('ZCARD', KEYS[1]), redis.call('ZCARD', KEYS[2])}
            """, List.class);
    private final StringRedisTemplate redis;
    private final long windowMillis;

    public RedisFailureCounter(StringRedisTemplate redis, CorrelationProperties properties) {
        this.redis = redis;
        this.windowMillis = Duration.ofSeconds(properties.windowSeconds()).toMillis();
    }

    public WindowSnapshot record(ServiceEvent event) {
        String key = "errors:{" + event.serviceName() + "}";
        List<?> result = redis.execute(RECORD_FAILURE,
                List.of(key, key + ":critical", "health-errors:{" + event.serviceName() + "}"),
                event.eventId().toString(), event.severity() == EventSeverity.CRITICAL ? "1" : "0",
                Long.toString(windowMillis));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Redis did not return a correlation window");
        }
        return new WindowSnapshot(((Number) result.get(0)).longValue(), ((Number) result.get(1)).longValue() > 0);
    }

    public record WindowSnapshot(long count, boolean hasCritical) {
    }
}
