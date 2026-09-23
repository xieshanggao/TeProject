# 本地中间件环境（Docker）

秒杀功能需要 Redis（权威库存）、MySQL（持久库存与订单）、RabbitMQ（异步落库）。
本文说明如何在本地用 `docker compose` 起这三件套，以及如何排障。

## 两套环境，互不影响

| | 用途 | 谁起容器 | 端口 |
| --- | --- | --- | --- |
| `docker-compose.yml`（本文） | 本地跑应用、k6 压测 | 你手动 `docker compose up -d` | 固定：6379 / 3306 / 5672 |
| 集成测试 `mvnw.cmd test -Pit` | 自动化验证 | Testcontainers 自动起、跑完自动删 | 随机端口 |

**集成测试不使用本文这套容器**，它由 Testcontainers 自己拉 `redis:7-alpine`、`mysql:8.0`、
`rabbitmq:3.12-alpine` 并用随机端口连接，因此 `docker compose down` 不会影响 `-Pit`。
反过来说，跑 `-Pit` 时会**再起一套**容器，两边同时跑会占更多内存。

## 端口、账号、库名

| 服务 | 镜像 | 宿主地址 | 账号 | 说明 |
| --- | --- | --- | --- | --- |
| Redis | `redis:7-alpine` | `127.0.0.1:6379` | 无 | 开了 AOF 持久化（`--appendonly yes`） |
| MySQL | `mysql:8.0` | `127.0.0.1:3306` | `root` / `root` | 库 `teproject`；时区 `+08:00`；字符集 `utf8mb4` |
| RabbitMQ (AMQP) | `rabbitmq:3.12-management-alpine` | `127.0.0.1:5672` | `guest` / `guest` | — |
| RabbitMQ 管理台 | 同上 | http://127.0.0.1:15672 | `guest` / `guest` | 看队列、连接、消息堆积 |

这些值与 `src/main/resources/application.properties` 的默认值**完全一致**，所以本地启动应用
**不需要任何环境变量或配置改动**。反过来也成立：本仓库不动 `src/main/resources`，而是让容器
去对齐代码。

> 端口一律绑定 `127.0.0.1`。`docker/rabbitmq.conf` 里放开了 guest 用户的 loopback 限制
> （否则从宿主经端口映射连进来的 AMQP 连接会被拒），所以这三件套**不能**暴露到局域网。

## 常用命令

```bash
# 起（等到三个都 healthy 再返回，首次会久一些）
docker compose up -d --wait

# 看状态
docker compose ps

# 停，保留数据卷（下次 up 数据还在）
docker compose down

# 停并清空数据（Redis 库存、MySQL 表、MQ 队列全部重置）
docker compose down -v

# 看某个服务的日志
docker compose logs -f mysql

# 进容器
docker compose exec redis redis-cli
docker compose exec mysql mysql -uroot -proot teproject
```

`down` 与 `down -v` 的区别只在数据卷：`down` 保留三个 named volume，`down -v` 删掉。
**想让 MySQL/Redis 回到「全新初始化」状态就用 `down -v`**，否则旧数据会一直在。

## 本机 MySQL80 的停用与还原

容器 MySQL 占用宿主 `3306`，与本机安装的 MySQL 服务冲突，二者只能起一个。
当前状态：`MySQL80` 已停止并改为**手动启动**（`START_TYPE = DEMAND_START`）。

```bash
# 停用（需要管理员权限的终端；start= 后面必须有空格）
net stop MySQL80
sc config MySQL80 start= demand

# 还原为开机自启
sc config MySQL80 start= auto
net start MySQL80
```

改动可逆：随时用还原命令切回本机 MySQL，代价是要先 `docker compose down` 让出 3306。

## 排障

**拉不到镜像 / `docker pull` 报 EOF。**
镜像走的是 `C:\Users\39373\.docker\daemon.json` 里的 `registry-mirrors`（加速器），不依赖代理。
备份在 `daemon.json.bak`；若四个加速器全部失效，恢复备份后重启 Docker Desktop：
`cp daemon.json.bak daemon.json` + `docker desktop restart`。此时需自行解决网络（如给代理配可用节点）。

**RabbitMQ 管理台打不开 / 12345。**
第一次访问可能返回空响应（Docker Desktop 端口转发刚建立），几秒后重试即可。
若一直不通，先确认容器 healthy，再从容器内部验证：
`docker compose exec rabbitmq wget -qO- --header="Authorization: Basic Z3Vlc3Q6Z3Vlc3Q=" http://127.0.0.1:15672/api/overview`。

**`guest` 登录被拒（ACCESS_REFUSED）。**
说明 `docker/rabbitmq.conf` 没被挂载或没生效。检查
`docker compose exec rabbitmq cat /etc/rabbitmq/rabbitmq.conf` 是否为 `loopback_users.guest = false`，
改完 `docker compose restart rabbitmq`。

**3306 被占用 / 应用连不上数据库。**
`netstat -ano | grep "LISTENING" | grep ":3306 "` 看是谁占了。
本机 `MySQL80` 服务与容器 MySQL 只能留一个（见上一节）。

**MySQL 起不来，日志里有 `Cannot create redo log files because data files are corrupt`。**
数据目录被**非正常关机**弄坏了，通常发生在首次初始化还没跑完时容器被重启/停止。
（`docker compose up -d --wait` 一旦发现某个容器 unhealthy，会把整个 compose 项目停掉，
正好会打断 MySQL 的首次初始化。）修法：删掉数据卷重来 —— 注意这会清空所有表。

```bash
docker compose down
docker volume rm teproject-middleware_mysql-data
docker compose up -d mysql          # 先单独让 MySQL 跑完初始化
docker compose up -d --wait         # 再起其余服务
```

为降低复发概率，compose 里 MySQL 的 healthcheck 设了 `start_period: 150s`
（Windows 上首次初始化实测约 100 秒），使初始化期间的探测失败不计入 `retries`。

**RabbitMQ 首次启动就退出，日志里有 `Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces`。**
全新数据卷上偶发的启动竞态，`restart: unless-stopped` 会自动重启一次并恢复正常。
若反复出现，`docker compose restart rabbitmq`。

## Testcontainers 复用开关（已定论：不启用）

仓库根目录的 `.testcontainers.properties` 写了 `testcontainers.reuse.enable=true`，
但 **`-Pit` 的容器复用是刻意关闭的**：

- Testcontainers 要求每个容器额外调用 `.withReuse(true)` 才会复用，
  而 `IntegrationTestBase` 没有调用——这是决定，不是遗漏。
- 原因：`spring.sql.init.mode=always` 意味着每次上下文启动都会重跑
  `schema.sql` / `data.sql`。容器若跨运行保留状态，非幂等的种子数据会失败。
  理由写在 `IntegrationTestBase` 的类注释里。
- 因此 `.testcontainers.properties` 现在是一份**不起作用的保险**：它只声明了
  「允许复用」，真正开启还需要代码改动。该文件已在 `.gitignore` 中。
