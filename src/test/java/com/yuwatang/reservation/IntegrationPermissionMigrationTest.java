package com.yuwatang.reservation;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationPermissionMigrationTest {
    @Test void upgradeOnlyGrantsBuiltinHeadquartersAndPreservesExplicitRevocations()throws Exception {
        for(boolean revoked:new boolean[]{false,true}){
            String url="jdbc:h2:mem:extension_upgrade_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            Flyway.configure().dataSource(url,"sa","").target("17").load().migrate();
            try(Connection connection=DriverManager.getConnection(url,"sa","");Statement sql=connection.createStatement()){
                sql.executeUpdate("INSERT INTO permission(code,name,module) SELECT 'integrations:read','查看扩展配置','系统' WHERE NOT EXISTS (SELECT 1 FROM permission WHERE code='integrations:read')");
                sql.executeUpdate("INSERT INTO app_role VALUES('hq','总部管理员','GLOBAL',TRUE),('custom-hq','自定义总部','GLOBAL',FALSE),('manager','门店','STORE',TRUE)");
                sql.executeUpdate("INSERT INTO role_permission VALUES('custom-hq','integrations:read')");
                if(!revoked)sql.executeUpdate("INSERT INTO role_permission VALUES('hq','integrations:read')");
                Flyway.configure().dataSource(url,"sa","").load().migrate();
                try(ResultSet grant=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_id='hq' AND permission_code='integrations:write'")){
                    assertTrue(grant.next());assertEquals(revoked?0:1,grant.getInt(1));
                }
                try(ResultSet others=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_id<>'hq' AND permission_code='integrations:write'")){
                    assertTrue(others.next());assertEquals(0,others.getInt(1));
                }
                try(ResultSet reader=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_id='custom-hq' AND permission_code='integrations:read'")){
                    assertTrue(reader.next());assertEquals(1,reader.getInt(1));
                }
                try(ResultSet catalog=sql.executeQuery("SELECT COUNT(*) FROM permission WHERE code IN ('integrations:read','integrations:write') AND module='扩展与配置'")){
                    assertTrue(catalog.next());assertEquals(2,catalog.getInt(1));
                }
            }
        }
    }
}
