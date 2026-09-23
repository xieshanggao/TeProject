# 秒杀压测

## 目的

本压测用于**观察**系统在并发压力下的行为，并**实证**不超卖。它不是性能门槛。

## 现实预期

在单台 Windows 笔记本 + Docker 化 Redis 的环境下，压出万级 QPS 的可能性很低——
瓶颈通常先出现在 Docker 网络、JVM 或压测工具自身。

因此验收标准是**正确性指标**：

- 成功数恰好等于初始库存（不超卖、不少卖）
- 应用日志中没有对账告警
- 最终 DB 落库数与成功数一致

QPS 数字如实记录即可，不与任何阈值比较。

## 诚实声明：脚本从未在 k6 上运行过

本脚本在本仓库的开发机上**从未实际执行**——该机器没有安装 k6
（`which k6` 无输出）。它只通过了语法校验，校验方式是把脚本复制为临时 `.mjs`
后运行 `node --check`（k6 脚本是 ESM，`.js` 会被 node 当作 CommonJS 而误报）。

因此：**任何 QPS 数字、任何「不超卖」结论都尚未由本脚本产出。**
下面的「结果解读」描述的是脚本被真正执行后应当看到什么，不是已经看到过什么。
谁执行，谁才有资格下结论。

## 前置条件

1. 应用已启动：`mvnw.cmd spring-boot:run`（监听 `localhost:8088`）
2. **目标活动已预热**。启动时 `SeckillWarmUpRunner` 会用 `SETNX` 写入库存键；
   若 Redis 中没有该键，Lua 返回 `-3`，所有请求都得到 `503`，压测结果毫无意义。
   压测前先确认 `GET /api/seckill/activities/1/stock` 返回 `200`。
3. 目标活动的 Redis 库存已重置为预期初值。重启应用后 `SETNX` **不会**覆盖已有键，
   因此重置需要先删除对应键或 `FLUSHALL`。
4. 已安装 [k6](https://k6.io/docs/get-started/installation/)（本机尚未安装）。

**压测会真实消耗 Redis 库存与 MySQL 数据**：跑完必须重置才能重跑，
否则第二轮是在「已售罄」的状态上压测。重置方式二选一：

- 删除 Redis 中 `seckill:stock:{1}` 与 `seckill:bought:{1}` 两个键，再重启应用
  （重启时 `SETNX` 才会把库存写回）；
- 或对数据库调用 `resetToInitial` 并清空 Redis。

## 运行

```powershell
# 默认：活动 1，初始库存 100
.\scripts\loadtest\run-loadtest.ps1

# 自定义目标
$env:SECKILL_ID=2; $env:INITIAL_STOCK=500; .\scripts\loadtest\run-loadtest.ps1
```

脚本参数（环境变量）：`BASE_URL`（默认 `http://localhost:8088`）、
`SECKILL_ID`（默认 `1`）、`INITIAL_STOCK`（默认 `100`）。
`INITIAL_STOCK` 必须与目标活动在 `data.sql` 中的初始库存一致，结论才有意义。

## 结果解读

脚本在结束时打印结论：

- `不超卖：成功 N 件，初始库存 M 件` — 符合预期
- `超卖！成功 N 件，超过初始库存 M 件` — **严重缺陷**，立即排查

详细指标写入 `scripts/loadtest/last-result.json`。

注意「不超卖」只是必要条件。要确认**不少卖**，还需核对成功数恰好等于初始库存
（而非小于），以及下方 SQL 中 `initial_stock - remaining_stock = records` 恒等。

## 压测后核对

1. 检查应用日志中是否有 `对账发现不一致` 的 ERROR
2. 执行以下 SQL 核对最终一致：

```sql
SELECT a.id,
       a.initial_stock,
       a.remaining_stock,
       (SELECT COUNT(*) FROM seckill_record r WHERE r.seckill_id = a.id) AS records
FROM seckill_activity a;
```

正常情况下：`initial_stock - remaining_stock = records`。

若不相等，说明 A2 金丝雀被触发——两条语句在同一事务内，正常情况下不可能分叉，
需排查是否有代码缺陷或人工改数。

## 与集成测试的关系

正确性不变量由集成测试（`mvnw.cmd test -Pit`，T1–T4）在受控条件下验证。
本压测是补充观察手段，不替代集成测试：它跑在真实容器与真实网络栈上，
数据更接近实况，但也因此更嘈杂、不可重复。
