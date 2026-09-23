package com.fjf.teproject.reconcile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final long STALL_THRESHOLD_SECONDS = 60L;

    private final ReconcileEvaluator evaluator = new ReconcileEvaluator();

    private ReconcileSnapshot snapshot(int initialStock, int redisStock,
                                       int dbRemainingStock, int recordCount,
                                       Instant stockUpdatedAt) {
        return ReconcileSnapshot.of(1L, initialStock, redisStock,
                dbRemainingStock, recordCount, stockUpdatedAt);
    }

    @Test
    void consistentStateProducesNoFinding() {
        ReconcileSnapshot snapshot = snapshot(100, 90, 90, 10, NOW);

        assertTrue(evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).isEmpty());
    }

    @Test
    void catchingUpIsNotAlertedBecauseStockTimestampIsFresh() {
        // Redis 已扣 10 件，DB 只落了 8 件，但库存 5 秒前刚被推进过。
        // 这是异步固有的延迟，不是故障——告警会变成必然误报。
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 8,
                NOW.minus(5, ChronoUnit.SECONDS));

        assertTrue(evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).isEmpty());
    }

    @Test
    void stalledMismatchIsAlerted() {
        // 同样的数值差异，但库存 5 分钟没被推进过——消费者卡住了。
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 8,
                NOW.minus(300, ChronoUnit.SECONDS));

        List<ReconcileFinding> findings =
                evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS);

        assertEquals(1, findings.size());
        assertEquals(ReconcileFinding.Kind.A1_REDIS_DB_MISMATCH, findings.get(0).getKind());
        assertEquals(1L, findings.get(0).getSeckillId());
    }

    @Test
    void exactlyAtTheThresholdCountsAsStalled() {
        // 边界值：恰好等于阈值应判定为停滞，否则阈值语义在两侧不一致。
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 8,
                NOW.minus(STALL_THRESHOLD_SECONDS, ChronoUnit.SECONDS));

        assertEquals(1, evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).size());
    }

    @Test
    void recordCountOutOfSyncWithStockIsAlwaysAlerted() {
        // A2 金丝雀：流水数与库存扣减数分叉。两条语句在同一事务内，
        // 正常情况下不可能发生，一旦出现即代码缺陷或人工改数。
        ReconcileSnapshot snapshot = snapshot(100, 90, 90, 9, NOW);

        List<ReconcileFinding> findings =
                evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS);

        assertEquals(1, findings.size());
        assertEquals(ReconcileFinding.Kind.A2_RECORD_STOCK_MISMATCH, findings.get(0).getKind());
    }

    @Test
    void bothProblemsCanBeReportedTogether() {
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 5,
                NOW.minus(300, ChronoUnit.SECONDS));

        List<ReconcileFinding> findings =
                evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS);

        assertEquals(2, findings.size());
    }

    @Test
    void redisAheadOfDbWithFreshTimestampStaysSilent() {
        // 消费者正常追赶的最常见形态：Redis 在前，DB 在后，时间戳很新。
        ReconcileSnapshot snapshot = snapshot(100, 0, 50, 50,
                NOW.minus(1, ChronoUnit.SECONDS));

        assertTrue(evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).isEmpty());
    }
}
