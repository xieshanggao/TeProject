package com.fjf.teproject.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对账任务的配置项，绑定前缀 {@code seckill.reconcile}。
 *
 * <p>阈值不硬编码：不同部署环境下「异步延迟多久算正常」的答案不同，
 * 写死会让告警要么永远不响、要么一直响。</p>
 */
@ConfigurationProperties(prefix = "seckill.reconcile")
public class ReconcileProperties {

    /** 对账任务执行间隔，单位秒。 */
    private long intervalSeconds = 10L;

    /** 库存时间戳超过该秒数未推进，才判定为「卡住」而非「追赶中」。 */
    private long stallThresholdSeconds = 60L;

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public long getStallThresholdSeconds() {
        return stallThresholdSeconds;
    }

    public void setStallThresholdSeconds(long stallThresholdSeconds) {
        this.stallThresholdSeconds = stallThresholdSeconds;
    }
}
