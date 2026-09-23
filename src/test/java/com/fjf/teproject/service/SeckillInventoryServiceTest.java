package com.fjf.teproject.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeckillInventoryServiceTest {

    @Test
    void purchaseDoesNotOversellWhenRequestsAreConcurrent() throws InterruptedException {
        int initialStock = 100;
        int requestCount = 200;
        SeckillInventoryService service = new SeckillInventoryService(initialStock);
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(requestCount);
        AtomicInteger successfulPurchases = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 先让所有任务就绪，再同时开始，增加并发竞争发生的概率。
        for (int i = 0; i < requestCount; i++) {
            executorService.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (service.purchase() >= 0) {
                        successfulPurchases.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failure.set(exception);
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        executorService.shutdown();

        assertNull(failure.get());
        assertEquals(initialStock, successfulPurchases.get());
        assertEquals(0, service.getRemainingStock());
    }
}
