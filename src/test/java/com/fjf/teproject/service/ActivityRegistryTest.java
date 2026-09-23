package com.fjf.teproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRegistryTest {

    private ActivityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ActivityRegistry();
    }

    @Test
    void unknownActivityIsNotPresent() {
        assertFalse(registry.exists(1L));
    }

    @Test
    void registeredActivityIsPresent() {
        registry.register(Collections.singletonList(1L));

        assertTrue(registry.exists(1L));
    }

    @Test
    void nullIdIsNeverPresent() {
        // 避免把「参数缺失」当成「活动不存在」之外的第二类问题，
        // 也避免 ConcurrentHashMap 键为 null 时抛 NPE。
        assertFalse(registry.exists(null));
    }

    @Test
    void clearRemovesEverything() {
        registry.register(Arrays.asList(1L, 2L, 3L));

        registry.clear();

        assertFalse(registry.exists(1L));
    }

    @Test
    void concurrentRegistrationDoesNotLoseEntries() throws InterruptedException, ExecutionException {
        // 预热可能由多个线程并发触发，注册表必须能安全承受。
        int threadCount = 16;
        int idsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        // 保留 Future 是为了在 done.await 之后重新抛出工作线程的异常：
        // done.countDown 在 finally 中执行，因此裸信号量无法区分「正常结束」与「register 抛异常」。
        List<Future<?>> futures = new ArrayList<>(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int base = t * idsPerThread;
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    List<Long> batch = new ArrayList<>();
                    for (int i = 0; i < idsPerThread; i++) {
                        batch.add((long) (base + i));
                    }
                    registry.register(batch);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // get() 会把工作线程未捕获的异常包装成 ExecutionException 抛出，使缺陷直接失败而非被静默吞掉。
        for (Future<?> future : futures) {
            future.get();
        }

        assertTrue(registry.exists(0L));
        assertTrue(registry.exists((long) (threadCount * idsPerThread - 1)));
        // 只抽样首尾 id 无法发现竞态下丢失的中间条目（如 HashSet 扩容时被覆盖），
        // 因此以总数断言：任何一次丢失都会确定性地失败。
        assertEquals(threadCount * idsPerThread, registry.size());
    }
}
