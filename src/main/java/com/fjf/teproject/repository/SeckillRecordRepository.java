package com.fjf.teproject.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀流水的持久化访问。
 *
 * <p>{@link #recordPurchase} 把「写入流水」与「扣减数据库库存并推进时间戳」
 * 放在同一个事务里，三件事必须同生共死。分开写会让库存与流水字段产生
 * 无意义的分叉，淹没 A2 金丝雀真正要捕捉的信号（代码缺陷或人工改数）。</p>
 *
 * <p>唯一索引 {@code uk_seckill_user} 是幂等性的凭证：消息的至少一次投递
 * 会导致重复消费，重复时 INSERT 抛 {@link DuplicateKeyException}，捕获后
 * 返回 {@code false} 即可，无需额外的幂等表。</p>
 */
@Repository
public class SeckillRecordRepository {

    private final JdbcTemplate jdbcTemplate;

    public SeckillRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 记录一次成功抢购，并同步扣减数据库中的库存副本。
     *
     * <p>整个方法在一个事务内执行：流水插入、库存扣减、时间戳推进三条副作用
     * 绑定为一次提交。唯一键冲突由 {@link DuplicateKeyException} 捕获，
     * 该分支在扣减之前返回，因此重复消息不会二次扣减。</p>
     *
     * <p>{@code try/catch} 有意只包住 INSERT：一旦异常在扣减之后才出现，
     * 事务边界（{@code @Transactional}）负责把已完成的扣减一并回滚。</p>
     *
     * @return {@code true} 表示本次真的写入；{@code false} 表示是重复消息，已安全忽略
     */
    @Transactional
    public boolean recordPurchase(long seckillId, String userId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO seckill_record (seckill_id, user_id, created_at) VALUES (?, ?, ?)",
                    seckillId, userId, Timestamp.valueOf(LocalDateTime.now()));
        } catch (DuplicateKeyException exception) {
            // 至少一次投递的正常后果，不是错误。返回 false 而非抛出：
            // Task 10 的消费者据此决定 ack 策略。
            return false;
        }

        jdbcTemplate.update(
                "UPDATE seckill_activity SET remaining_stock = remaining_stock - 1, "
                        + "stock_updated_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), seckillId);
        return true;
    }

    public int countByActivity(long seckillId) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT COUNT(*) FROM seckill_record WHERE seckill_id = ?",
                Integer.class, seckillId);
        return counts.isEmpty() || counts.get(0) == null ? 0 : counts.get(0);
    }

    public boolean exists(long seckillId, String userId) {
        List<Integer> counts = jdbcTemplate.queryForList(
                "SELECT COUNT(*) FROM seckill_record WHERE seckill_id = ? AND user_id = ?",
                Integer.class, seckillId, userId);
        return !counts.isEmpty() && counts.get(0) != null && counts.get(0) > 0;
    }
}
