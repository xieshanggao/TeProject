package com.fjf.teproject.reconcile;

import java.time.Instant;

/**
 * 某一个活动在对账时刻的两侧状态快照。
 *
 * <p>把 IO 的结果收敛成一个不可变值对象，使判定逻辑可以脱离 Redis 与
 * 数据库被穷举测试。</p>
 */
public final class ReconcileSnapshot {

    private final long seckillId;
    private final int initialStock;
    private final int redisStock;
    private final int dbRemainingStock;
    private final int recordCount;
    private final Instant stockUpdatedAt;

    private ReconcileSnapshot(long seckillId, int initialStock, int redisStock,
                              int dbRemainingStock, int recordCount,
                              Instant stockUpdatedAt) {
        this.seckillId = seckillId;
        this.initialStock = initialStock;
        this.redisStock = redisStock;
        this.dbRemainingStock = dbRemainingStock;
        this.recordCount = recordCount;
        this.stockUpdatedAt = stockUpdatedAt;
    }

    public static ReconcileSnapshot of(long seckillId, int initialStock, int redisStock,
                                       int dbRemainingStock, int recordCount,
                                       Instant stockUpdatedAt) {
        return new ReconcileSnapshot(seckillId, initialStock, redisStock,
                dbRemainingStock, recordCount, stockUpdatedAt);
    }

    public long getSeckillId() {
        return seckillId;
    }

    public int getInitialStock() {
        return initialStock;
    }

    public int getRedisStock() {
        return redisStock;
    }

    public int getDbRemainingStock() {
        return dbRemainingStock;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public Instant getStockUpdatedAt() {
        return stockUpdatedAt;
    }
}
