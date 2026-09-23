# TeProject

此仓库包含 Spring Boot 应用及其共享的项目知识。

## 从这里开始

1. 实现任务前阅读 `AGENTS.md`。
2. 以 `docs/business.md` 作为业务术语和规则的唯一事实来源。
3. 修改 API 或架构相关内容前，阅读对应文档。

## 运行与测试

```powershell
./mvnw.cmd test
./mvnw.cmd spring-boot:run
```

## 知识库

- `docs/business.md`：示例领域术语、流程和规则。请用真实项目需求替换示例。
- `docs/architecture.md`：模块边界和技术约定。
- `docs/api/`：对调用方可见的 API 契约。
- `docs/api/seckill.md`：分布式秒杀的 API 契约与边界。
- `docs/decisions/`：架构决策记录（ADR）。
- `scripts/loadtest/`：k6 压测脚本与说明。**该脚本尚未在 k6 上实际运行过**
  （本机未安装 k6，只做过语法校验），见该目录下的 `README.md`。
- `docs/task-template.md`：可复用的任务描述模板。
