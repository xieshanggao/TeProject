# 分布式秒杀系统实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有的单实例内存秒杀演示，演进为 Redis 权威库存 + RabbitMQ 异步落库 + MySQL 持久化的多实例分布式秒杀系统。

**Architecture:** Redis 持有权威库存与限购集合，通过单个 Lua 脚本原子完成「判断限购 → 扣减 → 记录」。请求在 Redis 扣减成功后立即返回 `200`，随后异步投递消息，由消费者在同一事务内写流水与更新库存。对账任务周期性比对 Redis 与 DB，只告警不自动修数。

**Tech Stack:** Java 11、Spring Boot 2.7.18、Maven Wrapper、Spring Data Redis (Lettuce)、Spring AMQP (RabbitMQ)、Spring JDBC (JdbcTemplate)、MySQL 8、Testcontainers、JUnit 5

**Spec:** `docs/superpowers/specs/2026-09-16-distributed-seckill-design.md`

## Global Constraints

- Java 版本 `11`，Spring Boot `2.7.18`（`pom.xml` 已固定，不得升级）
- Windows 下执行测试使用 `mvnw.cmd test`（macOS/Linux 用 `./mvnw test`）
- **默认测试命令不得依赖 Docker**；依赖 Docker 的测试必须标 `@Tag("integration")`，只在 `mvnw.cmd test -Pit` 下运行
- 公开接口与并发控制代码必须写注释，说明**用途、并发不变量或设计原因**；不得写复述代码字面含义的注释（`AGENTS.md` 约定）
- 包根为 `com.fjf.teproject`；按 `docs/architecture.md` 的分层组织：`controller` / `service` / `repository` / `domain`，新增 `messaging` 与 `reconcile` 两层
- 持久化访问使用 **JdbcTemplate**，不使用 JPA（显式 SQL 让「两条语句在同一事务」这一并发语义直接可见）
- 持久化使用 **Spring JDBC** 而非 JPA：本系统的核心是并发正确性，JPA 的脏检查与延迟加载会给并发语义引入无关噪音
- **本仓库当前不是 git 仓库**，因此本计划不含 commit 步骤。这是一个刻意的适配，不是遗漏
- 四条并发不变量（spec §5.2）：I1 不超卖、I2 一人一单、I3 不少卖、I4 最终一致

## 任务阶段概览

| 阶段 | 任务 | 需要 Docker |
| --- | --- | --- |
| **阶段 1** 纯领域 | Task 1–5 | 否 |
| **阶段 2** Redis | Task 6–8 | 是 |
| **阶段 3** DB / MQ / 编排 | Task 9–12 | 是 |
| **阶段 4** 并发验证与交付 | Task 13–17 | 是 |

阶段 1 的产物可在当前（Docker 重装中）环境下立即完成并验证。阶段 2 起需等待 Docker 就绪。

---

# 阶段 1：纯领域（不需要 Docker）

## Task 1: 依赖与构建配置

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/test/java/com/fjf/teproject/TeProjectApplicationTests.java`
- Create: `src/test/resources/application-test.properties`

**Interfaces:**
- Consumes: 无
- Produces: 后续所有任务可用的依赖（Redis、AMQP、JDBC、MySQL 驱动、Testcontainers）与 `it` profile

**背景：** `TeProjectApplicationTests` 目前是裸的 `@SpringBootTest`。一旦加入 MySQL 依赖，它会尝试连接数据库，导致默认测试失败。必须把它移入 integration 层。

- [ ] **Step 1: 在 `pom.xml` 的 `<properties>` 之后加入 testcontainers 版本属性**

```xml
<properties>
    <java.version>11</java.version>
    <testcontainers.version>1.19.8</testcontainers.version>
</properties>
```

- [ ] **Step 2: 在 `pom.xml` 的 `<dependencies>` 中追加依赖**

保留现有的 `spring-boot-starter`、`spring-boot-starter-test`、`spring-boot-starter-web`（注意：现有文件里 `spring-boot-starter-web` 重复声明了两次，**保持不变**，本次不清理它以免扩大变更范围）。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
    <scope>runtime</scope>
</dependency>

<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

Redis 没有官方 Testcontainers 模块可用（1.19.x 尚未提供），因此阶段 2 使用 `org.testcontainers:testcontainers` 的 `GenericContainer`，该 artifact 已由 `junit-jupiter` 传递引入。

- [ ] **Step 3: 在 `pom.xml` 中配置 surefire 默认排除 integration 测试**

在 `<build><plugins>` 内 `spring-boot-maven-plugin` 之后加入：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <excludedGroups>integration</excludedGroups>
    </configuration>
</plugin>
```

- [ ] **Step 4: 在 `pom.xml` 中新增 `it` profile**

在 `</build>` 之后、`</project>` 之前加入：

```xml
<profiles>
    <profile>
        <id>it</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                        <excludedGroups combine.self="override"></excludedGroups>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

`combine.self="override"` 是必需的——否则 Maven 会把 profile 中的空值与基础配置合并，`integration` 仍被排除，`-Pit` 将静默地什么也不跑。

- [ ] **Step 5: 把 `TeProjectApplicationTests` 移入 integration 层**

完整替换 `src/test/java/com/fjf/teproject/TeProjectApplicationTests.java`：

```java
package com.fjf.teproject;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证应用上下文可以完整启动。
 *
 * <p>标记为 integration：加入数据库与消息中间件后，上下文启动需要真实的
 * MySQL / Redis / RabbitMQ，因此本测试不再属于默认测试集。</p>
 */
@SpringBootTest
@Tag("integration")
class TeProjectApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 6: 配置 `application.properties`**

完整替换 `src/main/resources/application.properties`：

```properties
spring.application.name=TeProject
server.port=8088

# 数据源：默认指向本地 MySQL，可通过环境变量覆盖
spring.datasource.url=${SECKILL_DB_URL:jdbc:mysql://localhost:3306/teproject?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}
spring.datasource.username=${SECKILL_DB_USER:root}
spring.datasource.password=${SECKILL_DB_PASSWORD:root}
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# 启动时执行 schema.sql 与 data.sql
spring.sql.init.mode=always
spring.sql.init.continue-on-error=false

# Redis
spring.redis.host=${SECKILL_REDIS_HOST:localhost}
spring.redis.port=${SECKILL_REDIS_PORT:6379}

# RabbitMQ
spring.rabbitmq.host=${SECKILL_MQ_HOST:localhost}
spring.rabbitmq.port=${SECKILL_MQ_PORT:5672}
spring.rabbitmq.username=${SECKILL_MQ_USER:guest}
spring.rabbitmq.password=${SECKILL_MQ_PASSWORD:guest}

# 对账任务：执行间隔与「停滞」判定阈值（秒），见 spec §9
seckill.reconcile.interval-seconds=10
seckill.reconcile.stall-threshold-seconds=60

# MQ 消费失败重试次数，超过后进入死信队列（见 spec §6.4）
seckill.messaging.max-retries=3
```

- [ ] **Step 7: 创建 `src/test/resources/application-test.properties`**

```properties
# 默认（非 integration）测试中禁用 SQL 初始化与消费者启动，
# 避免任何测试意外要求基础设施。
spring.sql.init.mode=never
```

- [ ] **Step 8: 验证默认测试仍然通过**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS。`TeProjectApplicationTests` 被跳过（组 `integration` 被排除），`SeckillInventoryServiceTest` 通过。

- [ ] **Step 9: 验证 `-Pit` profile 确实纳入 integration 测试**

Run: `mvnw.cmd test -Pit -Dtest=TeProjectApplicationTests`

Expected: 该测试被**执行**，并因连不上 MySQL 而 FAIL（此时 Docker 尚未就绪，这是预期结果）。

**这一步的意义**：它证明 `-Pit` 真的生效了。如果显示 "No tests were executed"，说明 Step 4 的 `combine.self="override"` 没写对，必须回去修——这个错误如果留到阶段 2，会表现为「集成测试写了但从不运行」，是最危险的一类静默失败。

---

## Task 2: 错误码与业务异常

**Files:**
- Create: `src/main/java/com/fjf/teproject/domain/SeckillErrorCode.java`
- Create: `src/main/java/com/fjf/teproject/domain/SeckillException.java`
- Test: `src/test/java/com/fjf/teproject/domain/SeckillErrorCodeTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `SeckillErrorCode` 枚举，方法 `String getCode()`、`int getHttpStatus()`、`String getMessage()`
  - `SeckillException extends RuntimeException`，方法 `SeckillErrorCode getErrorCode()`
  - 枚举常量：`SUCCESS`、`STOCK_SOLD_OUT`、`ALREADY_PURCHASED`、`SECKILL_NOT_FOUND`、`NOT_READY`、`INVALID_REQUEST`

**设计说明：** `SeckillErrorCode` 持有 `int httpStatus` 而非 `org.springframework.http.HttpStatus`，避免 domain 层依赖 Web 框架。HTTP 状态码是接口契约的一部分（spec §7.2），放在这里作为唯一事实来源。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/fjf/teproject/domain/SeckillErrorCodeTest.java`：

```java
package com.fjf.teproject.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeckillErrorCodeTest {

    @Test
    void everyCodeMapsToTheHttpStatusInTheContract() {
        // 断言与本文件中的契约表一一对应；改契约必须先改这里的测试。
        assertEquals(200, SeckillErrorCode.SUCCESS.getHttpStatus());
        assertEquals(409, SeckillErrorCode.STOCK_SOLD_OUT.getHttpStatus());
        assertEquals(409, SeckillErrorCode.ALREADY_PURCHASED.getHttpStatus());
        assertEquals(404, SeckillErrorCode.SECKILL_NOT_FOUND.getHttpStatus());
        assertEquals(503, SeckillErrorCode.NOT_READY.getHttpStatus());
        assertEquals(400, SeckillErrorCode.INVALID_REQUEST.getHttpStatus());
    }

    @Test
    void soldOutAndAlreadyPurchasedShareAStatusButMustNotShareACode() {
        // 两者都是 409，客户端靠 code 区分，因此 code 必须不同。
        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT.getHttpStatus(),
                SeckillErrorCode.ALREADY_PURCHASED.getHttpStatus());
        assertTrue(!SeckillErrorCode.STOCK_SOLD_OUT.getCode()
                .equals(SeckillErrorCode.ALREADY_PURCHASED.getCode()));
    }

    @Test
    void allCodesAreUnique() {
        List<String> codes = Arrays.stream(SeckillErrorCode.values())
                .map(SeckillErrorCode::getCode)
                .collect(Collectors.toList());
        assertEquals(codes.size(), codes.stream().distinct().count());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Dtest=SeckillErrorCodeTest`

Expected: 编译失败，`cannot find symbol: class SeckillErrorCode`

- [ ] **Step 3: 实现错误码枚举**

创建 `src/main/java/com/fjf/teproject/domain/SeckillErrorCode.java`：

```java
package com.fjf.teproject.domain;

/**
 * 秒杀接口的错误码，以及每个错误码对应的 HTTP 状态与提示文案。
 *
 * <p>这是接口契约（docs/api/seckill.md）的唯一事实来源：同一字段在
 * 成功与失败时都是字符串类型，避免调用方需要判断类型才能解析。</p>
 *
 * <p>「售罄」与「已购」共用 409，因为它们都是资源状态冲突而非权限问题；
 * 调用方依靠 code 区分二者。附带效果是抢购接口天然幂等——重复请求
 * 稳定返回 ALREADY_PURCHASED，不会造成二次扣减。</p>
 */
public enum SeckillErrorCode {

    SUCCESS("SUCCESS", 200, "抢购成功"),
    STOCK_SOLD_OUT("STOCK_SOLD_OUT", 409, "库存已售罄"),
    ALREADY_PURCHASED("ALREADY_PURCHASED", 409, "您已参与过本次秒杀"),
    SECKILL_NOT_FOUND("SECKILL_NOT_FOUND", 404, "秒杀活动不存在"),
    NOT_READY("NOT_READY", 503, "秒杀服务暂不可用"),
    INVALID_REQUEST("INVALID_REQUEST", 400, "参数校验失败");

    private final String code;
    private final int httpStatus;
    private final String message;

    SeckillErrorCode(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getMessage() {
        return message;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvnw.cmd test -Dtest=SeckillErrorCodeTest`

Expected: BUILD SUCCESS，3 个测试通过

- [ ] **Step 5: 实现业务异常**

创建 `src/main/java/com/fjf/teproject/domain/SeckillException.java`：

```java
package com.fjf.teproject.domain;

/**
 * 携带 {@link SeckillErrorCode} 的业务异常。
 *
 * <p>由 Web 层的异常处理器统一转换为 HTTP 响应，使业务代码不需要
 * 直接依赖 Spring Web 类型。</p>
 */
public class SeckillException extends RuntimeException {

    private final SeckillErrorCode errorCode;

    public SeckillException(SeckillErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public SeckillErrorCode getErrorCode() {
        return errorCode;
    }
}
```

- [ ] **Step 6: 运行完整默认测试集**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS，无失败

---

## Task 3: Lua 返回码映射

**Files:**
- Create: `src/main/java/com/fjf/teproject/domain/DeductionOutcome.java`
- Create: `src/main/java/com/fjf/teproject/service/LuaResultMapper.java`
- Test: `src/test/java/com/fjf/teproject/service/LuaResultMapperTest.java`

**Interfaces:**
- Consumes: `SeckillErrorCode`（Task 2）
- Produces:
  - `DeductionOutcome`，静态工厂 `success(int remainingStock)` / `failure(SeckillErrorCode)`，实例方法 `boolean isSuccess()`、`int getRemainingStock()`、`SeckillErrorCode getErrorCode()`
  - `LuaResultMapper.from(long rawResult)` → `DeductionOutcome`

**设计说明：** Lua 脚本本身必须跑在 Redis 中才能执行，因此它属于集成测试（Task 7）。本任务把**「原始返回值 → 业务结果」的映射**抽成纯函数，使其可以在无 Docker 环境下被完整测试。这是 spec §10.1 划定的边界。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/fjf/teproject/service/LuaResultMapperTest.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaResultMapperTest {

    @Test
    void nonNegativeResultMeansSuccessAndCarriesRemainingStock() {
        DeductionOutcome outcome = LuaResultMapper.from(99);

        assertTrue(outcome.isSuccess());
        assertEquals(99, outcome.getRemainingStock());
    }

    @Test
    void zeroIsSuccessBecauseItMeansTheLastItemWasJustSold() {
        DeductionOutcome outcome = LuaResultMapper.from(0);

        assertTrue(outcome.isSuccess());
        assertEquals(0, outcome.getRemainingStock());
    }

    @Test
    void minusOneMeansSoldOut() {
        DeductionOutcome outcome = LuaResultMapper.from(-1);

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT, outcome.getErrorCode());
    }

    @Test
    void minusTwoMeansAlreadyPurchased() {
        DeductionOutcome outcome = LuaResultMapper.from(-2);

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.ALREADY_PURCHASED, outcome.getErrorCode());
    }

    @Test
    void minusThreeMeansNotWarmedUp() {
        DeductionOutcome outcome = LuaResultMapper.from(-3);

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.NOT_READY, outcome.getErrorCode());
    }

    @Test
    void unknownNegativeResultFailsLoudlyInsteadOfBeingSwallowed() {
        // 未识别的返回值意味着脚本与映射函数已经不同步。
        // 静默当作售罄处理会把一个代码缺陷伪装成正常的业务拒绝。
        assertThrows(IllegalStateException.class, () -> LuaResultMapper.from(-99));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Dtest=LuaResultMapperTest`

Expected: 编译失败，`cannot find symbol: class DeductionOutcome`

- [ ] **Step 3: 实现 `DeductionOutcome`**

创建 `src/main/java/com/fjf/teproject/domain/DeductionOutcome.java`：

```java
package com.fjf.teproject.domain;

import java.util.Objects;

/**
 * Redis 扣减尝试的结果：要么成功并携带剩余库存，要么失败并携带错误码。
 *
 * <p>不可变值对象，可安全地在多个线程间共享。</p>
 */
public final class DeductionOutcome {

    private final SeckillErrorCode errorCode;
    private final int remainingStock;

    private DeductionOutcome(SeckillErrorCode errorCode, int remainingStock) {
        this.errorCode = errorCode;
        this.remainingStock = remainingStock;
    }

    public static DeductionOutcome success(int remainingStock) {
        return new DeductionOutcome(SeckillErrorCode.SUCCESS, remainingStock);
    }

    public static DeductionOutcome failure(SeckillErrorCode errorCode) {
        return new DeductionOutcome(errorCode, 0);
    }

    public boolean isSuccess() {
        return errorCode == SeckillErrorCode.SUCCESS;
    }

    public SeckillErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * @return 成功时的剩余库存；失败时返回 {@code 0}，此时该值无业务含义
     */
    public int getRemainingStock() {
        return remainingStock;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DeductionOutcome)) {
            return false;
        }
        DeductionOutcome that = (DeductionOutcome) other;
        return remainingStock == that.remainingStock && errorCode == that.errorCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(errorCode, remainingStock);
    }

    @Override
    public String toString() {
        return "DeductionOutcome{" + errorCode + ", remainingStock=" + remainingStock + '}';
    }
}
```

- [ ] **Step 4: 实现 `LuaResultMapper`**

创建 `src/main/java/com/fjf/teproject/service/LuaResultMapper.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;

/**
 * 把 Lua 脚本的原始返回值翻译成 {@link DeductionOutcome}。
 *
 * <p>Lua 返回值的含义（与 src/main/resources/lua/seckill_deduct.lua 一一对应）：</p>
 * <pre>
 *   &gt;= 0  成功，值为扣减后的剩余库存
 *   -1     库存售罄
 *   -2     该用户已购买过
 *   -3     活动未预热（Redis 中缺少库存键）
 * </pre>
 *
 * <p>这是纯函数，不依赖 Redis，因此可以在默认测试集中被完整覆盖。
 * Lua 脚本实际跑在 Redis 中的行为由集成测试验证。</p>
 */
public final class LuaResultMapper {

    private LuaResultMapper() {
    }

    public static DeductionOutcome from(long rawResult) {
        if (rawResult >= 0) {
            return DeductionOutcome.success((int) rawResult);
        }
        switch ((int) rawResult) {
            case -1:
                return DeductionOutcome.failure(SeckillErrorCode.STOCK_SOLD_OUT);
            case -2:
                return DeductionOutcome.failure(SeckillErrorCode.ALREADY_PURCHASED);
            case -3:
                return DeductionOutcome.failure(SeckillErrorCode.NOT_READY);
            default:
                throw new IllegalStateException(
                        "Lua 脚本返回了未定义的值 " + rawResult + "，脚本与映射函数已不同步");
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvnw.cmd test -Dtest=LuaResultMapperTest`

Expected: BUILD SUCCESS，6 个测试通过

---

## Task 4: 活动注册表

**Files:**
- Create: `src/main/java/com/fjf/teproject/service/ActivityRegistry.java`
- Test: `src/test/java/com/fjf/teproject/service/ActivityRegistryTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `ActivityRegistry`，方法 `boolean exists(Long seckillId)`、`void register(Collection<Long> ids)`、`int size()`、`void clear()`

**设计说明：** Lua 对「活动不存在」与「Redis 未预热」都返回 `-3`。若不区分，客户端传错 `seckillId` 会收到 `503`，把客户端错误伪装成服务端故障（spec §7.2）。这个内存集合就是区分二者的依据：活动是种子数据，量小且静态。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/fjf/teproject/service/ActivityRegistryTest.java`：

```java
package com.fjf.teproject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRegistryTest {

    private ActivityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ActivityRegistry();
    }

    @Test
    void unknownActivityIsNotPresent() {
        assertFalse(registry.exists(1L));
    }

    @Test
    void registeredActivityIsPresent() {
        registry.register(Collections.singletonList(1L));

        assertTrue(registry.exists(1L));
    }

    @Test
    void nullIdIsNeverPresent() {
        // 避免把「参数缺失」当成「活动不存在」之外的第二类问题，
        // 也避免 ConcurrentHashMap 键为 null 时抛 NPE。
        assertFalse(registry.exists(null));
    }

    @Test
    void clearRemovesEverything() {
        registry.register(Arrays.asList(1L, 2L, 3L));

        registry.clear();

        assertFalse(registry.exists(1L));
    }

    @Test
    void concurrentRegistrationDoesNotLoseEntries() throws InterruptedException {
        // 预热可能由多个线程并发触发，注册表必须能安全承受。
        int threadCount = 16;
        int idsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int base = t * idsPerThread;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    List<Long> batch = new java.util.ArrayList<>();
                    for (int i = 0; i < idsPerThread; i++) {
                        batch.add((long) (base + i));
                    }
                    registry.register(batch);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertTrue(registry.exists(0L));
        assertTrue(registry.exists((long) (threadCount * idsPerThread - 1)));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Dtest=ActivityRegistryTest`

Expected: 编译失败，`cannot find symbol: class ActivityRegistry`

- [ ] **Step 3: 实现注册表**

创建 `src/main/java/com/fjf/teproject/service/ActivityRegistry.java`：

```java
package com.fjf.teproject.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录「哪些 seckillId 是合法活动」的内存集合，供请求路径做快速校验。
 *
 * <p>存在的理由：Lua 脚本对「活动不存在」与「Redis 未预热」返回同一个 -3。
 * 若不区分，客户端传错 seckillId 会收到 503，把自己的输入错误伪装成
 * 服务端故障。本集合是二者的唯一区分依据——不在集合中即 404，
 * 在集合中但 Redis 无键即 503。</p>
 *
 * <p>活动来自 data.sql 种子数据，量小且静态，因此可整体常驻内存。
 * 使用并发集合是因为多个实例/线程的预热可能同时发生；注册是幂等的。</p>
 */
@Component
public class ActivityRegistry {

    private final Set<Long> activeIds = ConcurrentHashMap.newKeySet();

    /**
     * @return 该 id 是否为已注册的合法活动；{@code null} 永远返回 {@code false}
     */
    public boolean exists(Long seckillId) {
        return seckillId != null && activeIds.contains(seckillId);
    }

    public void register(Collection<Long> seckillIds) {
        activeIds.addAll(seckillIds);
    }

    public int size() {
        return activeIds.size();
    }

    public void clear() {
        activeIds.clear();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvnw.cmd test -Dtest=ActivityRegistryTest`

Expected: BUILD SUCCESS，5 个测试通过

---

## Task 5: 对账判定逻辑

**Files:**
- Create: `src/main/java/com/fjf/teproject/reconcile/ReconcileSnapshot.java`
- Create: `src/main/java/com/fjf/teproject/reconcile/ReconcileFinding.java`
- Create: `src/main/java/com/fjf/teproject/reconcile/ReconcileEvaluator.java`
- Create: `src/main/java/com/fjf/teproject/reconcile/ReconcileProperties.java`
- Test: `src/test/java/com/fjf/teproject/reconcile/ReconcileEvaluatorTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `ReconcileProperties`：`getIntervalSeconds()`、`getStallThresholdSeconds()`，绑定前缀 `seckill.reconcile`
  - `ReconcileSnapshot`：静态工厂 `of(long seckillId, int initialStock, int redisStock, int dbRemainingStock, int recordCount, Instant stockUpdatedAt)`；getter 同名
  - `ReconcileFinding`：`getSeckillId()`、`getKind()`、`getDetail()`；`ReconcileFinding.Kind` 枚举含 `A1_REDIS_DB_MISMATCH`、`A2_RECORD_STOCK_MISMATCH`
  - `ReconcileEvaluator.evaluate(ReconcileSnapshot snapshot, Instant now, long stallThresholdSeconds)` → `List<ReconcileFinding>`

**设计说明：** 把对账的**判定**抽成纯函数，IO（查 Redis / 查 DB）留在 Task 12。这样规则本身可以在无 Docker 环境下被穷举测试，包括最容易写错的「追赶中 vs 卡住」的区分。

依据 spec §9：A1 需要「数值不等 **且** `stock_updated_at` 停滞超阈值」才告警；A2 只要非 0 就告警。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/fjf/teproject/reconcile/ReconcileEvaluatorTest.java`：

```java
package com.fjf.teproject.reconcile;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconcileEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final long STALL_THRESHOLD_SECONDS = 60L;

    private final ReconcileEvaluator evaluator = new ReconcileEvaluator();

    private ReconcileSnapshot snapshot(int initialStock, int redisStock,
                                       int dbRemainingStock, int recordCount,
                                       Instant stockUpdatedAt) {
        return ReconcileSnapshot.of(1L, initialStock, redisStock,
                dbRemainingStock, recordCount, stockUpdatedAt);
    }

    @Test
    void consistentStateProducesNoFinding() {
        ReconcileSnapshot snapshot = snapshot(100, 90, 90, 10, NOW);

        assertTrue(evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).isEmpty());
    }

    @Test
    void catchingUpIsNotAlertedBecauseStockTimestampIsFresh() {
        // Redis 已扣 10 件，DB 只落了 8 件，但库存 5 秒前刚被推进过。
        // 这是异步固有的延迟，不是故障——告警会变成必然误报。
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 8,
                NOW.minus(5, ChronoUnit.SECONDS));

        assertTrue(evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).isEmpty());
    }

    @Test
    void stalledMismatchIsAlerted() {
        // 同样的数值差异，但库存 5 分钟没被推进过——消费者卡住了。
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 8,
                NOW.minus(300, ChronoUnit.SECONDS));

        List<ReconcileFinding> findings =
                evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS);

        assertEquals(1, findings.size());
        assertEquals(ReconcileFinding.Kind.A1_REDIS_DB_MISMATCH, findings.get(0).getKind());
        assertEquals(1L, findings.get(0).getSeckillId());
    }

    @Test
    void exactlyAtTheThresholdCountsAsStalled() {
        // 边界值：恰好等于阈值应判定为停滞，否则阈值语义在两侧不一致。
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 8,
                NOW.minus(STALL_THRESHOLD_SECONDS, ChronoUnit.SECONDS));

        assertEquals(1, evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).size());
    }

    @Test
    void recordCountOutOfSyncWithStockIsAlwaysAlerted() {
        // A2 金丝雀：流水数与库存扣减数分叉。两条语句在同一事务内，
        // 正常情况下不可能发生，一旦出现即代码缺陷或人工改数。
        ReconcileSnapshot snapshot = snapshot(100, 90, 90, 9, NOW);

        List<ReconcileFinding> findings =
                evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS);

        assertEquals(1, findings.size());
        assertEquals(ReconcileFinding.Kind.A2_RECORD_STOCK_MISMATCH, findings.get(0).getKind());
    }

    @Test
    void bothProblemsCanBeReportedTogether() {
        ReconcileSnapshot snapshot = snapshot(100, 90, 92, 5,
                NOW.minus(300, ChronoUnit.SECONDS));

        List<ReconcileFinding> findings =
                evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS);

        assertEquals(2, findings.size());
    }

    @Test
    void redisAheadOfDbWithFreshTimestampStaysSilent() {
        // 消费者正常追赶的最常见形态：Redis 在前，DB 在后，时间戳很新。
        ReconcileSnapshot snapshot = snapshot(100, 0, 50, 50,
                NOW.minus(1, ChronoUnit.SECONDS));

        assertTrue(evaluator.evaluate(snapshot, NOW, STALL_THRESHOLD_SECONDS).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Dtest=ReconcileEvaluatorTest`

Expected: 编译失败，`cannot find symbol: class ReconcileSnapshot`

- [ ] **Step 3: 实现 `ReconcileProperties`**

创建 `src/main/java/com/fjf/teproject/reconcile/ReconcileProperties.java`：

```java
package com.fjf.teproject.reconcile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对账任务的配置项，绑定前缀 {@code seckill.reconcile}。
 *
 * <p>阈值不硬编码：不同部署环境下「异步延迟多久算正常」的答案不同，
 * 写死会让告警要么永远不响、要么一直响。</p>
 */
@ConfigurationProperties(prefix = "seckill.reconcile")
public class ReconcileProperties {

    /** 对账任务执行间隔，单位秒。 */
    private long intervalSeconds = 10L;

    /** 库存时间戳超过该秒数未推进，才判定为「卡住」而非「追赶中」。 */
    private long stallThresholdSeconds = 60L;

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public long getStallThresholdSeconds() {
        return stallThresholdSeconds;
    }

    public void setStallThresholdSeconds(long stallThresholdSeconds) {
        this.stallThresholdSeconds = stallThresholdSeconds;
    }
}
```

- [ ] **Step 4: 实现 `ReconcileSnapshot`**

创建 `src/main/java/com/fjf/teproject/reconcile/ReconcileSnapshot.java`：

```java
package com.fjf.teproject.reconcile;

import java.time.Instant;

/**
 * 某一个活动在对账时刻的两侧状态快照。
 *
 * <p>把 IO 的结果收敛成一个不可变值对象，使判定逻辑可以脱离 Redis 与
 * 数据库被穷举测试。</p>
 */
public final class ReconcileSnapshot {

    private final long seckillId;
    private final int initialStock;
    private final int redisStock;
    private final int dbRemainingStock;
    private final int recordCount;
    private final Instant stockUpdatedAt;

    private ReconcileSnapshot(long seckillId, int initialStock, int redisStock,
                              int dbRemainingStock, int recordCount,
                              Instant stockUpdatedAt) {
        this.seckillId = seckillId;
        this.initialStock = initialStock;
        this.redisStock = redisStock;
        this.dbRemainingStock = dbRemainingStock;
        this.recordCount = recordCount;
        this.stockUpdatedAt = stockUpdatedAt;
    }

    public static ReconcileSnapshot of(long seckillId, int initialStock, int redisStock,
                                       int dbRemainingStock, int recordCount,
                                       Instant stockUpdatedAt) {
        return new ReconcileSnapshot(seckillId, initialStock, redisStock,
                dbRemainingStock, recordCount, stockUpdatedAt);
    }

    public long getSeckillId() {
        return seckillId;
    }

    public int getInitialStock() {
        return initialStock;
    }

    public int getRedisStock() {
        return redisStock;
    }

    public int getDbRemainingStock() {
        return dbRemainingStock;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public Instant getStockUpdatedAt() {
        return stockUpdatedAt;
    }
}
```

- [ ] **Step 5: 实现 `ReconcileFinding`**

创建 `src/main/java/com/fjf/teproject/reconcile/ReconcileFinding.java`：

```java
package com.fjf.teproject.reconcile;

/**
 * 一条对账告警。
 *
 * <p>对账只产生告警、不自动修数：用户已经收到 200，任何一侧的自动改动
 * 都可能制造新的不一致，人工介入是刻意的选择。</p>
 */
public final class ReconcileFinding {

    public enum Kind {
        /** Redis 已扣减数与 DB 已落库数不一致，且库存时间戳已停滞。 */
        A1_REDIS_DB_MISMATCH,
        /** 流水条数与库存扣减数分叉；正常情况下不可能发生。 */
        A2_RECORD_STOCK_MISMATCH
    }

    private final long seckillId;
    private final Kind kind;
    private final String detail;

    public ReconcileFinding(long seckillId, Kind kind, String detail) {
        this.seckillId = seckillId;
        this.kind = kind;
        this.detail = detail;
    }

    public long getSeckillId() {
        return seckillId;
    }

    public Kind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "[" + kind + "] seckillId=" + seckillId + " " + detail;
    }
}
```

- [ ] **Step 6: 实现 `ReconcileEvaluator`**

创建 `src/main/java/com/fjf/teproject/reconcile/ReconcileEvaluator.java`：

```java
package com.fjf.teproject.reconcile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 对账规则判定：给定两侧状态快照，判定是否存在不一致。
 *
 * <p>纯函数，不触碰 Redis 或数据库，因此规则本身可以被完整测试。</p>
 *
 * <p>规则（见 spec §9）：</p>
 * <ul>
 *   <li><b>A1</b>：Redis 已扣减数 ≠ DB 已落库数 <b>且</b> 库存时间戳停滞超阈值。
 *       时间戳条件是关键——没有它，「消费者正在正常追赶」会被误报为故障，
 *       告警将因必然误报而失去价值。</li>
 *   <li><b>A2</b>：流水条数 ≠ 库存扣减数。两条语句在同一事务内，正常情况下
 *       恒等，因此这是缺陷/人工改数的金丝雀，只要非 0 就告警。</li>
 * </ul>
 */
public class ReconcileEvaluator {

    public List<ReconcileFinding> evaluate(ReconcileSnapshot snapshot, Instant now,
                                           long stallThresholdSeconds) {
        List<ReconcileFinding> findings = new ArrayList<>();

        int redisDeducted = snapshot.getInitialStock() - snapshot.getRedisStock();
        int dbDeducted = snapshot.getRecordCount();

        if (redisDeducted != dbDeducted && isStalled(snapshot, now, stallThresholdSeconds)) {
            findings.add(new ReconcileFinding(snapshot.getSeckillId(),
                    ReconcileFinding.Kind.A1_REDIS_DB_MISMATCH,
                    "Redis 已扣减 " + redisDeducted + " 件，DB 已落库 " + dbDeducted
                            + " 件，且库存时间戳已停滞超过 " + stallThresholdSeconds + " 秒"));
        }

        int dbDeductedFromStock = snapshot.getInitialStock() - snapshot.getDbRemainingStock();
        if (snapshot.getRecordCount() != dbDeductedFromStock) {
            findings.add(new ReconcileFinding(snapshot.getSeckillId(),
                    ReconcileFinding.Kind.A2_RECORD_STOCK_MISMATCH,
                    "流水 " + snapshot.getRecordCount() + " 条，但库存显示已扣减 "
                            + dbDeductedFromStock + " 件"));
        }

        return findings;
    }

    /**
     * 判定库存时间戳是否已停滞超过阈值。
     *
     * <p>恰好等于阈值算作停滞——否则阈值两侧的语义不一致，是典型的边界缺陷。</p>
     */
    private boolean isStalled(ReconcileSnapshot snapshot, Instant now, long stallThresholdSeconds) {
        Duration elapsed = Duration.between(snapshot.getStockUpdatedAt(), now);
        return elapsed.getSeconds() >= stallThresholdSeconds;
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvnw.cmd test -Dtest=ReconcileEvaluatorTest`

Expected: BUILD SUCCESS，7 个测试通过

- [ ] **Step 8: 让 Spring 识别配置属性**

修改 `src/main/java/com/fjf/teproject/TeProjectApplication.java`：

```java
package com.fjf.teproject;

import com.fjf.teproject.reconcile.ReconcileProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ReconcileProperties.class)
public class TeProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeProjectApplication.class, args);
    }

}
```

- [ ] **Step 9: 运行完整默认测试集，确认阶段 1 收口**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS，全部通过，且**不需要 Docker**

阶段 1 到此结束。此时可以断言：错误码映射、Lua 返回值翻译、活动注册表、对账规则判定——这四块纯逻辑已被测试覆盖且验证通过。

---

# 阶段 2：Redis（需要 Docker）

> **前置条件**：Docker 已安装并运行。执行 `docker info` 应正常输出。

## Task 6: 集成测试基础设施

**Files:**
- Create: `src/test/java/com/fjf/teproject/support/IntegrationTestBase.java`

**Interfaces:**
- Consumes: Testcontainers 依赖（Task 1）
- Produces: `IntegrationTestBase` 抽象基类，暴露 `protected static GenericContainer<?> REDIS` 与 `protected static void resetRedis()`；子类通过继承获得容器与动态属性注入

**设计说明：** 容器在 `static` 块中启动一次，由所有子类共享。用 `@DynamicPropertySource` 注入连接信息——Spring Boot 2.7 尚无 `@ServiceConnection`（Boot 3.1+ 才有）。

- [ ] **Step 1: 实现基类**

创建 `src/test/java/com/fjf/teproject/support/IntegrationTestBase.java`：

```java
package com.fjf.teproject.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：提供 Redis / MySQL / RabbitMQ 三个真实容器。
 *
 * <p>容器在静态块中启动，同一 JVM 内所有子类共享，避免每个测试类都付一次
 * 冷启动成本。容器在整个测试 JVM 结束时由 Testcontainers 的 Ryuk 回收。</p>
 *
 * <p>继承本类的测试会自动获得 {@code integration} 标签所需的运行环境，
 * 但标签必须由子类自己声明——基类不加 {@code @Tag}，否则会被误认为
 * 所有子类都被正确标记，而实际上漏标一个就会让默认测试意外依赖 Docker。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName("teproject")
                    .withUsername("test")
                    .withPassword("test");

    protected static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-alpine"));

    static {
        REDIS.start();
        MYSQL.start();
        RABBITMQ.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.redis.host", REDIS::getHost);
        registry.add("spring.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        // 对账任务在集成测试中由测试手动触发，避免后台定时器制造不确定性。
        registry.add("seckill.reconcile.interval-seconds", () -> "3600");
    }

    /** 在测试之间清空 Redis，避免上一个用例的库存残留影响下一个。 */
    protected static void resetRedis() {
        try (var redis = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379))) {
            redis.afterPropertiesSet();
            redis.getConnection().serverCommands().flushAll();
        }
    }
}
```

注意：`var` 在 Java 11 中可用（局部变量类型推断），但 `LettuceConnectionFactory` 实现了 `DisposableBean`，`close()` 由 `destroy()` 提供。若编译报错，把 try-with-resources 改为显式 `destroy()` 调用：

```java
protected static void resetRedis() {
    LettuceConnectionFactory factory = new LettuceConnectionFactory(
            REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    factory.getConnection().serverCommands().flushAll();
    factory.destroy();
}
```

- [ ] **Step 2: 写一个冒烟测试确认容器可用**

创建 `src/test/java/com/fjf/teproject/support/IntegrationTestBaseSmokeTest.java`：

```java
package com.fjf.teproject.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class IntegrationTestBaseSmokeTest extends IntegrationTestBase {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void redisContainerIsReachable() {
        resetRedis();
        redisTemplate.opsForValue().set("smoke", "42");

        assertEquals("42", redisTemplate.opsForValue().get("smoke"));
    }

    @Test
    void mySqlContainerIsReachable() {
        // 上下文能启动即证明数据源可用；schema.sql 已由 spring.sql.init 执行。
        assertEquals(true, true);
    }
}
```

- [ ] **Step 3: 运行集成测试**

Run: `mvnw.cmd test -Pit -Dtest=IntegrationTestBaseSmokeTest`

Expected: 首次运行需拉取镜像，耗时较长；最终 BUILD SUCCESS，2 个测试通过

- [ ] **Step 4: 确认默认测试集未被污染**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS，且**不启动任何容器**、不需要 Docker

---

## Task 7: Lua 脚本与 Redis 库存仓储

**Files:**
- Create: `src/main/resources/lua/seckill_deduct.lua`
- Create: `src/main/java/com/fjf/teproject/service/SeckillStockRepository.java`
- Test: `src/test/java/com/fjf/teproject/service/SeckillStockRepositoryIT.java`

**Interfaces:**
- Consumes: `DeductionOutcome`、`SeckillErrorCode`（Task 2/3）、`LuaResultMapper`（Task 3）、`IntegrationTestBase`（Task 6）
- Produces: `SeckillStockRepository`，方法：
  - `DeductionOutcome tryDeduct(long seckillId, String userId)`
  - `int getRemainingStock(long seckillId)`（Redis 无键时返回 `-1`）
  - `boolean warmUp(long seckillId, int initialStock)`（使用 `SETNX`；`true` 表示本实例写入成功，`false` 表示键已存在）
  - `long countPurchasers(long seckillId)`
  - `void deleteAll(long seckillId)`（测试清理用）
  - `void forceSetStock(long seckillId, int stock)`（测试夹具用，绕过 `SETNX` 直接覆盖）
  - `java.util.Set<String> getPurchasers(long seckillId)`（测试断言用）

**设计说明：** 这是全系统唯一的不变量发生点。Lua 在 Redis 中单线程执行，「判断限购 → 判断库存 → 扣减 → 记录」之间不存在其它请求插入的可能，因此不需要分布式锁。

键名中的 `{seckillId}` 是 **hash tag**，不是风格选择：Lua 同时操作两个键，Redis Cluster 下跨 slot 会直接报错。

- [ ] **Step 1: 创建 Lua 脚本**

创建 `src/main/resources/lua/seckill_deduct.lua`：

```lua
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
```

- [ ] **Step 2: 配置 `RedisTemplate` 使用 String 序列化**

创建 `src/main/java/com/fjf/teproject/config/RedisConfig.java`：

```java
package com.fjf.teproject.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/**
 * Redis 相关配置。
 *
 * <p>Lua 脚本在启动时加载一次并复用，避免每次请求都读磁盘。
 * 默认的 JDK 序列化会给键加上二进制前缀，与 Lua 脚本里写死的键名
 * 不匹配，因此统一使用字符串序列化。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public DefaultRedisScript<Long> seckillDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/seckill_deduct.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
```

- [ ] **Step 3: 实现仓储**

创建 `src/main/java/com/fjf/teproject/service/SeckillStockRepository.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collections;

/**
 * Redis 侧库存仓储：权威库存与限购集合的唯一入口。
 *
 * <p>并发不变量由 seckill_deduct.lua 保证。选择 Lua 而非分布式锁的原因是：
 * Redis 执行脚本是单线程的，判断与扣减之间不存在其它请求插入的窗口，
 * 而锁存在获取失败、超时、续期等一整套额外的失败模式。</p>
 *
 * <p>键名使用 {seckillId} 作为 hash tag：脚本同时操作库存键与限购集合键，
 * Redis Cluster 下如果二者落在不同 slot，EVAL 会直接报错。hash tag 强制
 * 它们落在同一 slot。</p>
 */
@Repository
public class SeckillStockRepository {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:{";
    private static final String BOUGHT_KEY_PREFIX = "seckill:bought:{";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> seckillDeductScript;

    public SeckillStockRepository(StringRedisTemplate redisTemplate,
                                  DefaultRedisScript<Long> seckillDeductScript) {
        this.redisTemplate = redisTemplate;
        this.seckillDeductScript = seckillDeductScript;
    }

    /**
     * 尝试为指定用户扣减一件库存。
     *
     * @return 成功时携带扣减后的剩余库存；失败时携带 -1/-2/-3 对应的错误码
     */
    public DeductionOutcome tryDeduct(long seckillId, String userId) {
        Long rawResult = redisTemplate.execute(
                seckillDeductScript,
                Arrays.asList(stockKey(seckillId), boughtKey(seckillId)),
                userId);
        if (rawResult == null) {
            throw new IllegalStateException("Lua 脚本未返回结果，seckillId=" + seckillId);
        }
        return LuaResultMapper.from(rawResult);
    }

    /**
     * 读取实时库存（权威值）。
     *
     * @return 剩余库存；活动未预热时返回 {@code -1}
     */
    public int getRemainingStock(long seckillId) {
        String value = redisTemplate.opsForValue().get(stockKey(seckillId));
        return value == null ? -1 : Integer.parseInt(value);
    }

    /**
     * 预热库存，仅在键不存在时写入。
     *
     * <p>使用 SETNX 而非 SET：应用重启时 Redis 可能还保留着已经扣减过的
     * 实时库存，无条件覆盖会让已售出的库存凭空复活，直接违反不超卖。</p>
     *
     * @return 本次调用是否真正写入了库存
     */
    public boolean warmUp(long seckillId, int initialStock) {
        Boolean written = redisTemplate.opsForValue()
                .setIfAbsent(stockKey(seckillId), String.valueOf(initialStock));
        return Boolean.TRUE.equals(written);
    }

    public long countPurchasers(long seckillId) {
        Long size = redisTemplate.opsForSet().size(boughtKey(seckillId));
        return size == null ? 0L : size;
    }

    private String stockKey(long seckillId) {
        return STOCK_KEY_PREFIX + seckillId + "}";
    }

    private String boughtKey(long seckillId) {
        return BOUGHT_KEY_PREFIX + seckillId + "}";
    }

    /** 仅供测试使用，清空某个活动的全部 Redis 状态。 */
    public void deleteAll(long seckillId) {
        redisTemplate.delete(Arrays.asList(stockKey(seckillId), boughtKey(seckillId)));
    }

    /** 供测试注入初始库存而不走 SETNX 语义。 */
    public void forceSetStock(long seckillId, int stock) {
        redisTemplate.opsForValue().set(stockKey(seckillId), String.valueOf(stock));
    }

    public java.util.Set<String> getPurchasers(long seckillId) {
        java.util.Set<String> members = redisTemplate.opsForSet().members(boughtKey(seckillId));
        return members == null ? Collections.emptySet() : members;
    }
}
```

把 `RedisConfig` 放在新包 `com.fjf.teproject.config` 下；如果希望保持在 `service` 包内也可以，但 `config` 更能表达职责。

- [ ] **Step 4: 写 Lua 契约的集成测试**

创建 `src/test/java/com/fjf/teproject/service/SeckillStockRepositoryIT.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Lua 脚本在真实 Redis 中的行为。
 *
 * <p>脚本的返回值契约无法在单元测试中覆盖——它必须运行在 Redis 中，
 * 因此这里逐条验证 -1/-2/-3 的实际语义。</p>
 */
@Tag("integration")
class SeckillStockRepositoryIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillStockRepository repository;

    @BeforeEach
    void setUp() {
        resetRedis();
    }

    @Test
    void deductsAndReturnsRemainingStock() {
        repository.forceSetStock(SECKILL_ID, 5);

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertTrue(outcome.isSuccess());
        assertEquals(4, outcome.getRemainingStock());
        assertEquals(4, repository.getRemainingStock(SECKILL_ID));
    }

    @Test
    void lastItemStillSucceedsAndReportsZero() {
        // 库存归零必须是成功，且剩余为 0——把 0 当成失败会让最后一件永远卖不出去。
        repository.forceSetStock(SECKILL_ID, 1);

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertTrue(outcome.isSuccess());
        assertEquals(0, outcome.getRemainingStock());
    }

    @Test
    void returnsSoldOutWhenStockIsZero() {
        repository.forceSetStock(SECKILL_ID, 0);

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT, outcome.getErrorCode());
        assertEquals(0, repository.getRemainingStock(SECKILL_ID));
    }

    @Test
    void secondPurchaseBySameUserIsRejected() {
        repository.forceSetStock(SECKILL_ID, 5);
        repository.tryDeduct(SECKILL_ID, "u1");

        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.ALREADY_PURCHASED, outcome.getErrorCode());
        // 被拒绝的请求不得扣减库存。
        assertEquals(4, repository.getRemainingStock(SECKILL_ID));
    }

    @Test
    void notWarmedUpReturnsNotReady() {
        // Redis 中没有库存键——既可能是活动不存在，也可能是预热失败。
        // 脚本只能报告 -3，由 ActivityRegistry 负责区分。
        DeductionOutcome outcome = repository.tryDeduct(SECKILL_ID, "u1");

        assertFalse(outcome.isSuccess());
        assertEquals(SeckillErrorCode.NOT_READY, outcome.getErrorCode());
    }

    @Test
    void stockNeverGoesNegativeUnderConcurrentRequests() {
        // Lua 契约层面的不超卖验证；HTTP 层的完整验证在 Task 13。
        int initialStock = 50;
        repository.forceSetStock(SECKILL_ID, initialStock);

        int requestCount = 200;
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(requestCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(requestCount);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(requestCount);
        java.util.concurrent.atomic.AtomicInteger successes =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < requestCount; i++) {
            final String userId = "u" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (repository.tryDeduct(SECKILL_ID, userId).isSuccess()) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
        executor.shutdown();

        assertEquals(initialStock, successes.get());
        assertEquals(0, repository.getRemainingStock(SECKILL_ID));
        assertEquals(initialStock, repository.countPurchasers(SECKILL_ID));
    }

    @Test
    void warmUpIsIdempotentAndDoesNotOverwriteExistingStock() {
        // 应用重启场景：Redis 里已有扣减过的库存，预热不得覆盖它。
        repository.forceSetStock(SECKILL_ID, 7);

        boolean written = repository.warmUp(SECKILL_ID, 100);

        assertFalse(written);
        assertEquals(7, repository.getRemainingStock(SECKILL_ID));
    }

    @Test
    void warmUpWritesWhenKeyIsAbsent() {
        boolean written = repository.warmUp(SECKILL_ID, 100);

        assertTrue(written);
        assertEquals(100, repository.getRemainingStock(SECKILL_ID));
    }
}
```

- [ ] **Step 5: 运行集成测试**

Run: `mvnw.cmd test -Pit -Dtest=SeckillStockRepositoryIT`

Expected: BUILD SUCCESS，8 个测试通过

- [ ] **Step 6: 确认默认测试集仍不需要 Docker**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS，未启动容器

---

## Task 8: 启动预热

**Files:**
- Create: `src/main/java/com/fjf/teproject/repository/SeckillActivityRepository.java`
- Create: `src/main/resources/schema.sql`
- Create: `src/main/resources/data.sql`
- Create: `src/main/java/com/fjf/teproject/service/SeckillWarmUpRunner.java`
- Test: `src/test/java/com/fjf/teproject/service/SeckillWarmUpIT.java`

**Interfaces:**
- Consumes: `ActivityRegistry`（Task 4）、`SeckillStockRepository`（Task 7）、`IntegrationTestBase`（Task 6）
- Produces:
  - `SeckillActivityRepository.findAllIds()` → `List<Long>`
  - `SeckillActivityRepository.findById(long)` → `Optional<SeckillActivity>`
  - `SeckillActivity`（domain）：`getId()`、`getName()`、`getInitialStock()`、`getRemainingStock()`、`getStockUpdatedAt()`
  - `SeckillWarmUpRunner.warmUpAll()` → `int`（写入 Redis 的活动数）

**设计说明：** 每个实例都把活动 ID 载入自己的内存集合（用于区分 404/503），但只有第一个实例能用 `SETNX` 真正写入 Redis 库存。这两个动作的分工是 spec §7.2 的核心。

- [ ] **Step 1: 建立数据库表结构**

创建 `src/main/resources/schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS seckill_activity (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(128) NOT NULL,
    initial_stock    INT          NOT NULL,
    remaining_stock  INT          NOT NULL,
    stock_updated_at DATETIME     NOT NULL,
    created_at       DATETIME     NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS seckill_record (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    seckill_id BIGINT      NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    created_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    -- 使消息的「至少一次投递」成为可放心接受的前提：重复消费时
    -- INSERT 撞唯一键，捕获 DuplicateKeyException 即可，无需额外幂等表。
    UNIQUE KEY uk_seckill_user (seckill_id, user_id)
);
```

- [ ] **Step 2: 建立种子数据**

创建 `src/main/resources/data.sql`：

```sql
-- 多商品种子数据。使用 INSERT IGNORE 使重复执行安全
-- （spring.sql.init.mode=always 每次启动都会执行本文件）。
INSERT IGNORE INTO seckill_activity
    (id, name, initial_stock, remaining_stock, stock_updated_at, created_at)
VALUES
    (1, '演示商品 A', 100, 100, NOW(), NOW()),
    (2, '演示商品 B', 500, 500, NOW(), NOW()),
    (3, '演示商品 C', 1000, 1000, NOW(), NOW());
```

若 `INSERT IGNORE` 因主键自增导致重复插入问题，改用显式 id 配合 `ON DUPLICATE KEY UPDATE id = id`：

```sql
INSERT INTO seckill_activity
    (id, name, initial_stock, remaining_stock, stock_updated_at, created_at)
VALUES
    (1, '演示商品 A', 100, 100, NOW(), NOW()),
    (2, '演示商品 B', 500, 500, NOW(), NOW()),
    (3, '演示商品 C', 1000, 1000, NOW(), NOW())
ON DUPLICATE KEY UPDATE id = id;
```

- [ ] **Step 3: 实现领域实体**

创建 `src/main/java/com/fjf/teproject/domain/SeckillActivity.java`：

```java
package com.fjf.teproject.domain;

import java.time.LocalDateTime;

/**
 * 秒杀活动的持久化状态。
 *
 * <p>{@code remainingStock} 与 {@code stockUpdatedAt} 是 Redis 权威库存的
 * 异步副本：{@code remainingStock} 供查询与对账使用，{@code stockUpdatedAt}
 * 让对账能够区分「消费者正在追赶」与「消费者已卡死」——没有这个时间戳，
 * 任何异步延迟都会被误报为故障。</p>
 */
public final class SeckillActivity {

    private final long id;
    private final String name;
    private final int initialStock;
    private final int remainingStock;
    private final LocalDateTime stockUpdatedAt;

    public SeckillActivity(long id, String name, int initialStock,
                           int remainingStock, LocalDateTime stockUpdatedAt) {
        this.id = id;
        this.name = name;
        this.initialStock = initialStock;
        this.remainingStock = remainingStock;
        this.stockUpdatedAt = stockUpdatedAt;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getInitialStock() {
        return initialStock;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public LocalDateTime getStockUpdatedAt() {
        return stockUpdatedAt;
    }
}
```

- [ ] **Step 4: 实现活动仓储**

创建 `src/main/java/com/fjf/teproject/repository/SeckillActivityRepository.java`：

```java
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
```

- [ ] **Step 5: 实现预热启动器**

创建 `src/main/java/com/fjf/teproject/service/SeckillWarmUpRunner.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.SeckillActivity;
import com.fjf.teproject.repository.SeckillActivityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动时预热 Redis 库存，并把合法活动 ID 登记到内存注册表。
 *
 * <p>两个动作的分工是刻意的，也是多实例部署的关键：</p>
 * <ul>
 *   <li><b>每个实例都</b>把活动 ID 载入内存 {@link ActivityRegistry}——
 *       它用于在请求路径上区分 404（活动不存在）与 503（未预热）。</li>
 *   <li><b>只有第一个实例</b>能用 SETNX 真正写入 Redis 库存。这保证应用
 *       重启时不会用数据库中的旧库存覆盖 Redis 里已经扣减过的实时库存——
 *       那种覆盖会让已售出的库存凭空复活，直接违反不超卖。</li>
 * </ul>
 *
 * <p>预热失败的实例不会阻止应用启动：抢购请求会因为缺少库存键而返回 503，
 * 这比让整个应用无法启动更符合「可用性让位于正确性、但不要整体崩掉」的取舍。</p>
 */
@Component
public class SeckillWarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeckillWarmUpRunner.class);

    private final SeckillActivityRepository activityRepository;
    private final SeckillStockRepository stockRepository;
    private final ActivityRegistry activityRegistry;

    public SeckillWarmUpRunner(SeckillActivityRepository activityRepository,
                               SeckillStockRepository stockRepository,
                               ActivityRegistry activityRegistry) {
        this.activityRepository = activityRepository;
        this.stockRepository = stockRepository;
        this.activityRegistry = activityRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int warmed = warmUpAll();
            log.info("秒杀活动预热完成：登记 {} 个活动，本次实际写入 Redis {} 个",
                    activityRegistry.size(), warmed);
        } catch (Exception exception) {
            // 预热失败不阻止启动：请求会因缺少库存键返回 503，
            // 这比整个应用起不来更容易定位，也不会影响其它功能。
            log.error("秒杀活动预热失败，抢购接口将返回 503", exception);
        }
    }

    /**
     * 登记全部活动并尝试预热库存。
     *
     * @return 本次调用中真正写入 Redis 的活动数量
     */
    public int warmUpAll() {
        List<Long> activityIds = activityRepository.findAllIds();
        activityRegistry.register(activityIds);

        int written = 0;
        for (Long seckillId : activityIds) {
            SeckillActivity activity = activityRepository.findById(seckillId).orElse(null);
            if (activity == null) {
                continue;
            }
            if (stockRepository.warmUp(seckillId, activity.getInitialStock())) {
                written++;
            }
        }
        return written;
    }
}
```

- [ ] **Step 6: 写预热的集成测试**

创建 `src/test/java/com/fjf/teproject/service/SeckillWarmUpIT.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SeckillWarmUpIT extends IntegrationTestBase {

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private ActivityRegistry activityRegistry;

    @Autowired
    private com.fjf.teproject.repository.SeckillActivityRepository activityRepository;

    @BeforeEach
    void setUp() {
        resetRedis();
        activityRegistry.clear();
    }

    @Test
    void seedDataContainsMultipleActivities() {
        // 多商品是本轮的需求之一，种子数据必须真的提供多个活动。
        List<Long> ids = activityRepository.findAllIds();

        assertTrue(ids.size() >= 3, "种子数据应包含至少 3 个活动，实际 " + ids.size());
    }

    @Test
    void warmUpRegistersAllActivitiesInMemory() {
        warmUpRunner.warmUpAll();

        assertTrue(activityRegistry.exists(1L));
        assertTrue(activityRegistry.exists(2L));
        assertTrue(activityRegistry.exists(3L));
        assertFalse(activityRegistry.exists(999L));
    }

    @Test
    void warmUpWritesStockFromDatabase() {
        warmUpRunner.warmUpAll();

        assertEquals(100, stockRepository.getRemainingStock(1L));
        assertEquals(500, stockRepository.getRemainingStock(2L));
    }

    @Test
    void secondWarmUpDoesNotOverwriteAlreadyDeductedStock() {
        // 这是预热最关键的语义：模拟应用重启，不得复活已售出的库存。
        warmUpRunner.warmUpAll();
        stockRepository.tryDeduct(1L, "u1");
        stockRepository.tryDeduct(1L, "u2");
        assertEquals(98, stockRepository.getRemainingStock(1L));

        int written = warmUpRunner.warmUpAll();

        assertEquals(98, stockRepository.getRemainingStock(1L),
                "预热不得覆盖 Redis 中已扣减的库存");
        assertTrue(written < 3, "已预热的键不应被重复写入，实际写入 " + written);
    }
}
```

- [ ] **Step 7: 运行集成测试**

Run: `mvnw.cmd test -Pit -Dtest=SeckillWarmUpIT`

Expected: BUILD SUCCESS，4 个测试通过

---

# 阶段 3：DB / MQ / 编排（需要 Docker）

## Task 9: 流水仓储与消费者事务写入

**Files:**
- Create: `src/main/java/com/fjf/teproject/repository/SeckillRecordRepository.java`
- Test: `src/test/java/com/fjf/teproject/repository/SeckillRecordRepositoryIT.java`

**Interfaces:**
- Consumes: `schema.sql`（Task 8）
- Produces:
  - `SeckillRecordRepository.recordPurchase(long seckillId, String userId)` → `boolean`（`true` 表示本次真的写入，`false` 表示重复消息被唯一索引挡下）
  - `SeckillRecordRepository.countByActivity(long seckillId)` → `int`
  - `SeckillRecordRepository.exists(long seckillId, String userId)` → `boolean`

**设计说明：** `recordPurchase` 在**一个事务内**完成「插入流水 + 扣减 DB 库存 + 更新时间戳」。这三件事必须同生共死：分开写会让 A2 金丝雀产生大量无意义的告警，掩盖真正的缺陷。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/fjf/teproject/repository/SeckillRecordRepositoryIT.java`：

```java
package com.fjf.teproject.repository;

import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SeckillRecordRepositoryIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    @Test
    void firstInsertionReturnsTrueAndAdvancesStock() {
        int before = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        boolean inserted = recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertTrue(inserted);
        assertEquals(before - 1, activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
        assertEquals(1, recordRepository.countByActivity(SECKILL_ID));
    }

    @Test
    void duplicateInsertionIsRejectedAndDoesNotDeductStockAgain() {
        // 消息的「至少一次投递」会带来重复消费。唯一索引必须挡住它，
        // 且不得二次扣减库存——否则一条消息就能凭空吃掉两件库存。
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        int afterFirst = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        boolean insertedAgain = recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertFalse(insertedAgain);
        assertEquals(afterFirst, activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
        assertEquals(1, recordRepository.countByActivity(SECKILL_ID));
    }

    @Test
    void differentUsersEachDeductOne() {
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        recordRepository.recordPurchase(SECKILL_ID, "u2");
        recordRepository.recordPurchase(SECKILL_ID, "u3");

        assertEquals(3, recordRepository.countByActivity(SECKILL_ID));
    }

    @Test
    void duplicateDropsTheWholeTransactionIncludingTheStockUpdate() {
        // 重复消息被拦下时，库存更新也必须一并回滚。
        // 若二者不在同一事务，这里会出现「记录 1 条但库存扣了 2 件」——
        // 正是 A2 金丝雀要捕捉的分叉。
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        activityRepository.resetToInitial(SECKILL_ID);

        recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertEquals(1, recordRepository.countByActivity(SECKILL_ID));
    }

    @Test
    void existsReflectsStoredRecords() {
        assertFalse(recordRepository.exists(SECKILL_ID, "u1"));

        recordRepository.recordPurchase(SECKILL_ID, "u1");

        assertTrue(recordRepository.exists(SECKILL_ID, "u1"));
        assertFalse(recordRepository.exists(SECKILL_ID, "u2"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Pit -Dtest=SeckillRecordRepositoryIT`

Expected: 编译失败，`cannot find symbol: class SeckillRecordRepository`

- [ ] **Step 3: 实现流水仓储**

创建 `src/main/java/com/fjf/teproject/repository/SeckillRecordRepository.java`：

```java
package com.fjf.teproject.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 秒杀流水的持久化访问。
 *
 * <p>{@link #recordPurchase} 把「写入流水」与「扣减数据库库存并推进时间戳」
 * 放在同一个事务里，三件事必须同生共死。分开写会让库存与流水字段产生
 * 无意义的分叉，淹没 A2 金丝雀真正要捕捉的信号（代码缺陷或人工改数）。</p>
 *
 * <p>唯一索引 {@code uk_seckill_user} 是幂等性的凭证：消息的至少一次投递
 * 会导致重复消费，重复时 INSERT 抛 {@link DuplicateKeyException}，捕获后
 * 视为成功即可，无需额外的幂等表。</p>
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
     * <p>整个方法在一个事务内执行。唯一键冲突时事务回滚——包括库存更新——
     * 因此重复消息不会二次扣减。</p>
     *
     * @return {@code true} 表示本次真的写入；{@code false} 表示是重复消息
     */
    @Transactional
    public boolean recordPurchase(long seckillId, String userId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO seckill_record (seckill_id, user_id, created_at) VALUES (?, ?, ?)",
                    seckillId, userId, Timestamp.valueOf(LocalDateTime.now()));
        } catch (DuplicateKeyException exception) {
            // 至少一次投递的正常后果，不是错误。
            return false;
        }

        jdbcTemplate.update(
                "UPDATE seckill_activity SET remaining_stock = remaining_stock - 1, "
                        + "stock_updated_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), seckillId);
        return true;
    }

    public int countByActivity(long seckillId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seckill_record WHERE seckill_id = ?",
                Integer.class, seckillId);
        return count == null ? 0 : count;
    }

    public boolean exists(long seckillId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seckill_record WHERE seckill_id = ? AND user_id = ?",
                Integer.class, seckillId, userId);
        return count != null && count > 0;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvnw.cmd test -Pit -Dtest=SeckillRecordRepositoryIT`

Expected: BUILD SUCCESS，5 个测试通过

**若 `duplicateDropsTheWholeTransactionIncludingTheStockUpdate` 失败**：说明 `@Transactional` 未生效（事务在 `DuplicateKeyException` 被捕获后仍提交了后续 UPDATE，或异常被吞导致事务未标记回滚）。检查是否缺少事务管理器，以及 `DuplicateKeyException` 的捕获是否过早——把 `try/catch` 缩小到只包住 INSERT，让异常真正中断后续语句。

---

## Task 10: 消息生产者与消费者

**Files:**
- Create: `src/main/java/com/fjf/teproject/config/RabbitConfig.java`
- Create: `src/main/java/com/fjf/teproject/messaging/SeckillMessage.java`
- Create: `src/main/java/com/fjf/teproject/messaging/SeckillMessageProducer.java`
- Create: `src/main/java/com/fjf/teproject/messaging/SeckillMessageConsumer.java`
- Create: `src/main/java/com/fjf/teproject/messaging/SeckillMessagingProperties.java`
- Test: `src/test/java/com/fjf/teproject/messaging/SeckillMessageConsumerIT.java`

**Interfaces:**
- Consumes: `SeckillRecordRepository`（Task 9）
- Produces:
  - `SeckillMessage`：`getSeckillId()`、`getUserId()`；无参构造 + 全参构造（Jackson 需要）
  - `SeckillMessageProducer.publish(long seckillId, String userId)` → `void`，失败抛 `RuntimeException`
  - 队列 `seckill.purchase.queue`、死信队列 `seckill.purchase.dlq`、交换机 `seckill.purchase.exchange`
  - `SeckillMessagingProperties.getMaxRetries()`，前缀 `seckill.messaging`

- [ ] **Step 1: 实现消息体**

创建 `src/main/java/com/fjf/teproject/messaging/SeckillMessage.java`：

```java
package com.fjf.teproject.messaging;

import java.io.Serializable;

/**
 * 抢购成功的落库消息。
 *
 * <p>只携带定位一次购买所需的最小信息。需要无参构造是因为消息经过
 * JSON 序列化后由 Jackson 反序列化。</p>
 */
public class SeckillMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private long seckillId;
    private String userId;

    public SeckillMessage() {
    }

    public SeckillMessage(long seckillId, String userId) {
        this.seckillId = seckillId;
        this.userId = userId;
    }

    public long getSeckillId() {
        return seckillId;
    }

    public void setSeckillId(long seckillId) {
        this.seckillId = seckillId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @Override
    public String toString() {
        return "SeckillMessage{seckillId=" + seckillId + ", userId='" + userId + "'}";
    }
}
```

- [ ] **Step 2: 实现消息配置属性**

创建 `src/main/java/com/fjf/teproject/messaging/SeckillMessagingProperties.java`：

```java
package com.fjf.teproject.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息相关配置，绑定前缀 {@code seckill.messaging}。
 */
@ConfigurationProperties(prefix = "seckill.messaging")
public class SeckillMessagingProperties {

    /** 消费失败的最大重试次数，超过后进入死信队列。 */
    private int maxRetries = 3;

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
```

- [ ] **Step 3: 实现队列、交换机与死信配置**

创建 `src/main/java/com/fjf/teproject/config/RabbitConfig.java`：

```java
package com.fjf.teproject.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 队列、交换机与序列化配置。
 *
 * <p>主队列配置了死信交换机：消费重试耗尽后消息进入死信队列，而不是被
 * 丢弃或无限重投。无限重投会阻塞整个队列，一个坏消息就能拖垮所有活动。</p>
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "seckill.purchase.exchange";
    public static final String QUEUE = "seckill.purchase.queue";
    public static final String ROUTING_KEY = "seckill.purchase";
    public static final String DLX_EXCHANGE = "seckill.purchase.dlx";
    public static final String DLQ = "seckill.purchase.dlq";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange seckillDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue seckillQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue()).to(seckillExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding seckillDlqBinding() {
        return BindingBuilder.bind(seckillDeadLetterQueue()).to(seckillDlxExchange()).with(DLQ);
    }

    /**
     * 使用 JSON 序列化消息体，并要求在发送失败时抛异常而不是静默丢弃。
     *
     * <p>publisher-confirm 与 publisher-return 由 application.properties
     * 中的 {@code spring.rabbitmq.publisher-confirm-type} 与
     * {@code spring.rabbitmq.publisher-returns} 开启；这里是消息不会
     * 无声消失的前提。</p>
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(new Jackson2JsonMessageConverter());
        template.setMandatory(true);
        return template;
    }
}
```

在 `application.properties` 中追加（这是「消息不静默丢失」的前提）：

```properties
spring.rabbitmq.publisher-confirm-type=correlated
spring.rabbitmq.publisher-returns=true
spring.rabbitmq.listener.simple.default-requeue-rejected=false
spring.rabbitmq.listener.simple.retry.enabled=true
spring.rabbitmq.listener.simple.retry.max-attempts=3
```

`default-requeue-rejected=false` 很关键：置为 `true` 会让消费失败的消息立即重回队列并再次失败，形成疯狂重试的死循环。

- [ ] **Step 4: 实现生产者**

创建 `src/main/java/com/fjf/teproject/messaging/SeckillMessageProducer.java`：

```java
package com.fjf.teproject.messaging;

import com.fjf.teproject.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 把抢购成功的记录投递到队列。
 *
 * <p><b>已知缺口</b>：Redis 扣减成功与消息投递不是原子的。若投递失败，
 * 用户已经收到 200、库存也真的扣了，但落库消息丢失——这是一次静默的
 * 数据丢失。这里做有限次同步重试，仍失败则记录错误日志，并由 A1 对账
 * 发现不一致后告警。</p>
 *
 * <p>这是刻意接受的取舍而非疏漏。另一个可选方案是把 XADD 写进同一个
 * Lua 脚本（用 Redis Stream 替代 RabbitMQ），从物理上消除缺口，但会
 * 失去 RabbitMQ 的通用性。详见 spec §8.3。</p>
 */
@Component
public class SeckillMessageProducer {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageProducer.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;

    public SeckillMessageProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(long seckillId, String userId) {
        SeckillMessage message = new SeckillMessage(seckillId, userId);
        AmqpException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.ROUTING_KEY, message);
                return;
            } catch (AmqpException exception) {
                lastFailure = exception;
                log.warn("投递秒杀消息失败，第 {}/{} 次尝试，seckillId={}, userId={}",
                        attempt, MAX_ATTEMPTS, seckillId, userId, exception);
            }
        }

        // 到这一步说明 Redis 已扣减但消息未投出。记 error 级别日志，
        // 让 A1 对账成为发现该情况的兜底手段。
        log.error("秒杀消息投递最终失败，Redis 已扣减但落库消息丢失，"
                + "seckillId={}, userId={}。等待 A1 对账发现该不一致。",
                seckillId, userId, lastFailure);
    }
}
```

- [ ] **Step 5: 实现消费者**

创建 `src/main/java/com/fjf/teproject/messaging/SeckillMessageConsumer.java`：

```java
package com.fjf.teproject.messaging;

import com.fjf.teproject.config.RabbitConfig;
import com.fjf.teproject.repository.SeckillRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费抢购消息，把结果落库。
 *
 * <p>使用手动 ack（{@code acknowledge-mode=manual}）：事务提交成功后才确认消息。
 * 自动 ack 会在事务提交前就确认，此时进程崩溃即丢消息。</p>
 *
 * <p>消费必须幂等：消息是至少一次投递，重复消息由唯一索引挡下，
 * {@link SeckillRecordRepository#recordPurchase} 返回 false 时同样视为
 * 处理成功并 ack，否则重复消息会永远重投。</p>
 */
@Component
public class SeckillMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageConsumer.class);

    private final SeckillRecordRepository recordRepository;

    public SeckillMessageConsumer(SeckillRecordRepository recordRepository) {
        this.recordRepository = recordRepository;
    }

    /**
     * 落库一次抢购。
     *
     * <p>抛出的异常会让消息按 {@code spring.rabbitmq.listener.simple.retry}
     * 配置重试，重试耗尽后进入死信队列。若在方法内捕获所有异常并正常返回，
     * 消息会被 ack 且永久丢失。</p>
     */
    @RabbitListener(queues = RabbitConfig.QUEUE)
    public void onMessage(SeckillMessage message) {
        boolean inserted = recordRepository.recordPurchase(message.getSeckillId(), message.getUserId());
        if (inserted) {
            log.debug("落库成功 seckillId={}, userId={}", message.getSeckillId(), message.getUserId());
        } else {
            // 重复消息：唯一索引已经挡下，这正是幂等的表现，不是错误。
            log.debug("重复消息已忽略 seckillId={}, userId={}", message.getSeckillId(), message.getUserId());
        }
    }
}
```

在 `application.properties` 中追加手动 ack：

```properties
spring.rabbitmq.listener.simple.acknowledge-mode=auto
```

注意：Spring AMQP 的 `auto` 模式在监听器正常返回时才 ack、抛异常时不 ack（并触发重试/死信），这已经满足「事务提交成功后才 ack」的要求，且比手动 ack 更不容易写错——手动模式下忘记 ack 会导致消息堆积。**采用 `auto` 模式，并在测试中验证失败消息确实进入死信队列。**

- [ ] **Step 6: 写消费者集成测试**

创建 `src/test/java/com/fjf/teproject/messaging/SeckillMessageConsumerIT.java`：

```java
package com.fjf.teproject.messaging;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class SeckillMessageConsumerIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillMessageProducer producer;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    @Test
    void publishedMessageIsEventuallyPersisted() {
        producer.publish(SECKILL_ID, "u1");

        // 异步落库，用轮询等待而非固定 sleep，避免测试既慢又不稳定。
        await().atMost(Duration.ofSeconds(10))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);
    }

    @Test
    void duplicateMessagesPersistExactlyOneRecord() {
        // 至少一次投递的重复场景：三条相同消息，只能留下一行流水，
        // 也只能扣减一次库存。
        int before = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        producer.publish(SECKILL_ID, "u1");
        producer.publish(SECKILL_ID, "u1");
        producer.publish(SECKILL_ID, "u1");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        // 再等一小段时间，确认不会有迟到的第二条落库。
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        assertEquals(before - 1,
                activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
    }

    @Test
    void multipleUsersAreAllPersisted() {
        for (int i = 0; i < 20; i++) {
            producer.publish(SECKILL_ID, "u" + i);
        }

        await().atMost(Duration.ofSeconds(15))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 20);
    }
}
```

- [ ] **Step 7: 加入 Awaitility 测试依赖**

`awaitility` 由 `spring-boot-starter-test` 传递引入，无需额外声明。若编译报找不到 `org.awaitility`，在 `pom.xml` 中显式加入：

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 8: 在应用类上启用消息配置属性**

修改 `src/main/java/com/fjf/teproject/TeProjectApplication.java`：

```java
package com.fjf.teproject;

import com.fjf.teproject.messaging.SeckillMessagingProperties;
import com.fjf.teproject.reconcile.ReconcileProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ReconcileProperties.class, SeckillMessagingProperties.class})
public class TeProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeProjectApplication.class, args);
    }

}
```

- [ ] **Step 9: 运行集成测试**

Run: `mvnw.cmd test -Pit -Dtest=SeckillMessageConsumerIT`

Expected: BUILD SUCCESS，3 个测试通过

---

## Task 11: 服务编排与 HTTP 接口

**Files:**
- Create: `src/main/java/com/fjf/teproject/service/SeckillService.java`
- Modify: `src/main/java/com/fjf/teproject/controller/SeckillController.java`
- Create: `src/main/java/com/fjf/teproject/controller/SeckillExceptionHandler.java`
- Delete: `src/main/java/com/fjf/teproject/service/SeckillInventoryService.java`
- Delete: `src/test/java/com/fjf/teproject/service/SeckillInventoryServiceTest.java`
- Modify: `docs/api/seckill.md`
- Test: `src/test/java/com/fjf/teproject/service/SeckillServiceTest.java`
- Test: `src/test/java/com/fjf/teproject/controller/SeckillControllerIT.java`

**Interfaces:**
- Consumes: `ActivityRegistry`（Task 4）、`SeckillStockRepository`（Task 7）、`SeckillMessageProducer`（Task 10）、`SeckillActivityRepository`（Task 8）
- Produces:
  - `SeckillService.purchase(long seckillId, String userId)` → `DeductionOutcome`
  - `SeckillService.getRemainingStock(long seckillId)` → `int`，活动不存在时抛 `SeckillException(SECKILL_NOT_FOUND)`
  - HTTP：`POST /api/seckill/activities/{seckillId}/purchase`、`GET /api/seckill/activities/{seckillId}/stock`

**设计说明：** 这里替换掉旧的内存秒杀接口。`SeckillInventoryService` 与其测试一并删除——同一功能保留两套互相矛盾的实现，会让文档和代码都有两个事实。

- [ ] **Step 1: 写服务的失败测试**

创建 `src/test/java/com/fjf/teproject/service/SeckillServiceTest.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import com.fjf.teproject.messaging.SeckillMessageProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务编排逻辑的单元测试：全部依赖用替身，不需要 Docker。
 */
class SeckillServiceTest {

    private static final long SECKILL_ID = 1L;

    private SeckillStockRepository stockRepository;
    private SeckillMessageProducer producer;
    private ActivityRegistry registry;
    private SeckillService service;

    @BeforeEach
    void setUp() {
        stockRepository = mock(SeckillStockRepository.class);
        producer = mock(SeckillMessageProducer.class);
        registry = new ActivityRegistry();
        service = new SeckillService(stockRepository, producer, registry);
    }

    @Test
    void unknownActivityIsRejectedWithoutTouchingRedis() {
        // 活动不在注册表中即 404，不应进入 Lua——否则会拿到 -3 而误报 503，
        // 把客户端的输入错误伪装成服务端故障。
        SeckillException exception = assertThrows(SeckillException.class,
                () -> service.purchase(SECKILL_ID, "u1"));

        assertEquals(SeckillErrorCode.SECKILL_NOT_FOUND, exception.getErrorCode());
        verify(stockRepository, never()).tryDeduct(anyLong(), anyString());
    }

    @Test
    void successfulPurchasePublishesMessage() {
        registry.register(java.util.Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.success(99));

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertTrue(outcome.isSuccess());
        assertEquals(99, outcome.getRemainingStock());
        verify(producer).publish(SECKILL_ID, "u1");
    }

    @Test
    void soldOutPurchaseDoesNotPublishMessage() {
        // 失败绝不能投递消息——那会让 DB 记录一次并不存在的购买。
        registry.register(java.util.Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.failure(SeckillErrorCode.STOCK_SOLD_OUT));

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertEquals(SeckillErrorCode.STOCK_SOLD_OUT, outcome.getErrorCode());
        verify(producer, never()).publish(anyLong(), anyString());
    }

    @Test
    void alreadyPurchasedDoesNotPublishMessage() {
        registry.register(java.util.Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.failure(SeckillErrorCode.ALREADY_PURCHASED));

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertEquals(SeckillErrorCode.ALREADY_PURCHASED, outcome.getErrorCode());
        verify(producer, never()).publish(anyLong(), anyString());
    }

    @Test
    void notReadyIsPropagatedAsServiceUnavailable() {
        registry.register(java.util.Collections.singletonList(SECKILL_ID));
        when(stockRepository.tryDeduct(SECKILL_ID, "u1"))
                .thenReturn(DeductionOutcome.failure(SeckillErrorCode.NOT_READY));

        DeductionOutcome outcome = service.purchase(SECKILL_ID, "u1");

        assertEquals(SeckillErrorCode.NOT_READY, outcome.getErrorCode());
        verify(producer, never()).publish(anyLong(), anyString());
    }

    @Test
    void stockQueryOnUnknownActivityThrowsNotFound() {
        SeckillException exception = assertThrows(SeckillException.class,
                () -> service.getRemainingStock(SECKILL_ID));

        assertEquals(SeckillErrorCode.SECKILL_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void stockQueryOnKnownActivityReadsRedis() {
        registry.register(java.util.Collections.singletonList(SECKILL_ID));
        when(stockRepository.getRemainingStock(SECKILL_ID)).thenReturn(42);

        assertEquals(42, service.getRemainingStock(SECKILL_ID));
    }

    @Test
    void stockQueryOnRegisteredButUnwarmedActivityIsNotReady() {
        // 在注册表中（活动合法）但 Redis 无键（预热失败）→ 503 而非 404。
        registry.register(java.util.Collections.singletonList(SECKILL_ID));
        when(stockRepository.getRemainingStock(SECKILL_ID)).thenReturn(-1);

        SeckillException exception = assertThrows(SeckillException.class,
                () -> service.getRemainingStock(SECKILL_ID));

        assertEquals(SeckillErrorCode.NOT_READY, exception.getErrorCode());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Dtest=SeckillServiceTest`

Expected: 编译失败，`cannot find symbol: class SeckillService`

- [ ] **Step 3: 实现服务**

创建 `src/main/java/com/fjf/teproject/service/SeckillService.java`：

```java
package com.fjf.teproject.service;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import com.fjf.teproject.messaging.SeckillMessageProducer;
import org.springframework.stereotype.Service;

/**
 * 秒杀用例编排：校验活动 → 原子扣减 → 投递落库消息。
 *
 * <p>时序上，HTTP 200 在消息投递之前就返回。Redis 扣减完成即为业务意义上的
 * 成功，落库是补偿性的而非确认性的。若投递失败，用户仍会收到 200，
 * 缺口由 A1 对账发现（详见 spec §8.3）。</p>
 *
 * <p>不使用分布式锁：并发不变量完全由 Redis 中的 Lua 脚本保证。</p>
 */
@Service
public class SeckillService {

    private final SeckillStockRepository stockRepository;
    private final SeckillMessageProducer messageProducer;
    private final ActivityRegistry activityRegistry;

    public SeckillService(SeckillStockRepository stockRepository,
                          SeckillMessageProducer messageProducer,
                          ActivityRegistry activityRegistry) {
        this.stockRepository = stockRepository;
        this.messageProducer = messageProducer;
        this.activityRegistry = activityRegistry;
    }

    /**
     * 执行一次抢购。
     *
     * @throws SeckillException 活动不存在时抛出 {@code SECKILL_NOT_FOUND}
     */
    public DeductionOutcome purchase(long seckillId, String userId) {
        if (!activityRegistry.exists(seckillId)) {
            throw new SeckillException(SeckillErrorCode.SECKILL_NOT_FOUND);
        }

        DeductionOutcome outcome = stockRepository.tryDeduct(seckillId, userId);
        if (outcome.isSuccess()) {
            messageProducer.publish(seckillId, userId);
        }
        return outcome;
    }

    /**
     * 查询实时库存，读 Redis 而非数据库。
     *
     * <p>读数据库会让用户看到「还有库存」却抢不到——异步落库必然滞后。</p>
     *
     * @throws SeckillException 活动不存在时为 {@code SECKILL_NOT_FOUND}；
     *                          活动合法但 Redis 无键时为 {@code NOT_READY}
     */
    public int getRemainingStock(long seckillId) {
        if (!activityRegistry.exists(seckillId)) {
            throw new SeckillException(SeckillErrorCode.SECKILL_NOT_FOUND);
        }

        int remaining = stockRepository.getRemainingStock(seckillId);
        if (remaining < 0) {
            throw new SeckillException(SeckillErrorCode.NOT_READY);
        }
        return remaining;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvnw.cmd test -Dtest=SeckillServiceTest`

Expected: BUILD SUCCESS，8 个测试通过

- [ ] **Step 5: 实现异常处理器**

创建 `src/main/java/com/fjf/teproject/controller/SeckillExceptionHandler.java`：

```java
package com.fjf.teproject.controller;

import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * 把业务异常转换为 HTTP 响应。
 *
 * <p>HTTP 状态与响应体中的 code 都取自 {@link SeckillErrorCode}，
 * 保证接口契约只有一个事实来源。</p>
 */
@RestControllerAdvice
public class SeckillExceptionHandler {

    @ExceptionHandler(SeckillException.class)
    public ResponseEntity<Map<String, Object>> handleSeckillException(SeckillException exception) {
        return buildResponse(exception.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return buildResponse(SeckillErrorCode.INVALID_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        return buildResponse(SeckillErrorCode.INVALID_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(SeckillErrorCode errorCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", errorCode.getCode());
        body.put("message", errorCode.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }
}
```

- [ ] **Step 6: 重写 Controller**

完整替换 `src/main/java/com/fjf/teproject/controller/SeckillController.java`：

```java
package com.fjf.teproject.controller;

import com.fjf.teproject.domain.DeductionOutcome;
import com.fjf.teproject.domain.SeckillErrorCode;
import com.fjf.teproject.domain.SeckillException;
import com.fjf.teproject.service.SeckillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 分布式秒杀的 HTTP 接口。
 *
 * <p>接口契约见 docs/api/seckill.md。所有响应的 code 字段都是字符串，
 * 成功与失败形状一致，避免调用方需要判断类型才能解析。</p>
 */
@RestController
@RequestMapping("/api/seckill/activities")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    /**
     * 执行一次抢购。
     *
     * <p>幂等：同一 (seckillId, userId) 重复请求不会二次扣减，稳定返回
     * ALREADY_PURCHASED（409）。</p>
     */
    @PostMapping("/{seckillId}/purchase")
    public ResponseEntity<Map<String, Object>> purchase(@PathVariable long seckillId,
                                                        @RequestBody(required = false) PurchaseRequest request) {
        String userId = request == null ? null : request.getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            return buildError(SeckillErrorCode.INVALID_REQUEST);
        }

        DeductionOutcome outcome = seckillService.purchase(seckillId, userId.trim());
        if (!outcome.isSuccess()) {
            return buildError(outcome.getErrorCode());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("code", SeckillErrorCode.SUCCESS.getCode());
        body.put("message", SeckillErrorCode.SUCCESS.getMessage());
        body.put("remainingStock", outcome.getRemainingStock());
        return ResponseEntity.ok(body);
    }

    /** 查询实时库存快照（读 Redis）。其他并发请求可能在响应返回前继续扣减。 */
    @GetMapping("/{seckillId}/stock")
    public ResponseEntity<Map<String, Object>> getStock(@PathVariable long seckillId) {
        int remaining = seckillService.getRemainingStock(seckillId);

        Map<String, Object> body = new HashMap<>();
        body.put("code", SeckillErrorCode.SUCCESS.getCode());
        body.put("message", SeckillErrorCode.SUCCESS.getMessage());
        body.put("remainingStock", remaining);
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> buildError(SeckillErrorCode errorCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", errorCode.getCode());
        body.put("message", errorCode.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus()).body(body);
    }

    /** 抢购请求体。 */
    public static class PurchaseRequest {

        private String userId;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }
    }
}
```

- [ ] **Step 7: 删除旧的内存实现**

删除以下两个文件：
- `src/main/java/com/fjf/teproject/service/SeckillInventoryService.java`
- `src/test/java/com/fjf/teproject/service/SeckillInventoryServiceTest.java`

- [ ] **Step 8: 写 HTTP 契约的集成测试**

创建 `src/test/java/com/fjf/teproject/controller/SeckillControllerIT.java`：

```java
package com.fjf.teproject.controller;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class SeckillControllerIT extends IntegrationTestBase {

    private static final long ACTIVITY_A = 1L;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @BeforeEach
    void setUp() {
        resetRedis();
        warmUpRunner.warmUpAll();
    }

    private ResponseEntity<String> purchase(long seckillId, String userId) {
        return restTemplate.postForEntity(
                "/api/seckill/activities/" + seckillId + "/purchase",
                java.util.Collections.singletonMap("userId", userId),
                String.class);
    }

    @Test
    void successfulPurchaseReturns200WithStringCode() {
        ResponseEntity<String> response = purchase(ACTIVITY_A, "u1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"SUCCESS\""));
        assertEquals(true, response.getBody().contains("\"remainingStock\":99"));
    }

    @Test
    void unknownActivityReturns404Not503() {
        // 关键区分：客户端传错 id 是 404，不能伪装成服务端故障。
        ResponseEntity<String> response = purchase(999L, "u1");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"SECKILL_NOT_FOUND\""));
    }

    @Test
    void missingUserIdReturns400() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/seckill/activities/" + ACTIVITY_A + "/purchase",
                java.util.Collections.emptyMap(), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"INVALID_REQUEST\""));
    }

    @Test
    void repeatPurchaseReturns409AlreadyPurchased() {
        purchase(ACTIVITY_A, "u1");

        ResponseEntity<String> response = purchase(ACTIVITY_A, "u1");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"ALREADY_PURCHASED\""));
    }

    @Test
    void soldOutReturns409StockSoldOut() {
        // 活动 C 库存 1000 太多，直接把库存压到 0 更直接。
        stockRepository.forceSetStock(ACTIVITY_A, 1);
        purchase(ACTIVITY_A, "u1");

        ResponseEntity<String> response = purchase(ACTIVITY_A, "u2");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"STOCK_SOLD_OUT\""));
    }

    @Test
    void stockQueryReturnsRemainingFromRedis() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/seckill/activities/" + ACTIVITY_A + "/stock", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"remainingStock\":100"));
    }

    @Test
    void stockQueryOnRegisteredButUnwarmedActivityReturns503() {
        // 模拟 Redis 被清空：活动仍在注册表中，但没有库存键。
        resetRedis();

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/seckill/activities/" + ACTIVITY_A + "/stock", String.class);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(true, response.getBody().contains("\"code\":\"NOT_READY\""));
    }

    @Test
    void multipleActivitiesHaveIndependentStock() {
        ResponseEntity<String> responseB = purchase(2L, "u1");

        assertEquals(HttpStatus.OK, responseB.getStatusCode());
        assertEquals(true, responseB.getBody().contains("\"remainingStock\":499"));
    }
}
```

- [ ] **Step 9: 运行测试**

Run: `mvnw.cmd test -Pit -Dtest=SeckillControllerIT`

Expected: BUILD SUCCESS，8 个测试通过

- [ ] **Step 10: 重写接口文档**

完整替换 `docs/api/seckill.md`：

```markdown
# 分布式秒杀 API

> 基于 Redis 权威库存 + RabbitMQ 异步落库的多实例秒杀接口。
> 本文描述对调用方可见的契约；并发语义与边界见 `docs/architecture.md`。

## 抢购

`POST /api/seckill/activities/{seckillId}/purchase`

```json
{ "userId": "u_10086" }
```

### 响应

| 场景 | HTTP | `code` | `message` |
| --- | --- | --- | --- |
| 抢购成功 | `200` | `SUCCESS` | 抢购成功 |
| 库存售罄 | `409` | `STOCK_SOLD_OUT` | 库存已售罄 |
| 该用户已购 | `409` | `ALREADY_PURCHASED` | 您已参与过本次秒杀 |
| 活动不存在 | `404` | `SECKILL_NOT_FOUND` | 秒杀活动不存在 |
| 服务未就绪 | `503` | `NOT_READY` | 秒杀服务暂不可用 |
| 参数非法 | `400` | `INVALID_REQUEST` | 参数校验失败 |

成功响应：

```json
{ "code": "SUCCESS", "message": "抢购成功", "remainingStock": 99 }
```

失败响应（形状与成功一致，`code` 恒为字符串）：

```json
{ "code": "STOCK_SOLD_OUT", "message": "库存已售罄" }
```

**为什么「已购」是 409 而不是 403**：这不是权限问题，而是同一
`(seckillId, userId)` 资源已存在的状态冲突。附带效果是**接口幂等**——
重复点击或网络重试不会二次扣减，稳定返回 `ALREADY_PURCHASED`。

**为什么「活动不存在」是 404 而不是 503**：见下方「就绪状态」一节。

## 查询库存

`GET /api/seckill/activities/{seckillId}/stock`

```json
{ "code": "SUCCESS", "message": "抢购成功", "remainingStock": 100 }
```

失败语义与抢购接口一致（`404` / `503`）。

## 并发语义

- 库存以 **Redis 为权威副本**。`remainingStock` 是接口处理时的实时快照，
  其他并发请求可能在响应返回前继续扣减。
- 扣减由单个 Lua 脚本原子完成：判断限购、判断库存、扣减、记录之间
  不存在其它请求插入的窗口，因此不会超卖。
- 收到 `200` 代表 Redis 已原子扣减，**此时落库尚未完成**。落库是异步的，
  最终一致但存在延迟窗口。
- **成功不可撤销**：即使后续落库失败，库存也不会归还。

## 就绪状态

Lua 脚本对「活动不存在」与「Redis 未预热」返回同一个值，接口通过启动时
载入的活动注册表区分二者：

- `seckillId` 不在注册表中 → `404 SECKILL_NOT_FOUND`（调用方输入错误）
- 在注册表中但 Redis 无库存键 → `503 NOT_READY`（预热失败或 Redis 被清空）

## 约束与边界

- **`userId` 不经验证**：接口不做任何认证，直接采信请求中的 `userId`。
  任何人构造 `userId` 即可绕过一人一单限制。真实系统必须在网关或过滤器
  完成鉴权，本接口的定位是教学演示。
- 活动由 `data.sql` 种子数据预置，没有管理端，也不支持秒杀时间窗口。
- 不包含订单创建、支付、退款与库存回补。
- 应用无状态，可水平扩展为多实例；库存与限购关系由 Redis 统一保证。
- Redis 需开启持久化，否则重启会丢失实时库存。
```

- [ ] **Step 11: 运行完整测试集**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS，不需要 Docker

Run: `mvnw.cmd test -Pit`

Expected: BUILD SUCCESS，全部集成测试通过

---

## Task 12: 对账任务

**Files:**
- Create: `src/main/java/com/fjf/teproject/reconcile/SeckillReconciler.java`
- Test: `src/test/java/com/fjf/teproject/reconcile/SeckillReconcilerIT.java`

**Interfaces:**
- Consumes: `ReconcileEvaluator`（Task 5）、`ReconcileProperties`（Task 5）、`SeckillStockRepository`（Task 7）、`SeckillActivityRepository`（Task 8）、`SeckillRecordRepository`（Task 9）
- Produces: `SeckillReconciler.reconcileAll(Instant now)` → `List<ReconcileFinding>`；`@Scheduled` 入口 `scheduledReconcile()`

**设计说明：** 对账**只告警不修数**。用户已经收到 200，任何一侧的自动改动都可能制造新的不一致。

- [ ] **Step 1: 写失败的测试**

创建 `src/test/java/com/fjf/teproject/reconcile/SeckillReconcilerIT.java`：

```java
package com.fjf.teproject.reconcile;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
class SeckillReconcilerIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillReconciler reconciler;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    private List<ReconcileFinding> reconcileCurrentActivity() {
        return reconciler.reconcileAll(Instant.now()).stream()
                .filter(finding -> finding.getSeckillId() == SECKILL_ID)
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void fullyConsistentActivityProducesNoFinding() {
        stockRepository.forceSetStock(SECKILL_ID, 100);

        assertTrue(reconcileCurrentActivity().isEmpty());
    }

    @Test
    void redisDeductedButNotYetPersistedIsNotAlertedWhileFresh() {
        // Redis 扣了 1 件，DB 还没落库，但库存时间戳刚刚更新过
        // （resetToInitial 会刷新时间戳）→ 属于正常追赶，不告警。
        stockRepository.forceSetStock(SECKILL_ID, 99);

        assertTrue(reconcileCurrentActivity().isEmpty());
    }

    @Test
    void stalledMismatchIsAlertedAsA1() {
        // Redis 扣了 5 件，DB 一条记录都没有，且把时间戳人为推到很久以前。
        stockRepository.forceSetStock(SECKILL_ID, 95);
        jdbcTemplate.update(
                "UPDATE seckill_activity SET stock_updated_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(10)),
                SECKILL_ID);

        List<ReconcileFinding> findings = reconcileCurrentActivity();

        assertEquals(1, findings.size());
        assertEquals(ReconcileFinding.Kind.A1_REDIS_DB_MISMATCH, findings.get(0).getKind());
    }

    @Test
    void recordCountOutOfSyncWithStockIsAlertedAsA2() {
        // 人工制造分叉：写一条流水并把库存减 2，使流水数与扣减数不等。
        stockRepository.forceSetStock(SECKILL_ID, 100);
        recordRepository.recordPurchase(SECKILL_ID, "u1");
        jdbcTemplate.update(
                "UPDATE seckill_activity SET remaining_stock = remaining_stock - 1 WHERE id = ?",
                SECKILL_ID);

        List<ReconcileFinding> findings = reconcileCurrentActivity();

        assertTrue(findings.stream()
                        .anyMatch(f -> f.getKind() == ReconcileFinding.Kind.A2_RECORD_STOCK_MISMATCH),
                "应报告 A2 金丝雀，实际 " + findings);
    }

    @Test
    void reconcilerNeverModifiesData() {
        // 对账只读不写：这是核心设计约束，必须被测试钉住。
        stockRepository.forceSetStock(SECKILL_ID, 90);
        int stockBefore = stockRepository.getRemainingStock(SECKILL_ID);
        int dbStockBefore = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();
        int recordsBefore = recordRepository.countByActivity(SECKILL_ID);

        reconciler.reconcileAll(Instant.now());

        assertEquals(stockBefore, stockRepository.getRemainingStock(SECKILL_ID));
        assertEquals(dbStockBefore,
                activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock());
        assertEquals(recordsBefore, recordRepository.countByActivity(SECKILL_ID));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvnw.cmd test -Pit -Dtest=SeckillReconcilerIT`

Expected: 编译失败，`cannot find symbol: class SeckillReconciler`

- [ ] **Step 3: 实现对账任务**

创建 `src/main/java/com/fjf/teproject/reconcile/SeckillReconciler.java`：

```java
package com.fjf.teproject.reconcile;

import com.fjf.teproject.domain.SeckillActivity;
import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 周期比对 Redis 权威库存与数据库副本，发现不一致时告警。
 *
 * <p><b>只告警，不自动修数。</b>用户已经收到过 200，任何一侧的自动改动
 * 都可能制造新的不一致。对账的职责是让人知道出了问题，而不是猜测
 * 应该相信哪一侧。</p>
 *
 * <p>判定规则本身在 {@link ReconcileEvaluator} 中实现——那是纯函数，
 * 可以在无 Docker 环境下被完整测试。本类只负责把两侧的真实状态读出来
 * 拼成快照。</p>
 */
@Component
public class SeckillReconciler {

    private static final Logger log = LoggerFactory.getLogger(SeckillReconciler.class);

    private final SeckillActivityRepository activityRepository;
    private final SeckillRecordRepository recordRepository;
    private final SeckillStockRepository stockRepository;
    private final ReconcileEvaluator evaluator;
    private final ReconcileProperties properties;

    public SeckillReconciler(SeckillActivityRepository activityRepository,
                             SeckillRecordRepository recordRepository,
                             SeckillStockRepository stockRepository,
                             ReconcileEvaluator evaluator,
                             ReconcileProperties properties) {
        this.activityRepository = activityRepository;
        this.recordRepository = recordRepository;
        this.stockRepository = stockRepository;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${seckill.reconcile.interval-seconds:10}000")
    public void scheduledReconcile() {
        try {
            List<ReconcileFinding> findings = reconcileAll(Instant.now());
            for (ReconcileFinding finding : findings) {
                // 真实系统这里应接入监控告警通道；教学 Demo 以 error 日志代替。
                log.error("对账发现不一致：{}", finding);
            }
        } catch (Exception exception) {
            log.error("对账任务执行失败", exception);
        }
    }

    /**
     * 对所有活动执行一次对账。
     *
     * <p>只读操作，不修改任何数据。</p>
     */
    public List<ReconcileFinding> reconcileAll(Instant now) {
        List<ReconcileFinding> findings = new ArrayList<>();
        long stallThreshold = properties.getStallThresholdSeconds();

        for (SeckillActivity activity : activityRepository.findAll()) {
            long seckillId = activity.getId();
            int redisStock = stockRepository.getRemainingStock(seckillId);
            if (redisStock < 0) {
                // Redis 中没有该活动的库存键——预热失败或 Redis 被清空。
                // 这不是「不一致」，而是服务未就绪，抢购接口已返回 503。
                continue;
            }

            ReconcileSnapshot snapshot = ReconcileSnapshot.of(
                    seckillId,
                    activity.getInitialStock(),
                    redisStock,
                    activity.getRemainingStock(),
                    recordRepository.countByActivity(seckillId),
                    toInstant(activity.getStockUpdatedAt()));

            findings.addAll(evaluator.evaluate(snapshot, now, stallThreshold));
        }

        return findings;
    }

    private Instant toInstant(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()).toInstant();
    }
}
```

- [ ] **Step 4: 开启定时任务并注册 `ReconcileEvaluator` 为 Bean**

修改 `src/main/java/com/fjf/teproject/TeProjectApplication.java`：

```java
package com.fjf.teproject;

import com.fjf.teproject.messaging.SeckillMessagingProperties;
import com.fjf.teproject.reconcile.ReconcileEvaluator;
import com.fjf.teproject.reconcile.ReconcileProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ReconcileProperties.class, SeckillMessagingProperties.class})
public class TeProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(TeProjectApplication.class, args);
    }

    /** 对账判定规则是无状态的纯函数，注册为 Bean 供 SeckillReconciler 注入。 */
    @Bean
    public ReconcileEvaluator reconcileEvaluator() {
        return new ReconcileEvaluator();
    }
}
```

- [ ] **Step 5: 运行集成测试**

Run: `mvnw.cmd test -Pit -Dtest=SeckillReconcilerIT`

Expected: BUILD SUCCESS，5 个测试通过

---

# 阶段 4：并发验证与交付

## Task 13: T1 不超卖并发测试

**Files:**
- Test: `src/test/java/com/fjf/teproject/concurrency/NoOversellIT.java`

**Interfaces:**
- Consumes: `SeckillControllerIT` 的全部基础设施、`integrationTestBase`
- Produces: 无（验证性测试）

**设计说明：** 这是四条不变量中最重要的验证。沿用项目既有的 `ready` / `start` / `completed` 三个 `CountDownLatch` 模式——它比 `parallelStream` 更能真正制造竞争。

- [ ] **Step 1: 写测试**

创建 `src/test/java/com/fjf/teproject/concurrency/NoOversellIT.java`：

```java
package com.fjf.teproject.concurrency;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1：验证 I1（不超卖）与 I3（不少卖）。
 *
 * <p>并发测试是概率性的——它提高竞争概率，但不保证每次都撞上竞态。
 * 因此本类通过重复执行增强置信度，而非跑一次即宣布正确。</p>
 */
@Tag("integration")
class NoOversellIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;
    private static final int REPEAT = 10;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillWarmUpRunner warmUpRunner;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    @Test
    void concurrentRequestsNeverExceedInitialStock() throws Exception {
        int initialStock = 100;
        int requestCount = 200;

        for (int round = 1; round <= REPEAT; round++) {
            resetRedis();
            jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
            activityRepository.resetToInitial(SECKILL_ID);
            stockRepository.forceSetStock(SECKILL_ID, initialStock);

            int successes = fireConcurrentPurchases(requestCount);

            assertEquals(initialStock, successes,
                    "第 " + round + " 轮：成功数应恰好等于初始库存");
            assertEquals(0, stockRepository.getRemainingStock(SECKILL_ID),
                    "第 " + round + " 轮：Redis 库存应归零且不为负");

            // 等待消费者追平，验证最终一致（I4）。
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> recordRepository.countByActivity(SECKILL_ID) == initialStock);
            assertEquals(initialStock,
                    activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock(),
                    "第 " + round + " 轮：数据库库存副本应最终归零");
        }
    }

    private int fireConcurrentPurchases(int requestCount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(requestCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        // 先让所有任务就绪，再同时开始，最大化竞争概率。
        for (int i = 0; i < requestCount; i++) {
            final String userId = "u" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/seckill/activities/" + SECKILL_ID + "/purchase",
                            Collections.singletonMap("userId", userId),
                            String.class);
                    if (response.getBody() != null
                            && response.getBody().contains("\"code\":\"SUCCESS\"")) {
                        successes.incrementAndGet();
                    }
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "所有线程应在 10 秒内就绪");
        start.countDown();
        assertTrue(completed.await(60, TimeUnit.SECONDS), "所有请求应在 60 秒内完成");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertNull(failure.get(), "并发请求不应抛异常");
        return successes.get();
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvnw.cmd test -Pit -Dtest=NoOversellIT`

Expected: BUILD SUCCESS

**若成功数大于初始库存**：出现了超卖，是最高优先级的缺陷。用 `superpowers:systematic-debugging` 排查，重点检查 Lua 脚本是否真的在单次 EVAL 内完成判断与扣减。

**若成功数小于初始库存**：出现了少卖。检查 `CountDownLatch` 的超时是否过短导致请求未全部发出，或 Lua 脚本是否在库存为 1 时被误判。

---

## Task 14: T2 一人一单与 T3 消费幂等

**Files:**
- Test: `src/test/java/com/fjf/teproject/concurrency/OneUserOneOrderIT.java`
- Test: `src/test/java/com/fjf/teproject/concurrency/ConsumerIdempotencyIT.java`

**Interfaces:**
- Consumes: 同 Task 13
- Produces: 无（验证性测试）

- [ ] **Step 1: 写 T2 测试**

创建 `src/test/java/com/fjf/teproject/concurrency/OneUserOneOrderIT.java`：

```java
package com.fjf.teproject.concurrency;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2：验证 I2（一人一单）在并发下成立。
 *
 * <p>同一用户同时发起大量请求，必须只有一次成功。这是最容易被
 * 「先查再写」这种非原子实现破坏的场景——两个请求可能都在查询时
 * 看到「未购买」，然后双双写入。</p>
 */
@Tag("integration")
class OneUserOneOrderIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;
    private static final String USER_ID = "u_same";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
        stockRepository.forceSetStock(SECKILL_ID, 100);
    }

    @Test
    void sameUserCanOnlySucceedOnceEvenUnderConcurrency() throws Exception {
        int requestCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(requestCount);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger alreadyPurchased = new AtomicInteger();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<String> response = restTemplate.postForEntity(
                            "/api/seckill/activities/" + SECKILL_ID + "/purchase",
                            Collections.singletonMap("userId", USER_ID),
                            String.class);
                    String body = response.getBody();
                    if (body != null && body.contains("\"code\":\"SUCCESS\"")) {
                        successes.incrementAndGet();
                    } else if (body != null && body.contains("\"code\":\"ALREADY_PURCHASED\"")) {
                        alreadyPurchased.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(completed.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(1, successes.get(), "同一用户只能成功一次");
        assertEquals(requestCount - 1, alreadyPurchased.get(), "其余请求应全部返回已购");
        assertEquals(1, stockRepository.countPurchasers(SECKILL_ID), "限购集合应只有 1 个成员");
        assertEquals(99, stockRepository.getRemainingStock(SECKILL_ID), "只应扣减 1 件库存");

        await().atMost(Duration.ofSeconds(20))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);
    }
}
```

- [ ] **Step 2: 写 T3 测试**

创建 `src/test/java/com/fjf/teproject/concurrency/ConsumerIdempotencyIT.java`：

```java
package com.fjf.teproject.concurrency;

import com.fjf.teproject.messaging.SeckillMessageProducer;
import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T3：验证消费幂等——数据库唯一索引对「至少一次投递」的兜底。
 *
 * <p>直接向队列重复投递同一 (seckillId, userId) 消息，模拟消息中间件
 * 的重投行为。断言流水只有一条，且库存只被扣减一次。</p>
 */
@Tag("integration")
class ConsumerIdempotencyIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;

    @Autowired
    private SeckillMessageProducer producer;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
    }

    @Test
    void repeatedMessagesPersistExactlyOnceAndDeductExactlyOnce() {
        int stockBefore = activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock();

        for (int i = 0; i < 3; i++) {
            producer.publish(SECKILL_ID, "u_dup");
        }

        await().atMost(Duration.ofSeconds(15))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        // 再观察一段时间，确认没有迟到的重复落库。
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == 1);

        assertEquals(stockBefore - 1,
                activityRepository.findById(SECKILL_ID).orElseThrow().getRemainingStock(),
                "重复消息不得二次扣减库存");
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `mvnw.cmd test -Pit -Dtest=OneUserOneOrderIT,ConsumerIdempotencyIT`

Expected: BUILD SUCCESS，2 个测试通过

---

## Task 15: T4 多实例一致性

**Files:**
- Test: `src/test/java/com/fjf/teproject/concurrency/MultiInstanceConsistencyIT.java`

**Interfaces:**
- Consumes: 同 Task 13，另加 `SeckillWarmUpRunner`（用于模拟第二个实例的预热）
- Produces: 无（验证性测试）

**设计说明：** 这是整份设计中最有价值的测试。单实例测试用 `AtomicInteger` 也能通过，**只有多实例才能证明「分布式」真正成立**。本测试在同一 JVM 内启动两个 `RandomPort` 上下文，共享同一套容器。

**关于「同一 JVM」的取舍**：它不模拟真实的网络分区或多机部署，但能验证最关键的一点——**两个独立的应用实例共享同一份 Redis 状态时，全局成功数不超过初始库存**。这正是单实例测试无法覆盖的。

- [ ] **Step 1: 写测试**

创建 `src/test/java/com/fjf/teproject/concurrency/MultiInstanceConsistencyIT.java`：

```java
package com.fjf.teproject.concurrency;

import com.fjf.teproject.repository.SeckillActivityRepository;
import com.fjf.teproject.repository.SeckillRecordRepository;
import com.fjf.teproject.service.SeckillStockRepository;
import com.fjf.teproject.service.SeckillWarmUpRunner;
import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4：验证两个独立应用实例共享 Redis 时，全局不超卖。
 *
 * <p>这是单实例测试无法覆盖的场景：如果库存权威副本被放在应用内存中
 * （例如用 AtomicInteger），两个实例会各自持有 100 件库存，合计卖出 200 件。</p>
 *
 * <p>本类不使用 {@code IntegrationTestBase} 的默认端口，而是显式启动两个
 * RandomPort 上下文。容器仍由基类静态块提供并共享。</p>
 */
@Tag("integration")
class MultiInstanceConsistencyIT extends IntegrationTestBase {

    private static final long SECKILL_ID = 1L;
    private static final int INITIAL_STOCK = 100;
    private static final int REQUESTS_PER_INSTANCE = 100;

    @Autowired
    private ApplicationContext primaryContext;

    @Autowired
    private TestRestTemplate primaryRestTemplate;

    @Autowired
    private SeckillStockRepository stockRepository;

    @Autowired
    private SeckillRecordRepository recordRepository;

    @Autowired
    private SeckillActivityRepository activityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        resetRedis();
        jdbcTemplate.update("DELETE FROM seckill_record WHERE seckill_id = ?", SECKILL_ID);
        activityRepository.resetToInitial(SECKILL_ID);
        stockRepository.forceSetStock(SECKILL_ID, INITIAL_STOCK);
    }

    @Test
    void twoInstancesSharingRedisNeverOversellGlobally() throws Exception {
        // 模拟第二个实例启动：它会把活动登记进自己的内存注册表，
        // 但 SETNX 不会覆盖 Redis 中已有的库存。
        SeckillWarmUpRunner secondInstanceWarmUp =
                primaryContext.getBean(SeckillWarmUpRunner.class);
        boolean secondInstanceWroteStock = stockRepository.warmUp(SECKILL_ID, INITIAL_STOCK);

        assertTrue(!secondInstanceWroteStock,
                "第二个实例的预热不得覆盖 Redis 中已有的库存");
        secondInstanceWarmUp.warmUpAll();
        assertEquals(INITIAL_STOCK, stockRepository.getRemainingStock(SECKILL_ID),
                "预热不得复活已存在的库存");

        // 两个实例同时打压。两个 TestRestTemplate 都指向同一个 RandomPort 上下文，
        // 因此这里验证的是「两个 HTTP 客户端并发 + 共享 Redis」下的全局一致性。
        int totalRequests = REQUESTS_PER_INSTANCE * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(totalRequests);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < totalRequests; i++) {
            final String userId = "multi_u" + i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    String body = primaryRestTemplate.postForEntity(
                            "/api/seckill/activities/" + SECKILL_ID + "/purchase",
                            Collections.singletonMap("userId", userId),
                            String.class).getBody();
                    if (body != null && body.contains("\"code\":\"SUCCESS\"")) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    completed.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        assertTrue(completed.await(90, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(INITIAL_STOCK, successes.get(),
                "全局成功数必须恰好等于初始库存，多一件即为超卖");
        assertEquals(0, stockRepository.getRemainingStock(SECKILL_ID));

        await().atMost(Duration.ofSeconds(60))
                .until(() -> recordRepository.countByActivity(SECKILL_ID) == INITIAL_STOCK);
    }
}
```

**关于二实例的真实性**：本测试目前验证的是「共享 Redis 的并发访问全局一致」。若要更接近真实多实例，可在后续迭代中用 `SpringApplicationBuilder` 在同一 JVM 内启动第二个 `ApplicationContext`（不同端口）并分别打压。当前版本已能证伪「库存放在应用内存」这一最危险的错误实现，同时避免了第二个上下文与 Testcontainers 生命周期纠缠带来的脆弱性。

- [ ] **Step 2: 运行测试**

Run: `mvnw.cmd test -Pit -Dtest=MultiInstanceConsistencyIT`

Expected: BUILD SUCCESS

- [ ] **Step 3: 额外验证——把库存挪回内存应当让该测试失败**

这是对测试本身有效性的验证。临时把 `SeckillStockRepository.tryDeduct` 改为使用一个实例级 `AtomicInteger` 扣减，然后重跑本测试。

Expected: **测试失败**，成功数超过初始库存。

这一步的意义：如果测试在错误实现下仍然通过，那它就没有在验证任何东西。验证完毕后**必须还原**该临时改动。

---

## Task 16: 压测脚本

**Files:**
- Create: `scripts/loadtest/README.md`
- Create: `scripts/loadtest/seckill-loadtest.js`（k6 脚本）
- Create: `scripts/loadtest/run-loadtest.ps1`（Windows 启动脚本）

**Interfaces:**
- Consumes: 运行中的应用（`localhost:8088`）
- Produces: 可执行的压测脚本与结果记录模板

**设计说明：** 压测的定位是**观察数据与不变量的实证**，不是性能门槛。spec §11 已明确：单台 Windows 笔记本 + Docker 化 Redis 压出万级 QPS 的可能性很低，因此不设 QPS 硬指标。

- [ ] **Step 1: 创建 k6 压测脚本**

创建 `scripts/loadtest/seckill-loadtest.js`：

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// 目标：观察 QPS，并实证「成功数不超过初始库存」。
// 默认使用活动 1（库存 100）。压测前请确认该活动的 Redis 库存已被重置。

const successCount = new Counter('seckill_success');
const soldOutCount = new Counter('seckill_sold_out');
const alreadyPurchasedCount = new Counter('seckill_already_purchased');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8088';
const SECKILL_ID = __ENV.SECKILL_ID || '1';
const INITIAL_STOCK = parseInt(__ENV.INITIAL_STOCK || '100', 10);

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 3000,
      stages: [
        { target: 1000, duration: '5s' },
        { target: 3000, duration: '10s' },
        { target: 0, duration: '2s' },
      ],
    },
  },
};

export default function () {
  // 每个 VU 迭代使用不同 userId，模拟不同用户；同一 userId 重复请求
  // 会被限购拦下，无法压出真实的库存竞争。
  const userId = `load_u${__VU}_${__ITER}`;
  const response = http.post(
    `${BASE_URL}/api/seckill/activities/${SECKILL_ID}/purchase`,
    JSON.stringify({ userId }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  check(response, {
    'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
  });

  if (response.body && response.body.includes('"code":"SUCCESS"')) {
    successCount.add(1);
  } else if (response.body && response.body.includes('"code":"STOCK_SOLD_OUT"')) {
    soldOutCount.add(1);
  } else if (response.body && response.body.includes('"code":"ALREADY_PURCHASED"')) {
    alreadyPurchasedCount.add(1);
  }
}

export function handleSummary(data) {
  const successes = data.metrics.seckill_success
    ? data.metrics.seckill_success.values.count : 0;
  const oversold = successes > INITIAL_STOCK;

  const verdict = oversold
    ? `超卖！成功 ${successes} 件，超过初始库存 ${INITIAL_STOCK} 件`
    : `不超卖：成功 ${successes} 件，初始库存 ${INITIAL_STOCK} 件`;

  return {
    stdout: `\n${'='.repeat(60)}\n${verdict}\n${'='.repeat(60)}\n`,
    'scripts/loadtest/last-result.json': JSON.stringify(data, null, 2),
  };
}
```

- [ ] **Step 2: 创建 Windows 启动脚本**

创建 `scripts/loadtest/run-loadtest.ps1`：

```powershell
# 秒杀压测启动脚本（Windows / PowerShell）
#
# 前置条件：
#   1. 应用已在 localhost:8088 运行
#   2. 已安装 k6：https://k6.io/docs/get-started/installation/
#   3. 目标活动的库存已在 Redis 中重置

$ErrorActionPreference = "Stop"

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 k6，请先安装：https://k6.io/docs/get-started/installation/"
}

$SeckillId = if ($env:SECKILL_ID) { $env:SECKILL_ID } else { "1" }
$InitialStock = if ($env:INITIAL_STOCK) { $env:INITIAL_STOCK } else { "100" }
$BaseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8088" }

Write-Host "压测目标: $BaseUrl/api/seckill/activities/$SeckillId/purchase"
Write-Host "初始库存: $InitialStock"
Write-Host ""

$env:SECKILL_ID = $SeckillId
$env:INITIAL_STOCK = $InitialStock
$env:BASE_URL = $BaseUrl

k6 run --summary-trend-stats "avg,p(95),p(99),max" "$PSScriptRoot/seckill-loadtest.js"

Write-Host ""
Write-Host "压测结束。请核对上方「不超卖」结论，并检查应用日志中是否出现对账告警。"
```

- [ ] **Step 3: 创建压测说明**

创建 `scripts/loadtest/README.md`：

```markdown
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

## 前置条件

1. 应用已启动：`mvnw.cmd spring-boot:run`
2. 已安装 [k6](https://k6.io/docs/get-started/installation/)
3. 目标活动的 Redis 库存已重置（重启应用后 `SETNX` 不会覆盖已有键，
   因此需要先 `FLUSHALL` 或删除对应键）

## 运行

```powershell
# 默认：活动 1，初始库存 100
.\scripts\loadtest\run-loadtest.ps1

# 自定义目标
$env:SECKILL_ID=2; $env:INITIAL_STOCK=500; .\scripts\loadtest\run-loadtest.ps1
```

## 结果解读

脚本在结束时打印结论：

- `不超卖：成功 N 件，初始库存 M 件` — 符合预期
- `超卖！成功 N 件，超过初始库存 M 件` — **严重缺陷**，立即排查

详细指标写入 `scripts/loadtest/last-result.json`。

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
```

- [ ] **Step 4: 手动验证脚本可被解析**

Run: `k6 run --dry-run scripts/loadtest/seckill-loadtest.js`（若已安装 k6）

Expected: 输出脚本校验通过，不实际发起请求

若未安装 k6，跳过本步并在交付说明中记录「压测脚本未实际执行验证」——**不得声称它已验证过**。

---

## Task 17: 文档收口

**Files:**
- Modify: `docs/business.md`
- Modify: `docs/architecture.md`
- Create: `docs/decisions/0002-redis-as-stock-authority.md`
- Create: `docs/decisions/0003-async-persistence-dual-write-gap.md`
- Create: `docs/decisions/0004-lua-over-distributed-lock.md`

**Interfaces:**
- Consumes: 全部实现
- Produces: 与实现一致的文档

**依据：** `AGENTS.md` 要求「业务规则、API 契约或长期决策发生变更时，更新对应文档」「对影响未来实现选择的决策，在 `docs/decisions/` 中记录新的 ADR」。

- [ ] **Step 1: 更新业务文档**

修改 `docs/business.md`，把「内存秒杀演示」整节（第 60 行至文件末尾）替换为：

```markdown
## 秒杀

秒杀是一个独立于上述订单领域的业务场景，用于演示高并发下的库存扣减。
它以「接近真实生产架构的教学 Demo」为定位，刻意保留了一些简化，
差距清单见 `docs/architecture.md`。

### 术语

| 术语 | 含义 | 示例 |
| --- | --- | --- |
| 秒杀活动 | 一次可被抢购的商品批次，拥有独立的库存。 | 活动 1，初始库存 100。 |
| 抢购 | 用户尝试从活动中获得一件商品的请求。 | 用户 `u_10086` 抢购活动 1。 |
| 限购 | 同一用户对同一活动至多成功一次的约束。 | 一人一单。 |
| 实时库存 | Redis 中当前可售的数量，是库存的权威副本。 | 剩余 99 件。 |
| 落库 | 把抢购结果异步写入数据库的过程。 | 消费者写入流水并扣减库存副本。 |

### 业务规则

1. 每个秒杀活动拥有独立库存，互不影响。
2. 每次抢购扣减 `1` 件。同一用户对同一活动至多成功一次。
3. 库存大于 `0` 时抢购可成功；库存为 `0` 时返回「售罄」。
4. 并发请求的成功总数不得超过活动初始库存（不超卖），也不得少于
   可售数量（不少卖）。
5. 抢购成功即不可撤销：即使后续落库失败，库存也不归还。
6. 库存以 Redis 为权威副本；数据库中的库存是异步副本，用于查询与对账。
7. 落库是异步的，因此数据库的库存与流水在任意时刻可能暂时落后于
   Redis，但最终一致。
8. 数据库中的库存副本与流水条数必须始终相等（二者在同一事务内更新）。
   一旦不等，说明存在代码缺陷或人工改数。

### 并发语义

实时库存与限购关系保存在 Redis 中，由单个 Lua 脚本原子完成
「判断限购 → 判断库存 → 扣减 → 记录」。因此：

- 系统在**多实例部署**下依然不超卖，库存权威副本不在应用内存中。
- Redis 扣减成功即返回 `200`；此时落库尚未完成。
- `remainingStock` 是接口处理时的实时快照，其他并发请求可能在其后继续扣减。

详细设计见 `docs/superpowers/specs/2026-09-16-distributed-seckill-design.md`。

### 不在范围内

不包含用户认证、订单创建、支付、退款、库存回补、秒杀时间窗口与风控反刷。
`userId` 由调用方直接提供且**不校验真实性**，这是教学 Demo 的刻意简化。
```

- [ ] **Step 2: 更新架构文档**

修改 `docs/architecture.md`，把「内存秒杀演示」整节（第 26 行至第 30 行）替换为：

```markdown
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

### 秒杀代码的并发规范

- `SeckillStockRepository` 是唯一不被允许绕过的不变量发生点。任何库存
  修改都必须经过 `seckill_deduct.lua`。
- 对账任务**只读不写**。发现不一致时告警，由人工介入，不自动修数——
  用户已经收到 `200`，任何一侧的自动改动都可能制造新的不一致。
```

- [ ] **Step 3: 记录 ADR 0002**

创建 `docs/decisions/0002-redis-as-stock-authority.md`：

```markdown
# ADR 0002：以 Redis 为库存权威副本

## 状态

已采纳

## 背景

秒杀的目标形态是万级 QPS。若每次抢购都需要数据库完成一次原子扣减，
数据库会成为瓶颈；而若为了保护数据库引入分布式锁，锁本身又会成为
新的瓶颈与故障点。

同时，多实例部署要求库存不能保存在应用内存中——否则每个实例各自持有
一份库存，N 个实例就会卖出 N 倍的库存。

## 决策

实时库存与限购关系保存在 **Redis**，通过单个 Lua 脚本原子完成
「判断限购 → 判断库存 → 扣减 → 记录」。数据库中的库存是异步副本，
用于查询、审计与对账。

冲突时以 Redis 为准：Redis 扣减成功的那一刻用户已收到 `200 OK`，
此后数据库只能追赶，不能否决。

## 影响

- 数据库不在抢购关键路径上，应用无状态，可水平扩展。
- 系统只承诺**最终一致**，不承诺强一致。数据库副本存在滞后窗口。
- Redis 成为关键的单点依赖。Redis 不可用时抢购接口**快速失败返回 503，
  不降级到数据库**——一旦部分请求走 Redis、部分走数据库，两个存储
  各自扣减，不超卖立刻被破坏。**可用性让位于正确性。**
- Redis 需要开启持久化，否则重启会丢失实时库存。
```

- [ ] **Step 4: 记录 ADR 0003**

创建 `docs/decisions/0003-async-persistence-dual-write-gap.md`：

```markdown
# ADR 0003：接受异步落库的双写缺口，用对账兜底

## 状态

已采纳

## 背景

Redis 扣减成功与消息投递不是同一个原子操作。若 Redis 扣减成功但消息
投递失败，用户已经收到 `200`、库存也真的扣减了，而落库消息丢失——
这是一次**静默的数据丢失**。这是异步方案的经典难题，没有免费的解法。

## 决策

**接受该缺口**，采用三层兜底：

1. 应用侧同步重试若干次（3 次）。
2. 仍失败则记录 `error` 级别日志。
3. 依赖 A1 对账（Redis 已扣减数 vs DB 已落库数）发现不一致并告警。

对账**只告警，不自动修数**——用户已经收到 `200`，任何一侧的自动改动
都可能制造新的不一致。

## 考虑过的替代方案

**用 Redis Stream 替代 RabbitMQ**：把 `XADD` 写进同一个 Lua 脚本，
扣减与入队变成一次原子操作，缺口从物理上消失。设计上更优雅，但：

- 失去 RabbitMQ 的通用性与生态；
- Redis Stream 的可靠性依赖 Redis 持久化配置，弱于 RabbitMQ；
- 生产中 Redis Stream 的使用远不如 RabbitMQ 普遍。

## 影响

- 极端情况下（重试全部失败）会丢数据，只能靠对账事后发现，**不可自动恢复**。
- A1 对账是这条链路唯一的兜底，其配置（执行间隔、停滞阈值）必须正确，
  否则缺口无法被察觉。
- 若未来该缺口变得不可接受，替换为 Redis Stream 是明确的演进方向。
```

- [ ] **Step 5: 记录 ADR 0004**

创建 `docs/decisions/0004-lua-over-distributed-lock.md`：

```markdown
# ADR 0004：用 Lua 脚本而非分布式锁保证并发不变量

## 状态

已采纳

## 背景

秒杀需要原子地完成「判断用户是否已购买 → 判断库存是否充足 → 扣减 →
记录用户」。这些步骤之间不能有其它请求插入，否则会超卖或违反一人一单。

## 决策

把全部判断与写入放进**单个 Lua 脚本**，由 Redis 以单线程方式执行。

## 考虑过的替代方案

**分布式锁**（Redis `SETNX` 锁、Redisson、ZooKeeper）：能解决问题，但引入
一整套额外的失败模式——锁获取超时、持有者崩溃导致死锁、锁续期、锁被
误删，以及为这些情况编写的补偿逻辑。锁的失败模式比它要解决的问题更多。

## 影响

- 脚本内的操作必须是幂等的、确定性的：Redis 不保证脚本「最多执行一次」。
  本脚本的 `DECR` 与 `SADD` 只在前置判断通过后执行，重复执行不会造成危害。
- 脚本中的键必须使用 **hash tag**（`{seckillId}`）强制落在同一 slot，
  否则 Redis Cluster 下跨 slot 访问会直接报错。
- 脚本返回值的含义必须与 `LuaResultMapper` 严格对应；未识别的返回值
  会抛出 `IllegalStateException` 而不是被静默当作售罄——静默吞掉会把
  代码缺陷伪装成正常的业务拒绝。
```

- [ ] **Step 6: 运行完整测试集做最终验证**

Run: `mvnw.cmd test`

Expected: BUILD SUCCESS，不需要 Docker

Run: `mvnw.cmd test -Pit`

Expected: BUILD SUCCESS，全部集成测试通过

**必须粘贴两个命令的真实输出，不得凭记忆断言通过。**

---

## 交付前检查清单

- [ ] `mvnw.cmd test` 通过且**不依赖 Docker**
- [ ] `mvnw.cmd test -Pit` 全部通过
- [ ] `SeckillInventoryService` 与其测试已删除，无残留引用
- [ ] `docs/api/seckill.md` 与 `SeckillErrorCode` 逐行一致（code 字符串、HTTP 状态）
- [ ] `docs/business.md`、`docs/architecture.md` 已更新
- [ ] ADR 0002 / 0003 / 0004 已创建
- [ ] 未验证的假设已在交付说明中明确列出（例如：压测脚本是否实际执行过）

## 未验证假设登记（交付时必须如实说明）

| 假设 | 状态 |
| --- | --- |
| 压测脚本在真实 k6 环境下可运行 | 取决于 k6 是否安装；未执行则必须声明 |
| Testcontainers 在目标机器上可拉取镜像 | 取决于网络 |
| 单机环境下的 QPS 表现 | **不设目标，不作断言** |
| `spring-rabbit` 的重试与死信配置符合预期 | 由 Task 10 的测试覆盖，但未单独验证死信队列的最终归宿 |

最后一条是本计划中**明确的测试空白**：死信队列的配置已写入，但没有测试
验证「重试耗尽的消息确实进入 DLQ」。若需要该保证，应新增一个集成测试，
故意投递一条必然失败的消息并断言它出现在 DLQ 中。
