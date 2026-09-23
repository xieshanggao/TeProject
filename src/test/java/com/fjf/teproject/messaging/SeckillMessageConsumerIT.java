package com.fjf.teproject.messaging;

import com.fjf.teproject.config.RabbitConfig;
import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产者 + 消费者在真实 RabbitMQ / MySQL 上的端到端验证。
 *
 * <p>覆盖三条语义：正常消息最终落库、重复消息只扣一次库存、以及
 * <b>毒消息在重试耗尽后真的进入死信队列</b>。最后一条是「最终一致」里
 * 「不丢消息」的那一半：如果失败消息被静默 ack 或无限重投，整个系统
 * 会表现为「用户抢到了但永远查不到记录」。</p>
 *
 * <p>重试次数在测试里调小，避免毒消息用例额外等待。真正生效的是
 * {@code spring.rabbitmq.listener.simple.retry.max-attempts}（监听器容器读取
 * 的就是它）；{@code seckill.messaging.max-retries} 是同一语义的领域侧声明，
 * 一并调小以保持两者一致。</p>
 */
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.retry.max-attempts=2",
        "seckill.messaging.max-retries=2"
})
@Tag("integration")
class SeckillMessageConsumerIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    /** user_id 列是 VARCHAR(64)，超过它必然被 MySQL 严格模式拒绝。 */
    private static final int POISON_USER_ID_LENGTH = 80;

    @Autowired
    private SeckillMessageProducer producer;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * RabbitMQ 与 MySQL 容器在整个测试 JVM 内复用，用例之间必须显式清理，
     * 否则上一个用例的流水/死信会污染下一个用例的断言。
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
        amqpAdmin.purgeQueue(RabbitConfig.DLQ);
    }

    @Test
    void publishedMessageIsEventuallyPersisted() {
        producer.publish(SECKILL_ID, "u1");

        // 异步落库，用轮询等待而非固定 sleep，避免测试既慢又不稳定。
        await().atMost(Duration.ofSeconds(10))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        assertTrue(recordRepository.exists(SECKILL_ID, "u1"));
    }

    @Test
    void duplicateMessagesPersistExactlyOneRecordAndDeductOnce() {
        // 至少一次投递的重复场景：三条相同消息，只能留下一行流水，
        // 也只能扣减一次库存。
        int before = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        producer.publish(SECKILL_ID, "u1");
        producer.publish(SECKILL_ID, "u1");
        producer.publish(SECKILL_ID, "u1");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        // 再等一小段时间，确认不会有迟到的第二条落库。
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        assertEquals(before - 1,
                activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
    }

    /**
     * 毒消息必须在重试耗尽后被死信队列接住，而不是被静默吞掉。
     *
     * <p>投毒方式：{@code user_id} 长度超过 {@code VARCHAR(64)}。MySQL 处于
     * {@code STRICT_TRANS_TABLES}，INSERT 直接报错，{@code recordPurchase} 抛出
     * {@code DataIntegrityViolationException}——它不是唯一键冲突，
     * 不会被「重复消息」分支吞掉，因此消费者每次尝试都失败。</p>
     *
     * <p>断言 x-death 头是有意为之：只断言「DLQ 里有条消息」可能被别处漏进来的
     * 消息蒙混过关，x-death 才证明这条消息确实是<b>被本队列拒绝后经 DLX 转投</b>
     * 过来的。</p>
     */
    @Test
    void poisonedMessageIsDeadLetteredAfterRetriesAreExhausted() {
        String poisonUser = "poison-" + "x".repeat(POISON_USER_ID_LENGTH);

        producer.publish(SECKILL_ID, poisonUser);

        Message dead = await().atMost(Duration.ofSeconds(30))
                .until(() -> rabbitTemplate.receive(RabbitConfig.DLQ), Objects::nonNull);

        assertNotNull(dead, "毒消息重试耗尽后必须出现在死信队列里");

        String body = new String(dead.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains(poisonUser),
                "死信消息体应包含被投毒的用户 id，实际: " + body);

        List<Map<String, ?>> xDeath = dead.getMessageProperties().getXDeathHeader();
        assertNotNull(xDeath, "死信消息应带 x-death 头，否则无法证明它经过 DLX");
        assertFalse(xDeath.isEmpty(), "x-death 头不应为空");
        assertEquals(RabbitConfig.QUEUE, xDeath.get(0).get("queue"),
                "x-death 应记录消息是从主队列被拒绝的");
        assertEquals("rejected", xDeath.get(0).get("reason"),
                "死信原因应为 rejected（未重新入队）");
    }
}
