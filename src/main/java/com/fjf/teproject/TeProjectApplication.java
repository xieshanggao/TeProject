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
