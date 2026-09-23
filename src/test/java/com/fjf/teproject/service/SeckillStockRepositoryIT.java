package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Lua 脚本在真实 Redis 中的行为。
 *
 * <p>脚本的返回值契约无法在单元测试中覆盖——它必须运行在 Redis 中，
 * 因此这里逐条验证 -1/-2/-3 的实际语义。</p>
 */
@Tag("integration")
class SeckillStockRepositoryIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillStockRepository repository;

    @BeforeEach
    void setUp() {
        resetRedis();
    }

    /**
     * Lua 契约的 ">= 0" 分支：成功扣减并返回扣减后的库存。
     *
     * <p>brief 里把「普通扣减」和「最后一件（剩余 0）」写成两个用例，两者覆盖的是
     * 同一个返回码分支，按执行约束 3 合并为一个用例，但保留 0 边界断言：把 0 当成
     * 失败会让最后一件永远卖不出去；同时 0 被序列化成整数 0 而非「无结果」，仓储
     * 不能把它当成 null 抛 IllegalStateException。</p>
     */
    @Test
    void deductsAndReturnsRemainingStock() {
        repository.forceSetStock(SECKILL_ID, 5);

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertTrue(outcome.isSuccess());
        assertEquals(4, outcome.getRemainingStock());
        assertEquals(4, repository.getRemainingStock(SECKILL_ID));

        // 最后一件：库存 1 → 扣减后为 0，仍然是成功。
        long lastItemSeckillId = 2L;
        repository.forceSetStock(lastItemSeckillId, 1);

        DeductionOutcome lastItem = repository.tryDeduct(lastItemSeckillId, "u1");

        assertTrue(lastItem.isSuccess());
        assertEquals(0, lastItem.getRemainingStock());
        assertEquals(0, repository.getRemainingStock(lastItemSeckillId));
    }

    @Test
    void returnsSoldOutWhenStockIsZero() {
        repository.forceSetStock(SECKILL_ID, 0);

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT, outcome.getErrorCode());
        assertEquals(0, repository.getRemainingStock(SECKILL_ID));
        // 被拒绝的请求不得写入限购集合。
        assertEquals(0L, repository.countPurchasers(SECKILL_ID));
    }

    @Test
    void secondPurchaseBySameUserIsRejected() {
        repository.forceSetStock(SECKILL_ID, 5);
        repository.tryDeduct(SECKILL_ID, "u1");

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.ALREADY_PURCHASED, outcome.getErrorCode());
        // 被拒绝的请求不得扣减库存。
        assertEquals(4, repository.getRemainingStock(SECKILL_ID));
        assertEquals(1L, repository.countPurchasers(SECKILL_ID));
    }

    @Test
    void notWarmedUpReturnsNotReady() {
        // Redis 中没有库存键——既可能是活动不存在，也可能是预热失败。
        // 脚本只能报告 -3，由 ActivityRegistry 负责区分。
        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.NOT_READY, outcome.getErrorCode());
        // 无键时读取库存返回 -1（哨兵值，不是真实库存）。
        assertEquals(-1, repository.getRemainingStock(SECKILL_ID));
    }

    @Test
    void stockNeverGoesNegativeUnderConcurrentRequests() {
        // Lua 契约层面的不超卖验证；HTTP 层的完整验证在 Task 13。
        int initialStock = 50;
        repository.forceSetStock(SECKILL_ID, initialStock);

        int requestCount = 200;
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(requestCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(requestCount);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(requestCount);
        java.util.concurrent.atomic.AtomicInteger successes =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < requestCount; i++) {
            final String userId = "u" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (repository.tryDeduct(SECKILL_ID, userId).isSuccess()) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        executor.shutdown();

        assertEquals(initialStock, successes.get());
        assertEquals(0, repository.getRemainingStock(SECKILL_ID));
        assertEquals(initialStock, repository.countPurchasers(SECKILL_ID));
    }

    @Test
    void warmUpIsIdempotentAndDoesNotOverwriteExistingStock() {
        // 应用重启场景：Redis 里已有扣减过的库存，预热不得覆盖它。
        repository.forceSetStock(SECKILL_ID, 7);

        boolean written = repository.warmUp(SECKILL_ID, 100);

        assertFalse(written);
        assertEquals(7, repository.getRemainingStock(SECKILL_ID));
    }

    @Test
    void warmUpWritesWhenKeyIsAbsent() {
        boolean written = repository.warmUp(SECKILL_ID, 100);

        assertTrue(written);
        assertEquals(100, repository.getRemainingStock(SECKILL_ID));
    }
}
