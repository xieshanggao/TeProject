package com.fjf.teproject.service;

import com.fjf.teproject.domain.SeckillActivity;
import com.fjf.teproject.repository.SeckillActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时预热 Redis 库存，并把合法活动 ID 登记到内存注册表。
 *
 * <p>两个动作的分工是刻意的，也是多实例部署的关键：</p>
 * <ul>
 *   <li><b>每个实例都</b>把活动 ID 载入内存 {@link ActivityRegistry}——
 *       它用于在请求路径上区分 404（活动不存在）与 503（未预热）。</li>
 *   <li><b>只有第一个实例</b>能用 SETNX 真正写入 Redis 库存。这保证应用
 *       重启时不会用数据库中的旧库存覆盖 Redis 里已经扣减过的实时库存——
 *       那种覆盖会让已售出的库存凭空复活，直接违反不超卖。</li>
 * </ul>
 *
 * <p>预热失败的实例不会阻止应用启动：抢购请求会因为缺少库存键而返回 503，
 * 这比让整个应用无法启动更符合「可用性让位于正确性、但不要整体崩掉」的取舍。</p>
 */
@Component
public class SeckillWarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeckillWarmUpRunner.class);

    private final SeckillActivityRepository activityRepository;
    private final SeckillStockRepository stockRepository;
    private final ActivityRegistry activityRegistry;

    public SeckillWarmUpRunner(SeckillActivityRepository activityRepository,
                               SeckillStockRepository stockRepository,
                               ActivityRegistry activityRegistry) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.activityRegistry = activityRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int warmed = warmUpAll();
            log.info("秒杀活动预热完成：登记 {} 个活动，本次实际写入 Redis {} 个",
                    activityRegistry.size(), warmed);
        } catch (Exception exception) {
            // 预热失败不阻止启动：请求会因缺少库存键返回 503，
            // 这比整个应用起不来更容易定位，也不会影响其它功能。
            log.error("秒杀活动预热失败，抢购接口将返回 503", exception);
        }
    }

    /**
     * 登记全部活动并尝试预热库存。
     *
     * @return 本次调用中真正写入 Redis 的活动数量
     */
    public int warmUpAll() {
        List<Long> activityIds = activityRepository.findAllIds();
        activityRegistry.register(activityIds);

        int written = 0;
        for (Long seckillId : activityIds) {
            SeckillActivity activity = activityRepository.findById(seckillId).orElse(null);
            if (activity == null) {
                continue;
            }
            if (stockRepository.warmUp(seckillId, activity.getInitialStock())) {
                written++;
            }
        }
        return written;
    }
}
