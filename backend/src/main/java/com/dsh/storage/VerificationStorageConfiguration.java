package com.dsh.storage;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/** One idempotent widening before Hibernate/recovery. An upgrade failure must abort startup. */
@Configuration(proxyBeanMethods = false)
public class VerificationStorageConfiguration {
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor verificationStorageBeforeJpa() {
        return new EntityManagerFactoryDependsOnPostProcessor("verificationCommandStorageUpgrade");
    }

    @Bean
    InitializingBean verificationCommandStorageUpgrade(DataSource dataSource) {
        return () -> { try (var connection = dataSource.getConnection()) { widenCommandColumn(connection); } };
    }

    static void widenCommandColumn(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Verification storage upgrade requires its own transaction");
        connection.setAutoCommit(false);
        try {
            String schema;
            try (var statement=connection.createStatement();var rows=statement.executeQuery("select current_schema()")) {
                if (!rows.next() || (schema=rows.getString(1))==null) throw new SQLException("No current database schema");
            }
            String type=null; Integer length=null;
            try (var statement=connection.prepareStatement("select data_type, character_maximum_length from information_schema.columns where table_schema=? and table_name='verifications' and column_name='command_summary'")) {
                statement.setString(1,schema);
                try (var rows=statement.executeQuery()) {
                    if(rows.next()) { type=rows.getString(1); length=(Integer)rows.getObject(2); }
                }
            }
            if (type==null || "text".equals(type)) { connection.commit(); return; }
            if (!"character varying".equals(type) || !Integer.valueOf(256).equals(length))
                throw new SQLException("Unsupported verification command column; manual migration review required");
            // Identifier is database metadata, quoted defensively. No user command or credential enters SQL.
            String table="\""+schema.replace("\"","\"\"")+"\".\"verifications\"";
            try (var statement=connection.createStatement()) {
                statement.execute("set local lock_timeout = '5s'");
                statement.execute("set local statement_timeout = '30s'");
                statement.execute("alter table "+table+" alter column command_summary type text");
            }
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally { connection.setAutoCommit(true); }
    }
}
