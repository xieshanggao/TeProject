package com.fjf.teproject.messaging;

import com.fjf.teproject.config.RabbitConfig;
import com.fjf.teproject.repository.SeckillRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费抢购消息，把结果落库。
 *
 * <p>采用 {@code auto} ack（{@code spring.rabbitmq.listener.simple.acknowledge-mode=auto}，
 * 也是 Spring AMQP 的默认值）：监听器正常返回时才 ack，抛异常时不 ack，
 * 由重试/死信机制接管。这已经满足「事务提交成功后才 ack」——
 * {@link SeckillRecordRepository#recordPurchase} 的 {@code @Transactional}
 * 在方法正常返回前就已提交，异常路径下事务回滚且消息不被 ack。</p>
 *
 * <p>之所以不用手动 ack：手动模式在这里换不到任何额外保证，却多出
 * 「某条路径忘记 ack → 消息在 unacked 里堆积 → 队列被拖垮」这个手写错误面。
 * 本系统不需要比「方法返回」更细粒度的确认时机。</p>
 *
 * <p>消费必须幂等：消息是至少一次投递，重复消息由唯一索引挡下，
 * {@link SeckillRecordRepository#recordPurchase} 返回 false 时同样视为
 * 处理成功并 ack，否则重复消息会永远重投。</p>
 */
@Component
public class SeckillMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageConsumer.class);

    private final SeckillRecordRepository recordRepository;

    public SeckillMessageConsumer(SeckillRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * 落库一次抢购。
     *
     * <p>抛出的异常会让消息按 {@code spring.rabbitmq.listener.simple.retry}
     * 配置重试，重试耗尽后进入死信队列。若在方法内捕获所有异常并正常返回，
     * 消息会被 ack 且永久丢失。</p>
     */
    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onMessage(SeckillMessage message) {
        boolean inserted = recordRepository.recordPurchase(message.getSeckillId(), message.getUserId());
        if (inserted) {
            log.debug("落库成功 seckillId={}, userId={}", message.getSeckillId(), message.getUserId());
        } else {
            // 重复消息：唯一索引已经挡下，这正是幂等的表现，不是错误。
            log.debug("重复消息已忽略 seckillId={}, userId={}", message.getSeckillId(), message.getUserId());
        }
    }
}
