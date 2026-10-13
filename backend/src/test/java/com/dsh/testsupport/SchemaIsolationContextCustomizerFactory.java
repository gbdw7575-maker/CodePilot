package com.dsh.testsupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.context.event.ContextClosedEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * MA-10 测试基础设施：为每个不同的 Spring 测试上下文分配独立 PostgreSQL schema。
 *
 * <p>背景：测试使用 create-drop + 共享库 dsh_test，多个不同配置的 ApplicationContext
 * 会被 Spring 缓存并在整个 JVM 内同时存活；编排开关下各上下文的 @Scheduled 补偿扫描
 * （worker 重投 / await 超时 / 租约回收）会扫到彼此的表，跨上下文"接管"对方的任务，
 * 在全套高负载下产生时序性失败。通过 Hikari connection-init-sql 把每个上下文的全部
 * 连接固定到私有 schema（search_path），后台扫描相互不可见；上下文关闭时删除 schema。</p>
 *
 * <p>自定义器为无状态单例，不改变上下文缓存键（相同 MergedContextConfiguration 的类
 * 仍共享同一上下文）；不同实际上下文使用独立随机 schema。</p>
 */
public class SchemaIsolationContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        return Isolator.INSTANCE;
    }

    static final class Isolator implements ContextCustomizer {

        static final Isolator INSTANCE = new Isolator();
        private static final Logger log = LoggerFactory.getLogger(Isolator.class);

        @Override
        public void customizeContext(ConfigurableApplicationContext ctx,
                                     MergedContextConfiguration mergedConfig) {
            String url = ctx.getEnvironment().getProperty("spring.datasource.url");
            if (url == null || !url.startsWith("jdbc:postgresql:")) {
                return;
            }
            // Spring already shares cached contexts. Distinct live contexts must never share a schema,
            // even if a customizer's toString omits part of its actual cache identity.
            String schema = "t_" + java.util.UUID.randomUUID().toString().replace("-", "");
            String user = ctx.getEnvironment().getProperty("spring.datasource.username", "dsh");
            String pass = ctx.getEnvironment().getProperty("spring.datasource.password", "dsh");
            adminExecute(url, user, pass, "CREATE SCHEMA " + schema, true);
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "schemaIsolation-" + schema,
                    Map.of("spring.datasource.hikari.connection-init-sql",
                            "SET search_path TO " + schema + ", public")));
            // ContextClosedEvent precedes scheduler and datasource destruction. Register first so
            // reverse singleton destruction removes the schema LAST, after all background users.
            if (!(ctx.getBeanFactory() instanceof org.springframework.beans.factory.support.DefaultListableBeanFactory factory))
                throw new IllegalStateException("Unsupported test bean factory");
            factory.registerDisposableBean("schemaIsolationCleanup-" + schema, () ->
                    adminExecute(url,user,pass,"DROP SCHEMA IF EXISTS " + schema + " CASCADE",false));
            log.info("测试 schema 隔离：{}", schema);
        }

        private static void adminExecute(String url, String user, String pass,
                                         String sql, boolean tolerateDuplicate) {
            try (Connection c = DriverManager.getConnection(url, user, pass);
                 Statement st = c.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                // 42P06=duplicate_schema：上次 JVM 异常退出残留，复用即可（关闭时仍会清理）
                if (tolerateDuplicate && "42P06".equals(e.getSQLState())) {
                    return;
                }
                throw new IllegalStateException("测试 schema 管理失败: " + sql, e);
            }
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Isolator;
        }

        @Override
        public int hashCode() {
            return Isolator.class.hashCode();
        }

        @Override
        public String toString() {
            return "SchemaIsolationContextCustomizer";
        }
    }
}
