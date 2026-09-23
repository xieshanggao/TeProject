package com.fjf.teproject.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;

/**
 * 集成测试基类：提供 Redis / MySQL / RabbitMQ 三个真实容器。
 *
 * <p>容器在静态块中启动，同一 JVM 内所有子类共享，避免每个测试类都付一次
 * 冷启动成本。容器在整个测试 JVM 结束时由 Testcontainers 的 Ryuk 回收。</p>
 *
 * <p>继承本类的测试会自动获得 {@code integration} 标签所需的运行环境，
 * 但标签必须由子类自己声明——基类不加 {@code @Tag}，否则会被误认为
 * 所有子类都被正确标记，而实际上漏标一个就会让默认测试意外依赖 Docker。</p>
 *
 * <p>容器未开启 reuse：{@code spring.sql.init.mode=always} 每次上下文启动都会
 * 重跑 schema.sql / data.sql，若容器跨运行保留状态，非幂等的种子数据会失败。</p>
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
                    .withPassword("test")
                    // 本机 Docker Desktop/WSL2 上 MySQL 8.0 初始化实测极慢（冷启动曾达
                    // 17 分钟，预热后仍需约 5 分钟），远超 Testcontainers 默认的 120 秒，
                    // 360 秒也曾在「数据库文件初始化完成、临时 server 刚起」时被掐断。
                    // 这里给到 900 秒兜底。注意必须用 withStartupTimeoutSeconds：
                    // JdbcDatabaseContainer 的等待循环只读它自己的 startupTimeoutSeconds，
                    // GenericContainer.withStartupTimeout(Duration) 对其无效。
                    .withStartupTimeoutSeconds(900)
                    // 慢的根因是 mysqld --initialize 往 Docker Desktop 的卷里 fsync 上百 MB。
                    // 两条优化：数据目录挂 tmpfs（走 WSL2 内存，省掉磁盘同步），
                    // 并关掉 binlog / doublewrite / redo 落盘等待——集成测试不需要崩溃恢复能力。
                    .withTmpFs(Collections.singletonMap("/var/lib/mysql", "rw,size=1g"))
                    .withCommand(
                            "--skip-log-bin",
                            "--innodb-doublewrite=0",
                            "--innodb-flush-log-at-trx-commit=0");

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

    /**
     * 在测试之间清空 Redis，避免上一个用例的库存残留影响下一个。
     *
     * <p>显式调用 {@code destroy()}：{@code LettuceConnectionFactory} 实现的是
     * Spring 的 {@code DisposableBean} 而非 {@code AutoCloseable}，无法用于
     * try-with-resources。</p>
     */
    protected static void resetRedis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.getConnection().serverCommands().flushAll();
        factory.destroy();
    }
}
