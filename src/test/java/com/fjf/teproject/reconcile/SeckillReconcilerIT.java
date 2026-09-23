package com.fjf.teproject.reconcile;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillService;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账任务在真实 Redis / MySQL / RabbitMQ 上的验证。
 *
 * <p>对账唯一真正吃劲的地方是三条性质，本类逐条钉死：</p>
 * <ol>
 *   <li><b>不误报</b>：一次抢购走完「Redis 扣减 + 消费者落库」全流程后必须静默。
 *       一个天天误报的对账等于没有对账——运维会迅速学会忽略它。</li>
 *   <li><b>能报</b>：人为制造的不一致必须被抓到，且告警里能看出是哪个 seckillId。</li>
 *   <li><b>只告警不修数</b>：对账前后两侧数据逐字节相同。用户已经收到过 200，
 *       任何自动改动都可能制造新的不一致。</li>
 * </ol>
 *
 * <p>为何不依赖后台定时器：基类把 {@code seckill.reconcile.interval-seconds} 设为
 * 3600（1 小时），定时器不会在用例中途插进来；这里一律手动调用
 * {@link SeckillReconciler#reconcileAll(Instant)}，结果才是确定的。</p>
 */
@Tag("integration")
class SeckillReconcilerIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    /** 把库存时间戳推到该秒数之前，必然超过默认的 60 秒停滞阈值。 */
    private static final int STALLED_SECONDS_AGO = 600;

    @Autowired
    private SeckillReconciler reconciler;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 把全部活动恢复到「两侧一致」的干净状态：清 Redis、删流水、重置 DB 库存、
     * 重新预热。清理全部活动而非只清活动 1，是为了让 {@code reconcileAll} 的
     * 全量结果可断言为空——别的测试类可能在同一 JVM 里改过活动 2/3。
     */
    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record");
        for (Long activityId : activityRepository.findAllIds()) {
            activityRepository.resetToInitial(activityId);
        }
        warmUpRunner.warmUpAll();
    }

    private List<ReconcileFinding> reconcileCurrentActivity() {
        return reconciler.reconcileAll(Instant.now()).stream()
                .filter(finding -> finding.getSeckillId() == SECKILL_ID)
                .collect(Collectors.toList());
    }

    /**
     * 用数据库自身的 {@code NOW()} 做减法，而不是在 Java 侧算好绝对时刻再写下去：
     * 应用 JVM 与 MySQL 容器若不在同一时区，驱动的一次时区换算会让「10 分钟前」
     * 反向偏成「未来」，使停滞判定失效。
     */
    private void stallStockTimestamp(long seckillId) {
        jdbcTemplate.update(
                "UPDATE seckill_activity SET stock_updated_at = NOW() - INTERVAL "
                        + STALLED_SECONDS_AGO + " SECOND WHERE id = ?",
                seckillId);
    }

    private int dbRemainingStock(long seckillId) {
        return activityRepository.findById(seckillId).orElseThrow().getRemainingStock();
    }

    private LocalDateTime dbStockUpdatedAt(long seckillId) {
        return activityRepository.findById(seckillId).orElseThrow().getStockUpdatedAt();
    }

    @Test
    void fullPurchaseFlowProducesNoFinding() {
        // 真实全流程：HTTP 之前的 Redis 原子扣减 + MQ 消费者异步落库，
        // 而不是手工把两侧设置成相等。走完之后必须完全静默。
        seckillService.purchase(SECKILL_ID, "u1");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        int redisBefore = stockRepository.getRemainingStock(SECKILL_ID);
        int dbStockBefore = dbRemainingStock(SECKILL_ID);

        List<ReconcileFinding> findings = reconciler.reconcileAll(Instant.now());

        assertTrue(findings.isEmpty(),
                "一次成功抢购走完 Redis 扣减 + 消费者落库后不应有任何告警，实际 " + findings);

        // 顺带钉住只读：这一次对账不得改动任何一侧。
        assertEquals(redisBefore, stockRepository.getRemainingStock(SECKILL_ID));
        assertEquals(dbStockBefore, dbRemainingStock(SECKILL_ID));
    }

    @Test
    void redisAheadOfDatabaseWhileTimestampIsFreshIsNotAlerted() {
        // Redis 已扣 1 件、DB 尚未落库，但库存时间戳刚被 setUp 刷新过——
        // 这是异步固有的追赶窗口，不是故障。没有时间戳条件，这条会被误报。
        stockRepository.forceSetStock(SECKILL_ID, 99);

        assertTrue(reconciler.reconcileAll(Instant.now()).isEmpty(),
                "消费者仍在正常追赶（时间戳新鲜）时对账必须静默");
    }

    @Test
    void stalledDivergenceIsReportedWithItsSeckillId() {
        // Redis 扣了 5 件，DB 一条流水都没有，且库存时间戳已停滞 10 分钟。
        stockRepository.forceSetStock(SECKILL_ID, 95);
        stallStockTimestamp(SECKILL_ID);

        List<ReconcileFinding> findings = reconciler.reconcileAll(Instant.now());

        assertEquals(1, findings.size(), "应恰好报告一条告警，实际 " + findings);
        assertEquals(SECKILL_ID, findings.get(0).getSeckillId(),
                "告警必须能定位到具体的 seckillId");
        assertEquals(ReconcileFinding.Kind.A1_REDIS_DB_MISMATCH, findings.get(0).getKind());
    }

    @Test
    void recordCountOutOfSyncWithStockIsReportedAsA2() {
        // 人工制造分叉：写一条流水（同一事务里把 DB 库存减 1），再手工多减 1 件。
        // 两条语句本在同一事务内，正常情况下恒等，分叉即金丝雀报警。
        stockRepository.forceSetStock(SECKILL_ID, 100);
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        jdbcTemplate.update(
                "UPDATE seckill_activity SET remaining_stock = remaining_stock - 1 WHERE id = ?",
                SECKILL_ID);

        List<ReconcileFinding> findings = reconcileCurrentActivity();

        assertTrue(findings.stream()
                        .anyMatch(f -> f.getKind() == ReconcileFinding.Kind.A2_RECORD_STOCK_MISMATCH
                                && f.getSeckillId() == SECKILL_ID),
                "应报告 A2 金丝雀并带上 seckillId，实际 " + findings);
    }

    @Test
    void reconcilerNeverModifiesEitherSide() {
        // 先制造一个真实可修的不一致（Redis 与 DB 不同，且时间戳停滞），
        // 确认对账确实看到了它，却仍然一个字都不改。
        stockRepository.forceSetStock(SECKILL_ID, 95);
        stallStockTimestamp(SECKILL_ID);

        int redisBefore = stockRepository.getRemainingStock(SECKILL_ID);
        int dbStockBefore = dbRemainingStock(SECKILL_ID);
        int recordsBefore = recordRepository.countByActivity(SECKILL_ID);
        LocalDateTime updatedAtBefore = dbStockUpdatedAt(SECKILL_ID);

        List<ReconcileFinding> findings = reconciler.reconcileAll(Instant.now());
        assertTrue(!findings.isEmpty(), "前置条件：这次对账必须真的发现了不一致，实际 " + findings);

        assertEquals(redisBefore, stockRepository.getRemainingStock(SECKILL_ID),
                "对账不得改动 Redis 权威库存");
        assertEquals(dbStockBefore, dbRemainingStock(SECKILL_ID),
                "对账不得改动 DB 的 remaining_stock");
        assertEquals(recordsBefore, recordRepository.countByActivity(SECKILL_ID),
                "对账不得写入或删除流水");
        assertEquals(updatedAtBefore, dbStockUpdatedAt(SECKILL_ID),
                "对账不得触碰 stock_updated_at");
    }
}
