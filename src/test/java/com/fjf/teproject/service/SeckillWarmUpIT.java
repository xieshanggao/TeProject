package com.fjf.teproject.service;

import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SeckillWarmUpIT extends IntegrationTestBase {

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private ActivityRegistry activityRegistry;

    @Autowired
    private com.fjf.teproject.repository.SeckillActivityRepository activityRepository;

    @BeforeEach
    void setUp() {
        resetRedis();
        activityRegistry.clear();
    }

    @Test
    void seedDataContainsMultipleActivities() {
        // 多商品是本轮的需求之一，种子数据必须真的提供多个活动。
        List<Long> ids = activityRepository.findAllIds();

        assertTrue(ids.size() >= 3, "种子数据应包含至少 3 个活动，实际 " + ids.size());
    }

    @Test
    void warmUpRegistersAllActivitiesInMemory() {
        warmUpRunner.warmUpAll();

        assertTrue(activityRegistry.exists(1L));
        assertTrue(activityRegistry.exists(2L));
        assertTrue(activityRegistry.exists(3L));
        assertFalse(activityRegistry.exists(999L));
    }

    @Test
    void warmUpWritesStockFromDatabase() {
        warmUpRunner.warmUpAll();

        assertEquals(100, stockRepository.getRemainingStock(1L));
        assertEquals(500, stockRepository.getRemainingStock(2L));
    }

    @Test
    void secondWarmUpDoesNotOverwriteAlreadyDeductedStock() {
        // 这是预热最关键的语义：模拟应用重启，不得复活已售出的库存。
        warmUpRunner.warmUpAll();
        stockRepository.tryDeduct(1L, "u1");
        stockRepository.tryDeduct(1L, "u2");
        assertEquals(98, stockRepository.getRemainingStock(1L));

        int written = warmUpRunner.warmUpAll();

        assertEquals(98, stockRepository.getRemainingStock(1L),
                "预热不得覆盖 Redis 中已扣减的库存");
        assertTrue(written < 3, "已预热的键不应被重复写入，实际写入 " + written);
    }
}
