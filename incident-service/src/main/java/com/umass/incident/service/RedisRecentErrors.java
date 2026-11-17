package com.umass.incident.service;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class RedisRecentErrors {
    // Score filtering uses Redis server time, even if no new event arrives to prune the set.
    // Reads never extend expiry or change the correlation counter.
    private static final DefaultRedisScript<Long> COUNT_LAST_MINUTE = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            return redis.call('ZCOUNT', KEYS[1], '(' .. (now - 60000), now)
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisRecentErrors(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long countLastMinute(String serviceName) {
        Long count = redis.execute(COUNT_LAST_MINUTE, List.of("health-errors:{" + serviceName + "}"));
        if (count == null) throw new DataRetrievalFailureException("Redis did not return recent health state");
        return count;
    }
}
