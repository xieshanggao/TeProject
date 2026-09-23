package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaResultMapperTest {

    @Test
    void nonNegativeResultMeansSuccessAndCarriesRemainingStock() {
        DeductionOutcome outcome = LuaResultMapper.from(99);

        assertTrue(outcome.isSuccess());
        assertEquals(99, outcome.getRemainingStock());
    }

    @Test
    void zeroIsSuccessBecauseItMeansTheLastItemWasJustSold() {
        DeductionOutcome outcome = LuaResultMapper.from(0);

        assertTrue(outcome.isSuccess());
        assertEquals(0, outcome.getRemainingStock());
    }

    @Test
    void minusOneMeansSoldOut() {
        DeductionOutcome outcome = LuaResultMapper.from(-1);

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT, outcome.getErrorCode());
    }

    @Test
    void minusTwoMeansAlreadyPurchased() {
        DeductionOutcome outcome = LuaResultMapper.from(-2);

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.ALREADY_PURCHASED, outcome.getErrorCode());
    }

    @Test
    void minusThreeMeansNotWarmedUp() {
        DeductionOutcome outcome = LuaResultMapper.from(-3);

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.NOT_READY, outcome.getErrorCode());
    }

    @Test
    void unknownNegativeResultFailsLoudlyInsteadOfBeingSwallowed() {
        // 未识别的返回值意味着脚本与映射函数已经不同步。
        // 静默当作售罄处理会把一个代码缺陷伪装成正常的业务拒绝。
        assertThrows(IllegalStateException.class, () -> LuaResultMapper.from(-99));
    }
}
