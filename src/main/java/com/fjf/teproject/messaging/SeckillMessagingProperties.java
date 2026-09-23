package com.fjf.teproject.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息相关配置，绑定前缀 {@code seckill.messaging}。
 */
@ConfigurationProperties(prefix = "seckill.messaging")
public class SeckillMessagingProperties {

    /** 消费失败的最大重试次数，超过后进入死信队列。 */
    private int maxRetries = 3;

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
