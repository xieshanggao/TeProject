package com.fjf.teproject.concurrency;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2：验证 I2（一人一单）在并发下成立。
 *
 * <p>同一用户同时发起大量请求，必须只有一次成功。这是最容易被
 * 「先查再写」这种非原子实现破坏的场景——两个请求可能都在查询时
 * 看到「未购买」，然后双双写入。</p>
 *
 * <p>accept-count 的理由同 {@link NoOversellIT}：本用例有意在同一瞬间
 * 发起 100 个连接，默认 listen 队列深度 100 处在临界点上，偶发的
 * {@code Connection refused} 会被误读成业务失败。并发度与断言未动。</p>
 */
@Tag("integration")
@TestPropertySource(properties = "server.tomcat.accept-count=1000")
class OneUserOneOrderIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;
    private static final String USER_ID = "u_same";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
        stockRepository.forceSetStock(SECKILL_ID, 100);
    }

    @Test
    void sameUserCanOnlySucceedOnceEvenUnderConcurrency() throws Exception {
        int requestCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(requestCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger alreadyPurchased = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/seckill/activities/" + SECKILL_ID + "/purchase",
                            Collections.singletonMap("userId", USER_ID),
                            String.class);
                    String body = response.getBody();
                    if (body != null && body.contains("\"code\":\"SUCCESS\"")) {
                        successes.incrementAndGet();
                    } else if (body != null && body.contains("\"code\":\"ALREADY_PURCHASED\"")) {
                        alreadyPurchased.incrementAndGet();
                    }
                } catch (Throwable throwable) {
                    // submit 会吞掉工作线程的异常，必须收集后断言，
                    // 否则「请求全炸了」会被读成「成功数为 0」。
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "所有线程应在 10 秒内就绪");
        start.countDown();
        assertTrue(completed.await(60, TimeUnit.SECONDS), "所有请求应在 60 秒内完成");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "线程池应在 10 秒内终止");

        assertNull(failure.get(), "并发请求不应抛异常");

        assertEquals(1, successes.get(), "同一用户只能成功一次");
        assertEquals(requestCount - 1, alreadyPurchased.get(), "其余请求应全部返回已购");
        assertEquals(1, stockRepository.countPurchasers(SECKILL_ID), "限购集合应只有 1 个成员");
        assertEquals(99, stockRepository.getRemainingStock(SECKILL_ID), "只应扣减 1 件库存");

        await().atMost(Duration.ofSeconds(20))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);
    }
}
