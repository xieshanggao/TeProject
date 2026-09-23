package com.fjf.teproject.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账规则判定：给定两侧状态快照，判定是否存在不一致。
 *
 * <p>纯函数，不触碰 Redis 或数据库，因此规则本身可以被完整测试。</p>
 *
 * <p>规则（见 spec §9）：</p>
 * <ul>
 *   <li><b>A1</b>：Redis 已扣减数 ≠ DB 已落库数 <b>且</b> 库存时间戳停滞超阈值。
 *       时间戳条件是关键——没有它，「消费者正在正常追赶」会被误报为故障，
 *       告警将因必然误报而失去价值。</li>
 *   <li><b>A2</b>：流水条数 ≠ 库存扣减数。两条语句在同一事务内，正常情况下
 *       恒等，因此这是缺陷/人工改数的金丝雀，只要非 0 就告警。</li>
 * </ul>
 */
public class ReconcileEvaluator {

    public List<ReconcileFinding> evaluate(ReconcileSnapshot snapshot, Instant now,
                                           long stallThresholdSeconds) {
        List<ReconcileFinding> findings = new ArrayList<>();

        int redisDeducted = snapshot.getInitialStock() - snapshot.getRedisStock();
        int dbDeducted = snapshot.getRecordCount();

        if (redisDeducted != dbDeducted && isStalled(snapshot, now, stallThresholdSeconds)) {
            findings.add(new ReconcileFinding(snapshot.getSeckillId(),
                    ReconcileFinding.Kind.A1_REDIS_DB_MISMATCH,
                    "Redis 已扣减 " + redisDeducted + " 件，DB 已落库 " + dbDeducted
                            + " 件，且库存时间戳已停滞超过 " + stallThresholdSeconds + " 秒"));
        }

        int dbDeductedFromStock = snapshot.getInitialStock() - snapshot.getDbRemainingStock();
        if (snapshot.getRecordCount() != dbDeductedFromStock) {
            findings.add(new ReconcileFinding(snapshot.getSeckillId(),
                    ReconcileFinding.Kind.A2_RECORD_STOCK_MISMATCH,
                    "流水 " + snapshot.getRecordCount() + " 条，但库存显示已扣减 "
                            + dbDeductedFromStock + " 件"));
        }

        return findings;
    }

    /**
     * 判定库存时间戳是否已停滞超过阈值。
     *
     * <p>恰好等于阈值算作停滞——否则阈值两侧的语义不一致，是典型的边界缺陷。</p>
     */
    private boolean isStalled(ReconcileSnapshot snapshot, Instant now, long stallThresholdSeconds) {
        Duration elapsed = Duration.between(snapshot.getStockUpdatedAt(), now);
        return elapsed.getSeconds() >= stallThresholdSeconds;
    }
}
