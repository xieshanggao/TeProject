package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collections;

/**
 * Redis 侧库存仓储：权威库存与限购集合的唯一入口。
 *
 * <p>并发不变量由 seckill_deduct.lua 保证。选择 Lua 而非分布式锁的原因是：
 * Redis 执行脚本是单线程的，判断与扣减之间不存在其它请求插入的窗口，
 * 而锁存在获取失败、超时、续期等一整套额外的失败模式。</p>
 *
 * <p>键名使用 {seckillId} 作为 hash tag：脚本同时操作库存键与限购集合键，
 * Redis Cluster 下如果二者落在不同 slot，EVAL 会直接报错。hash tag 强制
 * 它们落在同一 slot。</p>
 */
@Repository
public class SeckillStockRepository {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:{";
    private static final String BOUGHT_KEY_PREFIX = "seckill:bought:{";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> seckillDeductScript;

    public SeckillStockRepository(StringRedisTemplate redisTemplate,
                                  DefaultRedisScript<Long> seckillDeductScript) {
        this.redisTemplate = redisTemplate;
        this.seckillDeductScript = seckillDeductScript;
    }

    /**
     * 尝试为指定用户扣减一件库存。
     *
     * @return 成功时携带扣减后的剩余库存；失败时携带 -1/-2/-3 对应的错误码
     */
    public DeductionOutcome tryDeduct(long seckillId, String userId) {
        Long rawResult = redisTemplate.execute(
                seckillDeductScript,
                Arrays.asList(stockKey(seckillId), boughtKey(seckillId)),
                userId);
        if (rawResult == null) {
            throw new IllegalStateException("Lua 脚本未返回结果，seckillId=" + seckillId);
        }
        return LuaResultMapper.from(rawResult);
    }

    /**
     * 读取实时库存（权威值）。
     *
     * @return 剩余库存；活动未预热时返回 {@code -1}
     */
    public int getRemainingStock(long seckillId) {
        String value = redisTemplate.opsForValue().get(stockKey(seckillId));
        return value == null ? -1 : Integer.parseInt(value);
    }

    /**
     * 预热库存，仅在键不存在时写入。
     *
     * <p>使用 SETNX 而非 SET：应用重启时 Redis 可能还保留着已经扣减过的
     * 实时库存，无条件覆盖会让已售出的库存凭空复活，直接违反不超卖。</p>
     *
     * @return 本次调用是否真正写入了库存
     */
    public boolean warmUp(long seckillId, int initialStock) {
        Boolean written = redisTemplate.opsForValue()
                .setIfAbsent(stockKey(seckillId), String.valueOf(initialStock));
        return Boolean.TRUE.equals(written);
    }

    public long countPurchasers(long seckillId) {
        Long size = redisTemplate.opsForSet().size(boughtKey(seckillId));
        return size == null ? 0L : size;
    }

    private String stockKey(long seckillId) {
        return STOCK_KEY_PREFIX + seckillId + "}";
    }

    private String boughtKey(long seckillId) {
        return BOUGHT_KEY_PREFIX + seckillId + "}";
    }

    /** 仅供测试使用，清空某个活动的全部 Redis 状态。 */
    public void deleteAll(long seckillId) {
        redisTemplate.delete(Arrays.asList(stockKey(seckillId), boughtKey(seckillId)));
    }

    /** 供测试注入初始库存而不走 SETNX 语义。 */
    public void forceSetStock(long seckillId, int stock) {
        redisTemplate.opsForValue().set(stockKey(seckillId), String.valueOf(stock));
    }

    public java.util.Set<String> getPurchasers(long seckillId) {
        java.util.Set<String> members = redisTemplate.opsForSet().members(boughtKey(seckillId));
        return members == null ? Collections.emptySet() : members;
    }
}
