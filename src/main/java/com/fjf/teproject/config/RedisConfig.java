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
