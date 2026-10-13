package com.dsh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;

/**
 * @EnableAsync：S2 启用，使 EpisodicMemoryService.ingest（Run 终态后摘要入库）异步执行不阻塞主 Loop。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class DshApplication {

    public static void main(String[] args) {
        SpringApplication.run(DshApplication.class, args);
    }

    /**
     * 显式使用 JPA 事务管理器：保证幂等占位、资源写入与响应记录
     * 在同一 EntityManager 事务中提交（否则默认会注册 JDBC 事务管理器，
     * JPA 脏检查不会随事务提交而 flush）。
     */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
