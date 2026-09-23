package com.fjf.teproject.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class IntegrationTestBaseSmokeTest extends IntegrationTestBase {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void redisContainerIsReachable() {
        resetRedis();
        redisTemplate.opsForValue().set("smoke", "42");

        assertEquals("42", redisTemplate.opsForValue().get("smoke"));
    }
}
