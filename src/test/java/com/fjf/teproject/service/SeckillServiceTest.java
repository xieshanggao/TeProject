package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import com.fjf.teproject.messaging.SeckillMessageProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务编排逻辑的单元测试：全部依赖用替身，不需要 Docker。
 */
class SeckillServiceTest {

    private static final long SECKILL_ID = 1L;

    private SeckillStockRepository stockRepository;
    private SeckillMessageProducer producer;
    private ActivityRegistry registry;
    private SeckillService service;

    @BeforeEach
    void setUp() {
        stockRepository = mock(SeckillStockRepository.class);
        producer = mock(SeckillMessageProducer.class);
        registry = new ActivityRegistry();
        service = new SeckillService(stockRepository, producer, registry);
    }

    @Test
    void unknownActivityIsRejectedWithoutTouchingRedis() {
        // 活动不在注册表中即 404，不应进入 Lua——否则会拿到 -3 而误报 503，
        // 把客户端的输入错误伪装成服务端故障。
        SeckillException exception = assertThrows(SeckillException.class,
                () -> service.purchase(SECKILL_ID, "u1"));

        assertEquals(SeckillErrorCode.SECKILL_NOT_FOUND, exception.getErrorCode());
        verify(stockRepository, never()).tryDeduct(anyLong(), anyString());
    }

    @Test
    void successfulPurchasePublishesMessage() {
        registry.register(Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.success(99));

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertTrue(outcome.isSuccess());
        assertEquals(99, outcome.getRemainingStock());
        verify(producer).publish(SECKILL_ID, "u1");
    }

    @Test
    void failedDeductionDoesNotPublishMessage() {
        // 失败绝不能投递消息——那会让 DB 记录一次并不存在的购买。
        // 售罄作为失败分支的代表：已购 / 未预热走的是同一条「不投递」路径，
        // 它们的错误码差异由 SeckillControllerIT 的 HTTP 契约用例覆盖。
        registry.register(Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.failure(SeckillErrorCode.STOCK_SOLD_OUT));

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT, outcome.getErrorCode());
        verify(producer, never()).publish(anyLong(), anyString());
    }

    @Test
    void publishFailureStillReturnsSuccessfulOutcome() {
        // 钉住 spec §8.3 的取舍：Redis 扣减完成才是业务意义上的成功，
        // 落库是补偿性的。投递失败（即使每次都失败）不能冒泡成 500——
        // 用户已经扣了库存、也已收到成功，静默丢失的缺口交给 A1 对账发现。
        registry.register(Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.success(99));
        doThrow(new IllegalStateException("MQ 不可用"))
                .when(producer).publish(anyLong(), anyString());

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertTrue(outcome.isSuccess(), "投递失败时 purchase 必须仍然返回成功而非抛异常");
        assertEquals(99, outcome.getRemainingStock());
        // 有限次同步重试：恰好 3 次（首次 + 2 次重试）后放弃并记 error 日志。
        verify(producer, times(3)).publish(SECKILL_ID, "u1");
    }

    @Test
    void stockQueryOnUnknownActivityThrowsNotFound() {
        SeckillException exception = assertThrows(SeckillException.class,
                () -> service.getRemainingStock(SECKILL_ID));

        assertEquals(SeckillErrorCode.SECKILL_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void stockQueryOnKnownActivityReadsRedis() {
        registry.register(Collections.singletonList(SECKILL_ID));
        when(stockRepository.getRemainingStock(SECKILL_ID)).thenReturn(42);

        assertEquals(42, service.getRemainingStock(SECKILL_ID));
    }

    @Test
    void stockQueryOnRegisteredButUnwarmedActivityIsNotReady() {
        // 在注册表中（活动合法）但 Redis 无键（预热失败）→ 503 而非 404。
        registry.register(Collections.singletonList(SECKILL_ID));
        when(stockRepository.getRemainingStock(SECKILL_ID)).thenReturn(-1);

        SeckillException exception = assertThrows(SeckillException.class,
                () -> service.getRemainingStock(SECKILL_ID));

        assertEquals(SeckillErrorCode.NOT_READY, exception.getErrorCode());
    }
}
