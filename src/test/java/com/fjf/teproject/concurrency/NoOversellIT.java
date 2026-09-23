package com.fjf.teproject.concurrency;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
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
 * T1：验证 I1（不超卖）与 I3（不少卖）。
 *
 * <p>并发测试是概率性的——它提高竞争概率，但不保证每次都撞上竞态。
 * 因此本类通过重复执行增强置信度，而非跑一次即宣布正确。</p>
 *
 * <p><b>为何调大 Tomcat 的 accept-count：</b>本测试有意用 200 个线程在同一瞬间
 * 发起 TCP 连接（这是制造库存竞争的手段）。{@code server.tomcat.accept-count}
 * 默认只有 100，即内核 listen 队列深度；瞬时 200 个 SYN 会超出队列，超出部分
 * 被内核直接回 RST，客户端表现为 {@code Connection refused}。实测在默认值下
 * 3 次运行中复现 1 次，且总是发生在第 1 轮的连接阶段、从不发生在不变量断言上。
 * 这是测试自身制造的连接风暴撞上服务器默认容量，与库存不变量无关：把它调大到
 * 1000 让服务器有能力接下这个负载。<b>并发度仍是 200、超时与断言一律未动。</b></p>
 */
@Tag("integration")
@TestPropertySource(properties = "server.tomcat.accept-count=1000")
class NoOversellIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;
    private static final int REPEAT = 10;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

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
        // 注册活动 id（区分 404/503）并把库存键写回 Redis。
        warmUpRunner.warmUpAll();
    }

    @Test
    void concurrentRequestsNeverExceedInitialStock() throws Exception {
        int initialStock = 100;
        int requestCount = 200;

        for (int round = 1; round <= REPEAT; round++) {
            resetRedis();
            jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
            activityRepository.resetToInitial(SECKILL_ID);
            // 清空 Redis 会同时清掉限购集合，因此每一轮 200 个 userId 都是「未购」。
            stockRepository.forceSetStock(SECKILL_ID, initialStock);

            int successes = fireConcurrentPurchases(requestCount);

            // 每一轮的真实观测值都打出来，失败时报告里能看到是哪一轮、差多少。
            System.out.println("[NoOversellIT] round=" + round + " successes=" + successes
                    + " expected=" + initialStock);

            assertEquals(initialStock, successes,
                    "第 " + round + " 轮：成功数应恰好等于初始库存");
            assertEquals(0, stockRepository.getRemainingStock(SECKILL_ID),
                    "第 " + round + " 轮：Redis 库存应归零且不为负");

            // 等待消费者追平，验证最终一致（I4）。
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> recordRepository.countByActivity(SECKILL_ID) == initialStock);
            // brief 此处写成 assertEquals(initialStock, ...)，与它自己的断言消息
            // 「数据库库存副本应最终归零」矛盾：RecordRepository.recordPurchase 每落库
            // 一条就把 remaining_stock 减 1，且 ReconcileEvaluator 的 A2 规则要求
            // recordCount == initialStock - dbRemainingStock。100 条流水必然对应 0。
            // 断言 100 等于断言「库存副本从未被扣减」，那才是缺陷。这里按消息所述断言 0。
            assertEquals(0,
                    activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock(),
                    "第 " + round + " 轮：数据库库存副本应最终归零");
        }
    }

    private int fireConcurrentPurchases(int requestCount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(requestCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 先让所有任务就绪，再同时开始，最大化竞争概率。
        for (int i = 0; i < requestCount; i++) {
            final String userId = "u" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/seckill/activities/" + SECKILL_ID + "/purchase",
                            Collections.singletonMap("userId", userId),
                            String.class);
                    if (response.getBody() != null
                            && response.getBody().contains("\"code\":\"SUCCESS\"")) {
                        successes.incrementAndGet();
                    }
                } catch (Throwable throwable) {
                    // submit 会吞掉工作线程抛出的异常，必须显式收集，
                    // 否则「全部请求失败」会被误读成「成功数为 0」。
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
        return successes.get();
    }
}
