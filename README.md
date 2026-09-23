# TeProject — 分布式秒杀系统

> 把秒杀场景的四个并发不变量（不超卖 / 一人一单 / 不少卖 / 最终一致），
> 放到**真实的 Redis + MySQL + RabbitMQ** 上验证，而不是靠单机内存模拟。

`Java 11` · `Spring Boot 2.7.18` · `Maven Wrapper` · `Redis` · `MySQL` · `RabbitMQ`

---

## 这是什么 / 不是什么

**这是**一个接近真实架构的**教学 demo**：库存权威副本在 Redis、用单个 Lua 脚本原子扣减、
经 RabbitMQ 异步落库到 MySQL、后台对账任务发现不一致时告警。应用无状态、可多实例部署。

**这不是**生产系统。以下限制是**刻意保留**的，看到它们不代表有 bug：

| 限制 | 说明 |
| --- | --- |
| `userId` 不鉴权 | 接口直接采信请求里的 `userId`，谁都能构造。真实系统必须在网关鉴权，**这里的一人一单可以被绕过** |
| 无订单/支付/退款/库存回补 | 只做到「扣减 + 落库流水」 |
| 无秒杀时间窗口与管理端 | 活动由 `data.sql` 种子数据预置 |
| 没有压测结论 | k6 脚本在本机**从未运行过**（未安装 k6），只做过语法校验 |

把它当**学习分布式并发控制**的读本，不要当成可以抄进生产的设计。

---

## 架构

```text
客户端 → 应用实例（无状态，可多实例）
           │
           ├─ Redis ──── 权威库存 + 限购集合（唯一的不变量发生点）
           ├─ RabbitMQ ─ 削峰队列（含死信队列）
           └─ MySQL ──── 持久化流水 + 库存副本（异步、最终一致）
                            ▲
                     消费者 ┴ 对账任务（只告警，不自动修数）
```

一次抢购的完整链路：

1. `SeckillService` 先查**活动注册表**，区分「活动不存在」（404）与「未预热」（503）
2. 调用 `SeckillStockRepository.tryDeduct()` → 执行 `seckill_deduct.lua`
3. Lua 在 Redis 内**原子**完成「判空 → 判限购 → 判库存 → 扣减 → 记录」
4. 扣减成功即返回 `200`，并向 RabbitMQ 投递落库消息（失败做有限次重试，见下文）
5. `SeckillMessageConsumer` 异步把流水写入 MySQL，靠唯一索引保证幂等
6. `SeckillReconciler` 周期性比对 Redis 与 DB，不一致只**告警**

**为什么 200 在落库之前就确定了**：Redis 扣减完成即为业务意义上的成功。
落库是**补偿性**的而非**确认性**的——它只能追赶，不能否决一个已经对用户生效的成功。
所以消息投递失败**不会**让请求变成 500，那会向用户报告与事实相反的结果（库存真的扣了）。

---

## 四个并发不变量

这是整个项目的核心。读代码时始终带着这四个问题去看。

| # | 不变量 | 由什么保证 | 关键位置 |
| --- | --- | --- | --- |
| **I1** | **不超卖**：成功扣减次数 ≤ 初始库存 | Lua 脚本原子性 | `src/main/resources/lua/seckill_deduct.lua` |
| **I2** | **一人一单**：同一用户最多成功一次 | Redis 限购集合（`SISMEMBER`/`SADD`） | 同上 |
| **I3** | **不少卖**：库存耗尽时成功数应恰好等于初始库存 | 同上（扣减与记录同一脚本内完成） | 同上 |
| **I4** | **最终一致**：DB 流水数 + DB 剩余库存 = 初始库存 | 消费者落库 + 唯一索引幂等 | `SeckillMessageConsumer` |

**这四个都由同一个 Lua 脚本保证，而不是锁。** Redis 执行脚本是单线程的，
「判断」与「扣减」之间不存在其它请求插入的窗口，因此不需要分布式锁——
锁反而会引入获取失败、超时、续期等额外失败模式。这是本项目最值得带走的一点。

---

## 阅读顺序（学习路径）

按依赖顺序读，每一步都能理解「它在防什么」。

### 1. 入口：`controller/SeckillController.java`
HTTP 契约、响应形状。注意**失败与成功响应形状一致**（`code` 恒为字符串），
调用方不需要判断类型才能解析。

### 2. 编排：`service/SeckillService.java`
两件事值得看：
- `purchase()` 先查活动注册表，把「活动不存在」和「未预热」分开（404 vs 503）
- `publishWithoutFailingThePurchase()` 对投递失败做 **3 次尝试后放弃并记 error 日志**。
  为什么是「有限」而不是无限：用户此刻已拿到 200 并断开连接，
  在请求线程里无限重试只会耗尽 Web 容器线程，把局部故障放大成整体不可用。
  放弃后留下的 Redis/DB 差值，正是 I4 对账要发现的对象。

### 3. 不变量发生点：`service/SeckillStockRepository.java` + `lua/seckill_deduct.lua`
**全系统唯一允许修改库存的地方。** 逐行读那个脚本：

```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -3 end                       -- 未预热
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then   -- 已购买
    return -2
end
if stock <= 0 then return -1 end                         -- 售罄
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return stock - 1
```

两个易错的细节：
- **判空必须先于判限购**。否则未预热的活动会返回 `-2`（伪装成「已购买」），
  把系统故障伪装成业务拒绝，用户和运维都会被误导。
- 返回值契约 `>=0` 成功 / `-1` 售罄 / `-2` 已购 / `-3` 未预热，
  必须与 `LuaResultMapper.from(long)` **严格对齐**——后者对未定义值抛异常，
  脚本多返回一个数值就会让线上请求 500。

### 4. 预热：`service/SeckillWarmUpRunner.java`
启动时把 MySQL 的活动播种进 Redis，用 **SETNX** 而不是 SET。
原因：SET 会让**已售库存复活**。多实例同时启动时只有一个能赢，这是刻意的幂等设计。

### 5. 落库：`messaging/SeckillMessageConsumer.java`
异步把流水写入 MySQL。**至少一次投递下如何不重复扣减**：
`seckill_record` 上的 `uk_seckill_user` 唯一索引是幂等凭据——
消费者把 `DuplicateKeyException` 视为成功。

### 6. 对账：`reconcile/SeckillReconciler.java` + `ReconcileEvaluator.java`
周期性比对 Redis 与 DB。**只读不写、只告警不自动修数**——
用户已经收到 200，任何一侧的自动改动都可能制造新的不一致。

---

## 为什么这么设计（取舍）

| 决策 | 理由 | 代价 |
| --- | --- | --- |
| 库存权威在 **Redis** 而非 DB | 万级 QPS 下 DB 扛不住每请求一次的原子扣减 | DB 副本滞后，只承诺**最终一致** |
| 用 **Lua** 而非分布式锁 | Redis 执行脚本单线程，判断与扣减间无插入窗口 | 逻辑复杂度集中在脚本里，返回值契约必须与 Java 侧同步维护 |
| **异步落库** | DB 不在抢购关键路径上，这是 QPS 目标的前提 | 存在**双写缺口**（Redis 已扣减但消息投递最终失败） |
| 对账**只告警不自动修数** | 用户已收到 200，自动改动可能制造新的不一致 | 需要人工介入 |
| 预热用 **SETNX** | 无条件 SET 会让已售库存复活 | 多实例启动时只有第一个实例真正播种 |
| 键名带 `{seckillId}` **hash tag** | 脚本同时操作库存键与限购集合，Cluster 下跨 slot 会直接报错 | 看似是命名风格，实际是正确性要求 |

双写缺口是**明确接受**的，处理方式是「记 error 日志 + 由 A1 对账发现并告警」，
详见 `docs/decisions/0003-async-persistence-dual-write-gap.md`。

---

## 快速开始

本地中间件（Redis + MySQL + RabbitMQ）用仓库根目录的 `docker-compose.yml` 起，
容器端口与账号**刻意对齐** `application.properties` 的默认值，因此无需改任何配置。

```bash
# 1. 起中间件（首次启动 MySQL 需耐心等待健康检查）
docker compose up -d --wait

# 2. 起应用（默认端口 8088）
./mvnw.cmd spring-boot:run

# 3. 抢购一单（活动 1 的初始库存是 100）
curl -X POST http://localhost:8088/api/seckill/activities/1/purchase \
     -H 'Content-Type: application/json' \
     -d '{"userId":"u_10086"}'
# → {"code":"SUCCESS","message":"抢购成功","remainingStock":99}

# 4. 查实时库存（读 Redis，不是读 DB）
curl http://localhost:8088/api/seckill/activities/1/stock
# → {"code":"SUCCESS","message":"抢购成功","remainingStock":99}

# 5. 确认预热真的把 MySQL 的种子活动写进了 Redis
docker compose exec redis redis-cli keys 'seckill:*'
```

种子活动：`1` = 演示商品 A（100）、`2` = 演示商品 B（500）、`3` = 演示商品 C（1000）。

> **Windows + Docker Engine 29 的两个坑**（本机踩过，理由都写在 `pom.xml` / `IntegrationTestBase` 注释里）：
> 集成测试需要 surefire 注入 `api.version=1.44`（docker-java 3.3.6 固定用 API 1.32，daemon 直接回 HTTP 400）；
> MySQL 容器挂 tmpfs 并关闭 binlog/doublewrite，冷启动从 4 分 16 秒降到约 40 秒。

---

## API

完整契约见 [`docs/api/seckill.md`](docs/api/seckill.md)。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/seckill/activities/{seckillId}/purchase` | 抢购，body `{"userId":"..."}` |
| `GET` | `/api/seckill/activities/{seckillId}/stock` | 查询实时库存快照 |

| 场景 | HTTP | `code` |
| --- | --- | --- |
| 抢购成功 | `200` | `SUCCESS` |
| 库存售罄 | `409` | `STOCK_SOLD_OUT` |
| 该用户已购 | `409` | `ALREADY_PURCHASED` |
| 活动不存在 | `404` | `SECKILL_NOT_FOUND` |
| 服务未就绪 | `503` | `NOT_READY` |
| 参数非法 | `400` | `INVALID_REQUEST` |

两个常见疑问：

- **「已购」为什么是 409 而不是 403？** 这不是权限问题，而是同一
  `(seckillId, userId)` 资源已存在的**状态冲突**。附带效果是**接口幂等**——
  重复点击或网络重试不会二次扣减，稳定返回 `ALREADY_PURCHASED`。
- **「活动不存在」为什么是 404 而不是 503？** 启动时载入的活动注册表把两种情况分开了：
  不在注册表中 → `404`（调用方输入错误）；在注册表中但 Redis 无库存键 → `503`（预热失败）。
  **Redis 整体不可用时一律 `503`，绝不降级去读 DB**——部分请求走 Redis、部分走 DB
  会让两个存储各自扣减，必然超卖。

---

## 测试与证据

```bash
./mvnw.cmd test          # 28 通过，不需要 Docker
./mvnw.cmd test -Pit     # 65 通过，Testcontainers 自起真实 Redis/MySQL/RabbitMQ
```

- **单元测试**（默认集）不依赖 Docker，随时可跑。
- **集成测试**（`-Pit`）用 Testcontainers 起真实中间件，覆盖 Lua 返回码契约、
  预热、消费幂等、对账只读性，以及**四个并发不变量的并发验证**。

关于并发测试的两个设计要点：

1. **并发测试是概率性的。** 它提高撞上竞态的概率，但不保证每次都撞上。
   因此不变量测试跑 **10 轮 × 200 并发请求**，而不是跑一次就宣布正确。
2. **测试要能失败才有价值。** 本项目多次出现「测试通过但证明不了它声称证明的东西」，
   对策是**实际执行变异检查**：临时把生产代码改坏，确认测试真的变红，再还原。

> ⚠️ **压测脚本尚未运行过。** `scripts/loadtest/` 下的 k6 脚本只做过语法校验
> （`node --check` 与 PowerShell 解析均通过，并配了故意写错的负向对照），
> **本机未安装 k6，从未实际压测**。见 `scripts/loadtest/README.md`。

---

## 已知边界与未完成项

诚实清单。这些**不是**被忽略的 bug，而是已知且记录在案的缺口：

- **`seckill.messaging.max-retries` 是死键**：全仓库没有任何代码读取它，改它不改变行为。
  真正生效的是 `spring.rabbitmq.listener.simple.retry.max-attempts`。同一件事存在两个配置源。
- **消费 ack 模式与设计文档措辞不符**：设计文档写「手动 ack」，实现是 `acknowledge-mode=auto`。
  两者在此场景语义等价（正常返回才 ack，抛异常不 ack），但文档措辞不准。
- **`SeckillRecordRepository` 的 `@Transactional` 回滚路径未被测试覆盖**。
- **`userId` 不鉴权**，一人一单可被绕过（见上文「不是什么」）。
- **k6 压测从未执行**，没有任何性能数字。

与生产系统的完整差距清单见
[`docs/superpowers/specs/2026-09-16-distributed-seckill-design.md`](docs/superpowers/specs/2026-09-16-distributed-seckill-design.md) 第 11 节。

---

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | 模块边界、技术约定、并发规范 |
| [`docs/business.md`](docs/business.md) | 领域术语、流程与规则 |
| [`docs/api/seckill.md`](docs/api/seckill.md) | 秒杀接口契约、并发语义与就绪状态 |
| [`docs/docker-middleware.md`](docs/docker-middleware.md) | 本地中间件的起停、账号与排障 |
| [`docs/decisions/`](docs/decisions/) | 架构决策记录（ADR） |
| `docs/superpowers/` | 设计规格与实施计划 |

三篇关键 ADR：

- [0002 · 库存权威副本放在 Redis](docs/decisions/0002-redis-as-stock-authority.md)
- [0003 · 异步落库与双写缺口](docs/decisions/0003-async-persistence-dual-write-gap.md)
- [0004 · 用 Lua 而非分布式锁](docs/decisions/0004-lua-over-distributed-lock.md)
