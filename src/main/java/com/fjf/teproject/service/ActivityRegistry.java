package com.fjf.teproject.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「哪些 seckillId 是合法活动」的内存集合，供请求路径做快速校验。
 *
 * <p>存在的理由：Lua 脚本对「活动不存在」与「Redis 未预热」返回同一个 -3。
 * 若不区分，客户端传错 seckillId 会收到 503，把自己的输入错误伪装成
 * 服务端故障。本集合是二者的唯一区分依据——不在集合中即 404，
 * 在集合中但 Redis 无键即 503。</p>
 *
 * <p>活动来自 data.sql 种子数据，量小且静态，因此可整体常驻内存。
 * 使用并发集合是因为多个实例/线程的预热可能同时发生；注册是幂等的。</p>
 */
@Component
public class ActivityRegistry {

    private final Set<Long> activeIds = ConcurrentHashMap.newKeySet();

    /**
     * @return 该 id 是否为已注册的合法活动；{@code null} 永远返回 {@code false}
     */
    public boolean exists(Long seckillId) {
        return seckillId != null && activeIds.contains(seckillId);
    }

    public void register(Collection<Long> seckillIds) {
        activeIds.addAll(seckillIds);
    }

    public int size() {
        return activeIds.size();
    }

    public void clear() {
        activeIds.clear();
    }
}
