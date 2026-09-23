# AI 与团队协作指南

## 必读内容

修改代码前，请阅读与任务相关的文档：

| 变更范围 | 优先阅读 |
| --- | --- |
| Any business feature | `docs/business.md` |
| API change | `docs/api/orders.md` |
| 秒杀或并发库存功能 | `docs/api/seckill.md`、`docs/architecture.md` |
| Architecture or module boundary | `docs/architecture.md` |
| A decision with long-term impact | `docs/decisions/` |

如果所需业务规则缺失或含义不明确，请在实现前提问。不得自行编造规则。

## 项目约定

- 运行环境：Java 11、Spring Boot 2.7、Maven。
- 变更仅聚焦于请求的功能。
- 变更业务规则时，新增或更新自动化测试。
- 业务规则、API 契约或长期决策发生变更时，更新对应文档。
- 对影响未来实现选择的决策，在 `docs/decisions/` 中记录新的 ADR。
- 公共接口及并发控制代码应添加注释，说明其用途、并发不变量或设计原因；不要添加仅复述代码字面含义的注释。
- 高并发功能必须包含并发测试，验证业务上限（例如库存不超卖）及最终状态。

## 交付前

- macOS/Linux 执行 `./mvnw test`，Windows 执行 `mvnw.cmd test`。
- 说明变更的业务规则、文件以及未验证的假设。

## 任务请求模板

```text
目标：

相关文档：
- docs/...

业务规则：
- ...

验收标准：
- ...

不在范围内：
- ...
```
