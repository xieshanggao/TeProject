-- 多商品种子数据。必须幂等：spring.sql.init.mode=always 意味着每次上下文启动
-- 都会重跑本文件，同一个数据库被启动两次时裸 INSERT 会因主键冲突直接让上下文
-- 启动失败（continue-on-error=false）。
--
-- 这里用显式 id + ON DUPLICATE KEY UPDATE，而不是 INSERT IGNORE：INSERT IGNORE
-- 会把主键冲突之外的错误（数据截断、类型不符等）一并吞掉，在 continue-on-error=false
-- 的配置下等于悄悄关掉了失败保护；ON DUPLICATE KEY UPDATE 只对唯一键冲突
-- 生效，其余错误照常抛出。
INSERT INTO seckill_activity
    (id, name, initial_stock, remaining_stock, stock_updated_at, created_at)
VALUES
    (1, '演示商品 A', 100, 100, NOW(), NOW()),
    (2, '演示商品 B', 500, 500, NOW(), NOW()),
    (3, '演示商品 C', 1000, 1000, NOW(), NOW())
ON DUPLICATE KEY UPDATE id = id;
