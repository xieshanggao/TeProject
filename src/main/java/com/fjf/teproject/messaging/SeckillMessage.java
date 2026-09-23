package com.fjf.teproject.messaging;

import java.io.Serializable;

/**
 * 抢购成功的落库消息。
 *
 * <p>只携带定位一次购买所需的最小信息。需要无参构造是因为消息经过
 * JSON 序列化后由 Jackson 反序列化。</p>
 */
public class SeckillMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private long seckillId;
    private String userId;

    public SeckillMessage() {
    }

    public SeckillMessage(long seckillId, String userId) {
        this.seckillId = seckillId;
        this.userId = userId;
    }

    public long getSeckillId() {
        return seckillId;
    }

    public void setSeckillId(long seckillId) {
        this.seckillId = seckillId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @Override
    public String toString() {
        return "SeckillMessage{seckillId=" + seckillId + ", userId='" + userId + "'}";
    }
}
