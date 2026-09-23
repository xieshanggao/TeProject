package com.fjf.teproject;

import com.fjf.teproject.support.IntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 验证应用上下文可以完整启动。
 *
 * <p>标记为 integration：加入数据库与消息中间件后，上下文启动需要真实的
 * MySQL / Redis / RabbitMQ，因此本测试不再属于默认测试集。</p>
 *
 * <p>必须继承 {@link IntegrationTestBase} 而不是裸用 {@code @SpringBootTest}：
 * 启动预热（SeckillWarmUpRunner）会连 Redis，若沿用 application.properties 的
 * localhost 默认值，这个测试就会依赖「本机 docker compose 恰好起着」，
 * 而不是 Testcontainers 起的容器。</p>
 */
@Tag("integration")
class TeProjectApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
