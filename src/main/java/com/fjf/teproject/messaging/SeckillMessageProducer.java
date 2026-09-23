package com.fjf.teproject.messaging;

import com.fjf.teproject.config.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 把抢购成功的记录投递到队列。
 *
 * <p><b>只做投递，不做重试、也不吞异常。</b>投递失败时直接抛出，由
 * {@link com.fjf.teproject.service.SeckillService} 决定怎么处理——因为只有调用方
 * 知道「库存已经扣减、用户已经拿到 200」这个上下文，也只有它有权决定
 * 是重试、还是放弃并把缺口留给 A1 对账（见 spec §8.3）。</p>
 *
 * <p>反过来做的坏处很具体：如果这里自己重试并吞掉失败，调用方的重试逻辑就永远
 * 不会被触发，成为读起来像保护、实际不可达的死代码；两层重试还会相乘
 * （3×3 = 9 次投递）。职责放在一处，读代码的人才能确定重试了几次。</p>
 */
@Component
public class SeckillMessageProducer {

    private final RabbitTemplate rabbitTemplate;

    public SeckillMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 投递一条抢购消息。
     *
     * @throws org.springframework.amqp.AmqpException 投递失败（broker 不可达等）
     */
    public void publish(long seckillId, String userId) {
        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, new SeckillMessage(seckillId, userId));
    }
}
