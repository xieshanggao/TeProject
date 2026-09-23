package com.fjf.teproject.repository;

import com.fjf.teproject.domain.SeckillActivity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 秒杀活动的持久化访问。
 *
 * <p>使用 JdbcTemplate 而非 JPA：本系统的核心是并发正确性，
 * 显式 SQL 让「两条语句在同一事务内」这一语义在代码里直接可见。</p>
 */
@Repository
public class SeckillActivityRepository {

    private final JdbcTemplate jdbcTemplate;

    public SeckillActivityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> findAllIds() {
        return jdbcTemplate.queryForList("SELECT id FROM seckill_activity ORDER BY id", Long.class);
    }

    public Optional<SeckillActivity> findById(long seckillId) {
        List<SeckillActivity> rows = jdbcTemplate.query(
                "SELECT id, name, initial_stock, remaining_stock, stock_updated_at "
                        + "FROM seckill_activity WHERE id = ?",
                (resultSet, rowNum) -> new SeckillActivity(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getInt("initial_stock"),
                        resultSet.getInt("remaining_stock"),
                        resultSet.getTimestamp("stock_updated_at").toLocalDateTime()),
                seckillId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SeckillActivity> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, initial_stock, remaining_stock, stock_updated_at "
                        + "FROM seckill_activity ORDER BY id",
                (resultSet, rowNum) -> new SeckillActivity(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getInt("initial_stock"),
                        resultSet.getInt("remaining_stock"),
                        resultSet.getTimestamp("stock_updated_at").toLocalDateTime()));
    }

    /** 仅供测试使用：把活动重置回初始状态。 */
    public void resetToInitial(long seckillId) {
        jdbcTemplate.update(
                "UPDATE seckill_activity SET remaining_stock = initial_stock, "
                        + "stock_updated_at = ? WHERE id = ?",
                Timestamp.valueOf(java.time.LocalDateTime.now()), seckillId);
    }
}
