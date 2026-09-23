package com.fjf.teproject.concurrency;

import com.fjf.teproject.messaging.SeckillMessageProducer;
import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T3：验证消费幂等——数据库唯一索引对「至少一次投递」的兜底。
 *
 * <p>直接向队列重复投递同一 (seckillId, userId) 消息，模拟消息中间件
 * 的重投行为。断言流水只有一条，且库存只被扣减一次。</p>
 *
 * <p>本类不经过 HTTP，因此不需要 accept-count 之类的连接侧配置：
 * 生产者是同步调用，不存在连接风暴。</p>
 */
@Tag("integration")
class ConsumerIdempotencyIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillMessageProducer producer;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    @Test
    void repeatedMessagesPersistExactlyOnceAndDeductExactlyOnce() {
        int stockBefore = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        for (int i = 0; i < 3; i++) {
            producer.publish(SECKILL_ID, "u_dup");
        }

        await().atMost(Duration.ofSeconds(15))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        // 再观察一段时间，确认没有迟到的重复落库。
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        assertEquals(stockBefore - 1,
                activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock(),
                "重复消息不得二次扣减库存");
    }
}
