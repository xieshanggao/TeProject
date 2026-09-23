package com.fjf.teproject.reconcile;

import com.fjf.teproject.domain.SeckillActivity;
import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 周期比对 Redis 权威库存与数据库副本，发现不一致时告警。
 *
 * <p><b>只告警，不自动修数。</b>用户已经收到过 200，任何一侧的自动改动
 * 都可能制造新的不一致。对账的职责是让人知道出了问题，而不是猜测
 * 应该相信哪一侧。</p>
 *
 * <p>判定规则本身在 {@link ReconcileEvaluator} 中实现——那是纯函数，
 * 可以在无 Docker 环境下被完整测试。本类只负责把两侧的真实状态读出来
 * 拼成快照。</p>
 */
@Component
public class SeckillReconciler {

    private static final Logger log = LoggerFactory.getLogger(SeckillReconciler.class);

    private final SeckillActivityRepository activityRepository;
    private final SeckillRecordRepository recordRepository;
    private final SeckillStockRepository stockRepository;
    private final ReconcileEvaluator evaluator;
    private final ReconcileProperties properties;

    public SeckillReconciler(SeckillActivityRepository activityRepository,
                             SeckillRecordRepository recordRepository,
                             SeckillStockRepository stockRepository,
                             ReconcileEvaluator evaluator,
                             ReconcileProperties properties) {
        this.activityRepository = activityRepository;
        this.recordRepository = recordRepository;
        this.stockRepository = stockRepository;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    /**
     * 定时入口。间隔配置的单位是<b>秒</b>，这里在表达式里乘 1000 换算成
     * {@code fixedDelayString} 要求的毫秒。
     *
     * <p>用 SpEL 而非字符串拼接（{@code "${...}000"}）：后者在值写成
     * {@code 0.5} 或带空格时会静默算出错误间隔，读代码的人也容易误以为是
     * 10 秒的字面量。</p>
     */
    @Scheduled(fixedDelayString = "#{${seckill.reconcile.interval-seconds:10} * 1000}")
    public void scheduledReconcile() {
        try {
            List<ReconcileFinding> findings = reconcileAll(Instant.now());
            for (ReconcileFinding finding : findings) {
                // 真实系统这里应接入监控告警通道；教学 Demo 以 error 日志代替。
                log.error("对账发现不一致：{}", finding);
            }
        } catch (Exception exception) {
            log.error("对账任务执行失败", exception);
        }
    }

    /**
     * 对所有活动执行一次对账。
     *
     * <p>只读操作，不修改任何数据——这是本类最重要的设计约束。</p>
     */
    public List<ReconcileFinding> reconcileAll(Instant now) {
        List<ReconcileFinding> findings = new ArrayList<>();
        long stallThreshold = properties.getStallThresholdSeconds();

        for (SeckillActivity activity : activityRepository.findAll()) {
            long seckillId = activity.getId();
            int redisStock = stockRepository.getRemainingStock(seckillId);
            if (redisStock < 0) {
                // Redis 中没有该活动的库存键——预热失败或 Redis 被清空。
                // 这不是「不一致」，而是服务未就绪，抢购接口已返回 503。
                continue;
            }

            ReconcileSnapshot snapshot = ReconcileSnapshot.of(
                    seckillId,
                    activity.getInitialStock(),
                    redisStock,
                    activity.getRemainingStock(),
                    recordRepository.countByActivity(seckillId),
                    toInstant(activity.getStockUpdatedAt()));

            findings.addAll(evaluator.evaluate(snapshot, now, stallThreshold));
        }

        return findings;
    }

    private Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }
}
