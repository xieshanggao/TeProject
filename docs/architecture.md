# 架构

## 当前技术基线

| 范围 | 选型 |
| --- | --- |
| 语言 | Java 11 |
| 框架 | Spring Boot 2.7 |
| 构建工具 | Maven Wrapper |
| HTTP | Spring Web |
| 测试 | Spring Boot Test |

## 本地中间件环境

秒杀功能需要 Redis（权威库存）、MySQL（持久库存与订单）、RabbitMQ（异步落库）。
本地用仓库根目录的 `docker-compose.yml` 起这三件套，容器端口与账号**刻意对齐**
`application.properties` 的默认值，因此本地启动应用无需任何配置改动，也不需要修改
`src/main/resources` 下的任何文件。

- 起停、账号、排障，以及本机 MySQL 服务的停用与还原：见 `docs/docker-middleware.md`。
- 集成测试（`mvnw.cmd test -Pit`）用的是 Testcontainers 自起的随机端口容器，与这套互不影响。
- 压测脚本（k6）压的是这套容器上的应用：见 `scripts/loadtest/README.md`。
  该脚本在本机**尚未实际运行过**（未安装 k6），只做过语法校验。

## 建议的应用边界

随着应用发展，按以下边界组织代码。在功能确有需要前，不要提前创建分层。

```text
controller  -> HTTP 请求/响应和输入校验
service     -> 业务用例和事务边界
repository  -> 持久化访问
domain      -> 业务实体、状态和值对象
```

Controller 不应包含生命周期规则。例如，订单是否可以取消属于 service/domain 层，并应在该处测试。

## 分布式秒杀

秒杀库存的权威副本在 **Redis** 中，通过单个 Lua 脚本原子完成
「判断限购 → 判断库存 → 扣减 → 记录」，因此不需要分布式锁，
且在**多实例部署**下依然不超卖。

```text
客户端 → 应用实例（无状态，可多实例）
           │
           ├─ Redis ──── 权威库存 + 限购集合（唯一的不变量发生点）
           ├─ RabbitMQ ─ 削峰队列（含死信队列）
           └─ MySQL ──── 持久化流水 + 库存副本（异步、最终一致）
                            ▲
                     消费者 ┴ 对账任务（只告警，不自动修数）
```

选型说明：

- **库存权威副本在 Redis 而非数据库**：万级 QPS 下数据库无法承载
  每请求一次的原子扣减。代价是数据库副本会滞后，系统只承诺最终一致。
- **用 Lua 而非分布式锁**：Redis 执行脚本是单线程的，判断与扣减之间
  不存在插入窗口；锁则会引入获取失败、超时、续期等额外失败模式。
- **异步落库**：数据库不在抢购请求的关键路径上，这是 QPS 目标的前提。
  代价是存在双写缺口，见 ADR 0003。

已知缺口与简化见 `docs/decisions/0002`、`0003`、`0004`，
以及与生产系统的差距清单见 `docs/superpowers/specs/2026-09-16-distributed-seckill-design.md` 第 11 节。

### 与 spec 不符的一处配置：消费重试旋钮

spec §6.4 规定了消费失败要有限次重试；`application.properties` 里对应的键是
`seckill.messaging.max-retries`（注释标注「见 spec §6.4」），
`SeckillMessagingProperties` 也把它绑定成了属性对象。**但这个属性是死的**：
全仓库没有任何代码读取 `getMaxRetries()`，改它不会改变任何行为。

真正生效的是 **`spring.rabbitmq.listener.simple.retry.max-attempts`**
（`application.properties`，由 Spring AMQP 的监听器容器直接读取）。
要调整消费失败的重试次数，**请改这一个**，而不是 `seckill.messaging.max-retries`。

`SeckillMessageConsumerIT` 目前把两个键一起调小，只是因为同一件事存在两个
配置源，并不是第二个在起作用。

保留 `seckill.messaging.max-retries` 而不删除，是因为 spec 是冻结的设计文档，
删属性会让代码与 spec 更加对不上；由使用者决定是补上消费端逻辑还是移除该键。

### 与 spec 的另一处差异：消费 ack 模式

spec §6.4 写的是「手动 ack」，实现用的是 `acknowledge-mode=auto`
（`application.properties`）。二者在语义上等价：监听器方法正常返回时
事务已经提交，抛异常时事务回滚且消息不被 ack，由重试/死信机制接管。
`SeckillMessageConsumer` 的类注释记录了不选手动 ack 的理由——手动模式在这里
换不到额外保证，却多出「某条路径忘记 ack 导致 unacked 堆积」的手写错误面。

### 秒杀代码的并发规范

- `SeckillStockRepository` 是唯一不被允许绕过的不变量发生点。任何库存
  修改都必须经过 `seckill_deduct.lua`。
- 对账任务**只读不写**。发现不一致时告警，由人工介入，不自动修数——
  用户已经收到 `200`，任何一侧的自动改动都可能制造新的不一致。

## 并发代码与注释规范

- 并发代码必须在类或方法注释中说明保护的业务不变量。例如：成功扣减次数不得超过初始库存，库存不得小于 `0`。
- 注释应解释为什么选择同步、CAS 或其他并发控制方式；不重复变量赋值、循环等显而易见的代码行为。
- 并发行为必须通过自动化测试验证：使用多个线程同时发起操作，断言成功次数不超过业务上限，并验证最终状态。
- 接口文档必须写明并发语义及实现边界，例如单实例限制、数据重启后的行为和未覆盖的业务能力。

## 契约归属

- `docs/business.md` 定义业务含义和规则。
- `docs/api/` 定义对调用方可见的 HTTP 契约。
- 代码实现文档定义的规则。
- `docs/decisions/` 说明重要选择及其影响。
