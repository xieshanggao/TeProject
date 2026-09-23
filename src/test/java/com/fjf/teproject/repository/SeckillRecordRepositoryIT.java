package com.fjf.teproject.repository;

import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证流水仓储在真实 MySQL 上的事务语义。
 *
 * <p>核心断言只有一条：重复投递（消息「至少一次」投递的必然结果）被唯一索引
 * 挡下时，库存不得二次扣减。这条不成立，一条消息就能凭空吃掉两件库存，
 * 且 A2 金丝雀会把这种分叉误报成业务故障。</p>
 */
@Tag("integration")
class SeckillRecordRepositoryIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 容器跨用例复用，且本类会改写 DB 库存，必须在每个用例前把活动与流水都
     * 拉回已知状态，否则用例顺序会左右结果。
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    @Test
    void firstInsertionReturnsTrueAndAdvancesStock() {
        int before = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        boolean inserted = recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertTrue(inserted);
        assertEquals(before - 1, activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
        assertEquals(1, recordRepository.countByActivity(SECKILL_ID));
    }

    /**
     * 本任务的核心断言：重复投递返回 false，且库存与流水条数都不动。
     *
     * <p>brief 另有一个「重复投递整体回滚」的用例，但它只断言流水条数是 1——
     * 这由唯一索引保证，与事务是否生效无关，属于语义重复（执行约束 6），
     * 因此合并到本用例，并保留真正有判别力的库存断言。</p>
     */
    @Test
    void duplicateInsertionIsRejectedAndDoesNotDeductStockAgain() {
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        int afterFirst = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        boolean insertedAgain = recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertFalse(insertedAgain);
        assertEquals(afterFirst, activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
        assertEquals(1, recordRepository.countByActivity(SECKILL_ID));
    }

    /**
     * 唯一索引必须是 (seckill_id, user_id) 复合键：同一用户在不同活动里
     * 各抢一件都应放行，若误建成 user_id 单列唯一，这里只有 1 条流水。
     */
    @Test
    void differentUsersEachDeductOne() {
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        recordRepository.recordPurchase(SECKILL_ID, "u2");
        recordRepository.recordPurchase(SECKILL_ID, "u3");

        assertEquals(3, recordRepository.countByActivity(SECKILL_ID));
        assertEquals(97, activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
    }

    @Test
    void existsReflectsStoredRecords() {
        assertFalse(recordRepository.exists(SECKILL_ID, "u1"));

        recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertTrue(recordRepository.exists(SECKILL_ID, "u1"));
        assertFalse(recordRepository.exists(SECKILL_ID, "u2"));
    }
}
