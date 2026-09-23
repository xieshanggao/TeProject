package com.fjf.teproject.concurrency;

import com.fjf.teproject.TeProjectApplication;
import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4：验证两个<b>独立应用实例</b>共享同一份 Redis 时，全局不超卖（I1）。
 *
 * <p><b>为什么必须真的有两个 ApplicationContext：</b>本用例的配套验证是
 * 「把库存挪回应用内存应当让本测试失败」。如果第二个实例只是同一个上下文里
 * 的另一个 HTTP 客户端，那么 {@code SeckillStockRepository} 只有一个 Bean，
 * 实例级的 {@code AtomicInteger} 会被所有请求共享——错误实现照样能通过，
 * 测试就等于什么都没验证。只有两个上下文才各自持有独立的 Repository Bean，
 * 才能证伪「库存权威副本放在应用内存」这一最危险的错误实现。</p>
 *
 * <p>第二个实例用 {@link SpringApplicationBuilder} 在本 JVM 内启动：容器仍由
 * {@link IntegrationTestBase} 的静态块提供并共享，连接参数通过<b>命令行参数</b>
 * 传入（命令行参数优先级最高，能盖过 application.properties 里的
 * {@code ${SECKILL_DB_URL:localhost}} 默认值）。</p>
 *
 * <p>两个实例各自注册进自己的内存注册表、各自尝试预热；SETNX 保证只有第一个
 * 实例真正写入 Redis 库存。压测时请求轮流打到两个端口，全局成功数必须恰好
 * 等于初始库存。</p>
 */
@Tag("integration")
@TestPropertySource(properties = "server.tomcat.accept-count=1000")
class MultiInstanceConsistencyIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;
    private static final int INITIAL_STOCK = 100;
    private static final int REQUESTS_PER_INSTANCE = 100;

    private static ConfigurableApplicationContext secondInstanceContext;
    private static TestRestTemplate secondInstanceRestTemplate;
    private static String secondInstanceBaseUrl;

    @Autowired
    private TestRestTemplate primaryRestTemplate;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startSecondInstance() {
        secondInstanceContext = new SpringApplicationBuilder(TeProjectApplication.class)
                .run(
                        // 随机端口，避免与主实例（也是随机端口）冲突。
                        "--server.port=0",
                        // 两个实例都会收到约 100 个瞬时连接，理由同 NoOversellIT。
                        "--server.tomcat.accept-count=1000",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.redis.host=" + REDIS.getHost(),
                        "--spring.redis.port=" + REDIS.getMappedPort(6379),
                        "--spring.rabbitmq.host=" + RABBITMQ.getHost(),
                        "--spring.rabbitmq.port=" + RABBITMQ.getAmqpPort(),
                        "--spring.rabbitmq.username=" + RABBITMQ.getAdminUsername(),
                        "--spring.rabbitmq.password=" + RABBITMQ.getAdminPassword(),
                        // 对账定时器不在测试期间触发。
                        "--seckill.reconcile.interval-seconds=3600");

        int secondPort = ((ServletWebServerApplicationContext) secondInstanceContext).getWebServer().getPort();
        secondInstanceRestTemplate = new TestRestTemplate();
        System.out.println("[MultiInstanceIT] second instance listening on port " + secondPort);
        secondInstanceBaseUrl = "http://localhost:" + secondPort;
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondInstanceContext != null) {
            // 不关掉的话，第二个 Tomcat / 监听器容器会让 forked JVM 无法退出。
            secondInstanceContext.close();
            secondInstanceContext = null;
        }
    }

    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
        stockRepository.forceSetStock(SECKILL_ID, INITIAL_STOCK);
    }

    @Test
    void twoInstancesSharingRedisNeverOversellGlobally() throws Exception {
        assertNotNull(secondInstanceContext, "第二个实例必须已启动，否则本测试退化为单实例");

        // 第二个实例启动时已经预热过：它把活动登进了自己的内存注册表，
        // 但 SETNX 不会覆盖 Redis 中已有的库存。
        SeckillStockRepository secondInstanceStock =
                secondInstanceContext.getBean(SeckillStockRepository.class);
        SeckillWarmUpRunner secondInstanceWarmUp =
                secondInstanceContext.getBean(SeckillWarmUpRunner.class);

        // 刻意传一个与当前值不同的库存：若实现退化成无条件 SET，库存会变成 101，
        // 下面两条断言都会红。传相同的值（brief 原文）即使被覆盖也看不出来。
        boolean secondInstanceWroteStock = secondInstanceStock.warmUp(SECKILL_ID, INITIAL_STOCK + 1);
        assertTrue(!secondInstanceWroteStock,
                "第二个实例的预热不得覆盖 Redis 中已有的库存");
        assertEquals(INITIAL_STOCK, stockRepository.getRemainingStock(SECKILL_ID),
                "预热不得复活已存在的库存：SETNX 必须失败，值应保持 " + INITIAL_STOCK);

        // 模拟第二个实例的完整启动流程：登记全部活动 + 逐个尝试预热。
        // 活动 2/3 的库存键已被 resetRedis 清掉，因此它们会被正常写入——
        // 这是预期行为；关键不变量是活动 1 已存在的库存不得被改写。
        secondInstanceWarmUp.warmUpAll();
        assertEquals(INITIAL_STOCK, stockRepository.getRemainingStock(SECKILL_ID),
                "第二个实例完整预热后，活动 1 的库存仍不得被改写");

        // 两个实例同时打压，请求轮流打到两个端口。
        int totalRequests = REQUESTS_PER_INSTANCE * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(totalRequests);
        AtomicInteger primarySuccesses = new AtomicInteger();
        AtomicInteger secondSuccesses = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < totalRequests; i++) {
            final String userId = "multi_u" + i;
            final boolean toPrimary = (i % 2 == 0);
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    String body;
                    if (toPrimary) {
                        body = primaryRestTemplate.postForEntity(
                                "/api/seckill/activities/" + SECKILL_ID + "/purchase",
                                Collections.singletonMap("userId", userId),
                                String.class).getBody();
                    } else {
                        body = secondInstanceRestTemplate.postForEntity(
                                secondInstanceBaseUrl + "/api/seckill/activities/" + SECKILL_ID
                                        + "/purchase",
                                Collections.singletonMap("userId", userId),
                                String.class).getBody();
                    }
                    if (body != null && body.contains("\"code\":\"SUCCESS\"")) {
                        if (toPrimary) {
                            primarySuccesses.incrementAndGet();
                        } else {
                            secondSuccesses.incrementAndGet();
                        }
                    }
                } catch (Throwable throwable) {
                    // submit 会吞掉工作线程的异常，必须收集后断言。
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "所有线程应在 10 秒内就绪");
        start.countDown();
        assertTrue(completed.await(90, TimeUnit.SECONDS), "所有请求应在 90 秒内完成");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "线程池应在 10 秒内终止");

        assertNull(failure.get(), "并发请求不应抛异常");

        int successes = primarySuccesses.get() + secondSuccesses.get();
        System.out.println("[MultiInstanceIT] primarySuccesses=" + primarySuccesses.get()
                + " secondSuccesses=" + secondSuccesses.get()
                + " totalSuccesses=" + successes + " expected=" + INITIAL_STOCK);

        // 若两个实例各自持有内存库存，这里会看到 200 而不是 100。
        assertEquals(INITIAL_STOCK, successes,
                "全局成功数必须恰好等于初始库存，多一件即为超卖");
        assertEquals(0, stockRepository.getRemainingStock(SECKILL_ID),
                "共享的 Redis 库存应归零且不为负");

        // 最终一致（I4）：两个实例的消费者把流水追平，DB 副本与 Redis 权威值收敛。
        await().atMost(Duration.ofSeconds(60))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == INITIAL_STOCK);
        assertEquals(0,
                activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock(),
                "数据库库存副本应最终归零，与 Redis 权威值一致");
    }
}
