package com.dsh.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class VerificationStorageUpgradeIntegrationTest {
    @Autowired DataSource dataSource;
    interface Check { void run(Connection c,String schema) throws Exception; }
    void inSchema(Check check) throws Exception {
        String schema="c33a_"+UUID.randomUUID().toString().replace("-","");
        try(var c=dataSource.getConnection()) {
            String originalSchema=c.getSchema();
            try(var s=c.createStatement()) {s.execute("create schema "+schema);} c.setSchema(schema);
            try { check.run(c,schema); }
            finally { c.setAutoCommit(true);try(var s=c.createStatement()){s.execute("drop schema "+schema+" cascade");} c.setSchema(originalSchema); }
        }
    }
    static void table(Connection c,String type) throws SQLException {
        try(var s=c.createStatement()){s.execute("create table verifications (id text primary key, command_summary "+type+" not null)");}
    }
    static String type(Connection c) throws SQLException {
        try(var s=c.createStatement();var rows=s.executeQuery("select data_type from information_schema.columns where table_schema=current_schema() and table_name='verifications' and column_name='command_summary'")){assertTrue(rows.next());return rows.getString(1);}
    }
    @Test void widensLegacyColumnWithoutChangingRowsAndAllowsFullUnicodeCommand() throws Exception {
        inSchema((c,schema)-> {
            table(c,"varchar(256)");try(var s=c.createStatement()){s.execute("insert into verifications values ('old','unchanged')");}
            VerificationStorageConfiguration.widenCommandColumn(c);assertEquals("text",type(c));
            String command="汉字 ' and space\n".repeat(100);
            try(var s=c.prepareStatement("insert into verifications values ('long',?)")){s.setString(1,command);s.executeUpdate();}
            try(var s=c.createStatement();var r=s.executeQuery("select command_summary from verifications where id='long'")){assertTrue(r.next());assertEquals(command,r.getString(1));}
            try(var s=c.createStatement();var r=s.executeQuery("select command_summary from verifications where id='old'")){assertTrue(r.next());assertEquals("unchanged",r.getString(1));}
            try(var s=c.createStatement()){assertThrows(SQLException.class,()->s.execute("insert into verifications values ('null',null)"));}
        });
    }
    @Test void textUpgradeIsIdempotent() throws Exception {
        inSchema((c,schema)-> {table(c,"text");VerificationStorageConfiguration.widenCommandColumn(c);VerificationStorageConfiguration.widenCommandColumn(c);assertEquals("text",type(c));});
    }
    @Test void freshSchemaIsLeftForHibernateToCreate() throws Exception {
        inSchema((c,schema)-> {VerificationStorageConfiguration.widenCommandColumn(c);try(var s=c.createStatement();var r=s.executeQuery("select count(*) from information_schema.tables where table_schema=current_schema()")){assertTrue(r.next());assertEquals(0,r.getInt(1));}});
    }
    @Test void unexpectedColumnShapeIsRejectedWithoutDestructiveConversion() throws Exception {
        inSchema((c,schema)-> {table(c,"varchar(128)");assertThrows(SQLException.class,()->VerificationStorageConfiguration.widenCommandColumn(c));assertEquals("character varying",type(c));assertTrue(c.getAutoCommit());});
    }
    @Test void lockedTableTimesOutAndLeavesLegacyColumnUnchanged() throws Exception {
        inSchema((c,schema)-> {
            table(c,"varchar(256)");
            try(var lock=dataSource.getConnection()) {
                String originalSchema=lock.getSchema();
                lock.setSchema(schema);lock.setAutoCommit(false);
                try(var s=lock.createStatement()){s.execute("lock table verifications in access exclusive mode");}
                try {var failure=assertThrows(SQLException.class,()->VerificationStorageConfiguration.widenCommandColumn(c));assertEquals("55P03",failure.getSQLState());}
                finally {lock.rollback();lock.setAutoCommit(true);lock.setSchema(originalSchema);}
            }
            assertEquals("character varying",type(c));assertTrue(c.getAutoCommit());
        });
    }
    @Test void callerTransactionCannotBeCommittedByUpgrade() throws Exception {
        inSchema((c,schema)-> {c.setAutoCommit(false);assertThrows(SQLException.class,()->VerificationStorageConfiguration.widenCommandColumn(c));assertFalse(c.getAutoCommit());c.rollback();});
    }
}
