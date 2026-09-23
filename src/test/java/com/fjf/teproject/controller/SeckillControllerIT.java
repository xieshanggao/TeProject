package com.fjf.teproject.controller;

import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP 契约的集成测试：真实 Redis / MySQL / RabbitMQ 下验证 spec §7.2 的响应语义。
 */
@Tag("integration")
class SeckillControllerIT extends IntegrationTestBase {

    private static final long ACTIVITY_A = 1L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

    @Autowired
    private SeckillStockRepository stockRepository;

    @BeforeEach
    void setUp() {
        resetRedis();
        warmUpRunner.warmUpAll();
    }

    private ResponseEntity<String> purchase(long seckillId, String userId) {
        return restTemplate.postForEntity(
                "/api/seckill/activities/" + seckillId + "/purchase",
                Collections.singletonMap("userId", userId),
                String.class);
    }

    private ResponseEntity<String> getStock(long seckillId) {
        return restTemplate.getForEntity(
                "/api/seckill/activities/" + seckillId + "/stock", String.class);
    }

    @Test
    void successfulPurchaseReturns200WithStringCode() {
        ResponseEntity<String> response = purchase(ACTIVITY_A, "u1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"SUCCESS\""));
        assertEquals(true, response.getBody().contains("\"remainingStock\":99"));
    }

    @Test
    void unknownActivityReturns404Not503() {
        // 关键区分：客户端传错 id 是 404，不能伪装成服务端故障。
        // 与下一个用例合起来证明 404 与 503 在真实输入下是可区分的：
        //   id=999 不在内存注册表  → 404
        //   id=1 在注册表但 Redis 被清空 → 503
        ResponseEntity<String> response = purchase(999L, "u1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"SECKILL_NOT_FOUND\""));
    }

    @Test
    void missingUserIdReturns400() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/seckill/activities/" + ACTIVITY_A + "/purchase",
                Collections.emptyMap(), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"INVALID_REQUEST\""));
    }

    @Test
    void repeatPurchaseReturns409AlreadyPurchased() {
        purchase(ACTIVITY_A, "u1");

        ResponseEntity<String> response = purchase(ACTIVITY_A, "u1");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"ALREADY_PURCHASED\""));
    }

    @Test
    void soldOutReturns409StockSoldOut() {
        // 把库存压到 1 再买掉，比抢完活动 C 的 1000 件更直接。
        stockRepository.forceSetStock(ACTIVITY_A, 1);
        purchase(ACTIVITY_A, "u1");

        ResponseEntity<String> response = purchase(ACTIVITY_A, "u2");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"STOCK_SOLD_OUT\""));
    }

    @Test
    void stockQueryReturnsRemainingFromRedis() {
        ResponseEntity<String> response = getStock(ACTIVITY_A);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"remainingStock\":100"));
    }

    @Test
    void stockQueryOnRegisteredButUnwarmedActivityReturns503() {
        // 模拟 Redis 被清空：活动仍在内存注册表中，但没有库存键。
        // 这正是与 404 相对的另一半：同样的接口、同样的 seckillId，
        // 状态不同给出 503 而不是 404。
        resetRedis();

        ResponseEntity<String> response = getStock(ACTIVITY_A);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"NOT_READY\""));
    }

    @Test
    void multipleActivitiesHaveIndependentStock() {
        ResponseEntity<String> responseB = purchase(2L, "u1");

        assertEquals(HttpStatus.OK, responseB.getStatusCode());
        assertEquals(true, responseB.getBody().contains("\"remainingStock\":499"));
    }
}
