package com.fjf.teproject.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeckillErrorCodeTest {

    @Test
    void everyCodeMapsToTheHttpStatusInTheContract() {
        // 断言与本文件中的契约表一一对应；改契约必须先改这里的测试。
        assertEquals(200, SeckillErrorCode.SUCCESS.getHttpStatus());
        assertEquals(409, SeckillErrorCode.STOCK_SOLD_OUT.getHttpStatus());
        assertEquals(409, SeckillErrorCode.ALREADY_PURCHASED.getHttpStatus());
        assertEquals(404, SeckillErrorCode.SECKILL_NOT_FOUND.getHttpStatus());
        assertEquals(503, SeckillErrorCode.NOT_READY.getHttpStatus());
        assertEquals(400, SeckillErrorCode.INVALID_REQUEST.getHttpStatus());
    }

    @Test
    void soldOutAndAlreadyPurchasedShareAStatusButMustNotShareACode() {
        // 两者都是 409，客户端靠 code 区分，因此 code 必须不同。
        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT.getHttpStatus(),
                SeckillErrorCode.ALREADY_PURCHASED.getHttpStatus());
        assertTrue(!SeckillErrorCode.STOCK_SOLD_OUT.getCode()
                .equals(SeckillErrorCode.ALREADY_PURCHASED.getCode()));
    }

    @Test
    void allCodesAreUnique() {
        List<String> codes = Arrays.stream(SeckillErrorCode.values())
                .map(SeckillErrorCode::getCode)
                .collect(Collectors.toList());
        assertEquals(codes.size(), codes.stream().distinct().count());
    }
}
