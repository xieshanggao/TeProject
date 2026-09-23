package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;

/**
 * 把 Lua 脚本的原始返回值翻译成 {@link DeductionOutcome}。
 *
 * <p>Lua 返回值的含义（与 src/main/resources/lua/seckill_deduct.lua 一一对应）：</p>
 * <pre>
 *   &gt;= 0  成功，值为扣减后的剩余库存
 *   -1     库存售罄
 *   -2     该用户已购买过
 *   -3     活动未预热（Redis 中缺少库存键）
 * </pre>
 *
 * <p>这是纯函数，不依赖 Redis，因此可以在默认测试集中被完整覆盖。
 * Lua 脚本实际跑在 Redis 中的行为由集成测试验证。</p>
 */
public final class LuaResultMapper {

    private LuaResultMapper() {
    }

    public static DeductionOutcome from(long rawResult) {
        if (rawResult >= 0) {
            return DeductionOutcome.success((int) rawResult);
        }
        switch ((int) rawResult) {
            case -1:
                return DeductionOutcome.failure(SeckillErrorCode.STOCK_SOLD_OUT);
            case -2:
                return DeductionOutcome.failure(SeckillErrorCode.ALREADY_PURCHASED);
            case -3:
                return DeductionOutcome.failure(SeckillErrorCode.NOT_READY);
            default:
                throw new IllegalStateException(
                        "Lua 脚本返回了未定义的值 " + rawResult + "，脚本与映射函数已不同步");
        }
    }
}
