package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import com.fjf.teproject.messaging.SeckillMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 秒杀用例编排：校验活动 → 原子扣减 → 投递落库消息。
 *
 * <p>时序上，HTTP 200 在消息投递之前就已经确定。Redis 扣减完成即为业务意义上
 * 的成功，落库是<b>补偿性</b>的而非<b>确认性</b>的：它只能追赶，不能否决一个
 * 已经对用户生效的成功。因此消息投递失败<b>不会</b>让本次请求变成 500——
 * 那会向用户报告一个与事实相反的结果（库存真的扣了），并诱使客户端重试。
 * 投递失败时做有限次同步重试，仍失败则记 error 日志，缺口由 A1 对账
 * （Redis vs DB）发现并告警（详见 spec §8.2 失败矩阵、§8.3 双写缺口）。</p>
 *
 * <p>不使用分布式锁：并发不变量完全由 Redis 中的 Lua 脚本保证。</p>
 */
@Service
public class SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillService.class);

    /**
     * 投递落库消息的最大尝试次数（含首次），即最多重试 2 次。
     *
     * <p>为什么是「有限」而不是无限重试：此刻用户已经拿到 200 并断开连接，
     * 在请求线程里无限重试只会耗尽 Web 容器线程，把局部故障放大成整体不可用。
     * 放弃后留下的 Redis 与 DB 差值正是 A1 对账要发现的对象。</p>
     */
    private static final int MAX_PUBLISH_ATTEMPTS = 3;

    private final SeckillStockRepository stockRepository;
    private final SeckillMessageProducer messageProducer;
    private final ActivityRegistry activityRegistry;

    public SeckillService(SeckillStockRepository stockRepository,
                          SeckillMessageProducer messageProducer,
                          ActivityRegistry activityRegistry) {
        this.stockRepository = stockRepository;
        this.messageProducer = messageProducer;
        this.activityRegistry = activityRegistry;
    }

    /**
     * 执行一次抢购。
     *
     * <p>成功时不因落库消息投递失败而改变结果：Redis 扣减成功即返回成功。</p>
     *
     * @throws SeckillException 活动不存在时抛出 {@code SECKILL_NOT_FOUND}
     */
    public DeductionOutcome purchase(long seckillId, String userId) {
        if (!activityRegistry.exists(seckillId)) {
            throw new SeckillException(SeckillErrorCode.SECKILL_NOT_FOUND);
        }

        DeductionOutcome outcome = stockRepository.tryDeduct(seckillId, userId);
        if (outcome.isSuccess()) {
            publishWithoutFailingThePurchase(seckillId, userId, outcome.getRemainingStock());
        }
        return outcome;
    }

    /**
     * 查询实时库存，读 Redis 而非数据库。
     *
     * <p>读数据库会让用户看到「还有库存」却抢不到——异步落库必然滞后。</p>
     *
     * @throws SeckillException 活动不存在时为 {@code SECKILL_NOT_FOUND}；
     *                          活动合法但 Redis 无键时为 {@code NOT_READY}
     */
    public int getRemainingStock(long seckillId) {
        if (!activityRegistry.exists(seckillId)) {
            throw new SeckillException(SeckillErrorCode.SECKILL_NOT_FOUND);
        }

        int remaining = stockRepository.getRemainingStock(seckillId);
        if (remaining < 0) {
            throw new SeckillException(SeckillErrorCode.NOT_READY);
        }
        return remaining;
    }

    /**
     * 投递落库消息，吞掉最终失败并记录日志——调用方的抢购结果不受影响。
     *
     * <p>重复投递是安全的：{@code uk_seckill_user} 唯一索引 + 消费者把
     * {@code DuplicateKeyException} 视为成功，因此重试不会造成二次扣减。</p>
     */
    private void publishWithoutFailingThePurchase(long seckillId, String userId, int remainingStock) {
        RuntimeException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_PUBLISH_ATTEMPTS; attempt++) {
            try {
                messageProducer.publish(seckillId, userId);
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("秒杀落库消息投递失败，第 {}/{} 次尝试：seckillId={}, userId={}",
                        attempt, MAX_PUBLISH_ATTEMPTS, seckillId, userId, exception);
            }
        }

        // 走到这里说明 Redis 已扣减、用户已收到成功，但 MySQL 不会有对应流水。
        // 这是 spec §8.3 明确接受的缺口，交由 A1 对账发现，本次请求照常返回成功。
        log.error("秒杀落库消息最终投递失败：Redis 已扣减一件库存且用户已收到成功响应，"
                        + "MySQL 将缺少对应流水。seckillId={}, userId={}, 扣减后剩余库存={}。"
                        + "该双写缺口由 A1 对账（Redis vs DB）发现并告警。",
                seckillId, userId, remainingStock, lastFailure);
    }
}
