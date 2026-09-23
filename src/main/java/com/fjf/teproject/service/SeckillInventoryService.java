package com.fjf.teproject.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SeckillInventoryService {

    private final AtomicInteger remainingStock;

    public SeckillInventoryService() {
        this(100);
    }

    SeckillInventoryService(int initialStock) {
        this.remainingStock = new AtomicInteger(initialStock);
    }

    /**
     * 原子扣减一件库存。
     *
     * <p>不变量：成功抢购次数不超过初始库存，剩余库存永远不小于 {@code 0}。
     * CAS 失败说明有其他请求已修改库存，此时重新读取后再尝试，避免读写分离造成超卖。</p>
     *
     * @return 扣减成功后的库存快照；库存售罄时返回 {@code -1}
     */
    public int purchase() {
        while (true) {
            int currentStock = remainingStock.get();
            if (currentStock <= 0) {
                return -1;
            }
            if (remainingStock.compareAndSet(currentStock, currentStock - 1)) {
                return currentStock - 1;
            }
        }
    }

    /**
     * 返回当前库存快照。其他并发请求可在该值返回后继续扣减库存。
     */
    public int getRemainingStock() {
        return remainingStock.get();
    }
}
