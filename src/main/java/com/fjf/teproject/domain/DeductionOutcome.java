package com.fjf.teproject.domain;

import java.util.Objects;

/**
 * Redis 扣减尝试的结果：要么成功并携带剩余库存，要么失败并携带错误码。
 *
 * <p>不可变值对象，可安全地在多个线程间共享。</p>
 */
public final class DeductionOutcome {

    private final SeckillErrorCode errorCode;
    private final int remainingStock;

    private DeductionOutcome(SeckillErrorCode errorCode, int remainingStock) {
        this.errorCode = errorCode;
        this.remainingStock = remainingStock;
    }

    public static DeductionOutcome success(int remainingStock) {
        return new DeductionOutcome(SeckillErrorCode.SUCCESS, remainingStock);
    }

    public static DeductionOutcome failure(SeckillErrorCode errorCode) {
        return new DeductionOutcome(errorCode, 0);
    }

    public boolean isSuccess() {
        return errorCode == SeckillErrorCode.SUCCESS;
    }

    public SeckillErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * @return 成功时的剩余库存；失败时返回 {@code 0}，此时该值无业务含义
     */
    public int getRemainingStock() {
        return remainingStock;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DeductionOutcome)) {
            return false;
        }
        DeductionOutcome that = (DeductionOutcome) other;
        return remainingStock == that.remainingStock && errorCode == that.errorCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(errorCode, remainingStock);
    }

    @Override
    public String toString() {
        return "DeductionOutcome{" + errorCode + ", remainingStock=" + remainingStock + '}';
    }
}
