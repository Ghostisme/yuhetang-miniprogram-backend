package com.yuwatang.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WechatMenuMigrationTest {
    @Test void upgradeAddsMenuRightsOnlyToBuiltinHeadquartersAndPreservesExistingSettings(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:menu_upgrade_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("9").load().migrate();
        var d=new Data(new JdbcTemplate(source),new ObjectMapper());
        d.update("INSERT INTO permission VALUES('legacy:read','既有权限','既有模块')");
        d.update("INSERT INTO app_role VALUES('hq','总部管理员','GLOBAL',TRUE)");
        d.update("INSERT INTO app_role VALUES('custom-global','自定义总部岗位','GLOBAL',FALSE)");
        d.update("INSERT INTO app_role VALUES('manager','门店店长','STORE',TRUE)");
        for(String role:new String[]{"hq","custom-global","manager"})d.update("INSERT INTO role_permission VALUES(?,'legacy:read')",role);
        d.update("INSERT INTO app_setting VALUES('keep-config','原有配置')");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertEquals(3,d.count("SELECT COUNT(*) FROM role_permission WHERE role_id='hq' AND permission_code LIKE 'wechat-menu:%'"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM role_permission WHERE role_id='custom-global'"));
        assertEquals(1,d.count("SELECT COUNT(*) FROM role_permission WHERE role_id='manager'"));
        assertEquals(3,d.count("SELECT COUNT(*) FROM role_permission WHERE permission_code='legacy:read'"));
        assertEquals("原有配置",d.one("SELECT * FROM app_setting WHERE setting_key='keep-config'").get("settingValue"));
        assertEquals(0,Data.integer(d.one("SELECT * FROM wechat_service_menu"),"revision",-1));
    }
}
