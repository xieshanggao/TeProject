package com.fjf.teproject.reconcile;

/**
 * 一条对账告警。
 *
 * <p>对账只产生告警、不自动修数：用户已经收到 200，任何一侧的自动改动
 * 都可能制造新的不一致，人工介入是刻意的选择。</p>
 */
public final class ReconcileFinding {

    public enum Kind {
        /** Redis 已扣减数与 DB 已落库数不一致，且库存时间戳已停滞。 */
        A1_REDIS_DB_MISMATCH,
        /** 流水条数与库存扣减数分叉；正常情况下不可能发生。 */
        A2_RECORD_STOCK_MISMATCH
    }

    private final long seckillId;
    private final Kind kind;
    private final String detail;

    public ReconcileFinding(long seckillId, Kind kind, String detail) {
        this.seckillId = seckillId;
        this.kind = kind;
        this.detail = detail;
    }

    public long getSeckillId() {
        return seckillId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "[" + kind + "] seckillId=" + seckillId + " " + detail;
    }
}
