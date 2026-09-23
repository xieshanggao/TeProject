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
