package com.fjf.teproject.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列、交换机与序列化配置。
 *
 * <p>主队列配置了死信交换机：消费重试耗尽后消息进入死信队列，而不是被
 * 丢弃或无限重投。无限重投会阻塞整个队列，一个坏消息就能拖垮所有活动。</p>
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "seckill.purchase.exchange";
    public static final String QUEUE = "seckill.purchase.queue";
    public static final String ROUTING_KEY = "seckill.purchase";
    public static final String DLX_EXCHANGE = "seckill.purchase.dlx";
    public static final String DLQ = "seckill.purchase.dlq";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange seckillDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue()).to(seckillExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding seckillDlqBinding() {
        return BindingBuilder.bind(seckillDeadLetterQueue()).to(seckillDlxExchange()).with(DLQ);
    }

    /**
     * JSON 消息转换器，必须以 bean 的形式暴露。
     *
     * <p>不能只在 {@code rabbitTemplate} 里 new 一个就用：Spring Boot 的
     * {@code SimpleRabbitListenerContainerFactoryConfigurer} 通过
     * {@code ObjectProvider<MessageConverter>.getIfUnique()} 查找转换器，
     * 只有存在唯一的 {@code MessageConverter} bean 时才会把它装到监听器容器上。
     * 若没有这个 bean，监听端会退回默认的 {@code SimpleMessageConverter}，
     * 把消息体解成 {@code byte[]}，消费时抛
     * {@code MessageConversionException: Cannot convert from [[B] to [...]}——
     * 即生产端写 JSON、消费端按字节读，所有消息都会失败并最终被丢进死信队列。</p>
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 使用 JSON 序列化消息体，并要求在发送失败时抛异常而不是静默丢弃。
     *
     * <p>publisher-confirm 与 publisher-return 由 application.properties
     * 中的 {@code spring.rabbitmq.publisher-confirm-type} 与
     * {@code spring.rabbitmq.publisher-returns} 开启；这里是消息不会
     * 无声消失的前提。</p>
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        return template;
    }
}
