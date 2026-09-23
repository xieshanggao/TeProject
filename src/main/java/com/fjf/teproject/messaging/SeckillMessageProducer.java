package com.fjf.teproject.messaging;

import com.fjf.teproject.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 把抢购成功的记录投递到队列。
 *
 * <p><b>已知缺口</b>：Redis 扣减成功与消息投递不是原子的。若投递失败，
 * 用户已经收到 200、库存也真的扣了，但落库消息丢失——这是一次静默的
 * 数据丢失。这里做有限次同步重试，仍失败则记录错误日志，并由 A1 对账
 * 发现不一致后告警。</p>
 *
 * <p>这是刻意接受的取舍而非疏漏。另一个可选方案是把 XADD 写进同一个
 * Lua 脚本（用 Redis Stream 替代 RabbitMQ），从物理上消除缺口，但会
 * 失去 RabbitMQ 的通用性。详见 spec §8.3。</p>
 */
@Component
public class SeckillMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageProducer.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;

    public SeckillMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(long seckillId, String userId) {
        SeckillMessage message = new SeckillMessage(seckillId, userId);
        AmqpException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
                return;
            } catch (AmqpException exception) {
                lastFailure = exception;
                log.warn("投递秒杀消息失败，第 {}/{} 次尝试，seckillId={}, userId={}",
                        attempt, MAX_ATTEMPTS, seckillId, userId, exception);
            }
        }

        // 到这一步说明 Redis 已扣减但消息未投出。记 error 级别日志，
        // 让 A1 对账成为发现该情况的兜底手段。
        log.error("秒杀消息投递最终失败，Redis 已扣减但落库消息丢失，"
                + "seckillId={}, userId={}。等待 A1 对账发现该不一致。",
                seckillId, userId, lastFailure);
    }
}
