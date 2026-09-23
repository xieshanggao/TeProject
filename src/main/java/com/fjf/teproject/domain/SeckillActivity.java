package com.fjf.teproject.domain;

import java.time.LocalDateTime;

/**
 * 秒杀活动的持久化状态。
 *
 * <p>{@code remainingStock} 与 {@code stockUpdatedAt} 是 Redis 权威库存的
 * 异步副本：{@code remainingStock} 供查询与对账使用，{@code stockUpdatedAt}
 * 让对账能够区分「消费者正在追赶」与「消费者已卡死」——没有这个时间戳，
 * 任何异步延迟都会被误报为故障。</p>
 */
public final class SeckillActivity {

    private final long id;
    private final String name;
    private final int initialStock;
    private final int remainingStock;
    private final LocalDateTime stockUpdatedAt;

    public SeckillActivity(long id, String name, int initialStock,
                           int remainingStock, LocalDateTime stockUpdatedAt) {
        this.id = id;
        this.name = name;
        this.initialStock = initialStock;
        this.remainingStock = remainingStock;
        this.stockUpdatedAt = stockUpdatedAt;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getInitialStock() {
        return initialStock;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public LocalDateTime getStockUpdatedAt() {
        return stockUpdatedAt;
    }
}
