-- 秒杀扣减脚本：系统唯一的并发不变量发生点。
--
-- KEYS[1] = seckill:stock:{seckillId}    实时库存
-- KEYS[2] = seckill:bought:{seckillId}   已购用户集合
-- ARGV[1] = userId
--
-- 返回值约定（必须与 LuaResultMapper 保持一致）：
--   >= 0   成功，值为扣减后的剩余库存
--   -1     库存售罄
--   -2     该用户已购买过
--   -3     活动未预热（缺少库存键）
--
-- 顺序不可调换：判空必须先于判限购。否则一个未预热的活动会返回 -2（已购），
-- 把系统故障伪装成业务拒绝，用户和运维都会被误导。

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then
    return -3
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -2
end

if stock <= 0 then
    return -1
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return stock - 1
