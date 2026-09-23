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
