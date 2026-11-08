package com.yuwatang.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BrandEnglishMigrationTest {
    final ObjectMapper json = new ObjectMapper();

    @Test void upgradeRenamesPublishedAndDraftWithoutPublishingOtherDraftEdits() throws Exception {
        var source = database();
        var jdbc = new JdbcTemplate(source);
        String published = json.writeValueAsString(Map.of("brandEnglish", "YU HE TANG", "brandStory", "已发布介绍", "galleryGroups", new String[]{"published-image"}));
        String draft = json.writeValueAsString(Map.of("brandEnglish", " yu he tang ", "brandStory", "未发布修改", "galleryGroups", new String[]{"draft-image"}));
        jdbc.update("INSERT INTO site_config VALUES('brand',7,?,?)", published, draft);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM site_config WHERE id='brand'");
        assertEquals(8, row.get("revision"));
        for (var key : new String[]{"published_json", "draft_json"}) {
            var expected = json.readTree(key.equals("published_json") ? published : draft);
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected).put("brandEnglish", "YU Health");
            assertEquals(expected, json.readTree((String) row.get(key)));
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertEquals(row, jdbc.queryForMap("SELECT * FROM site_config WHERE id='brand'"), "Rerunning migrations must not change revision or data");
    }

    @Test void upgradePreservesCustomBrandNamesAndOnlyChangesTheLegacyField() throws Exception {
        var source = database();
        var jdbc = new JdbcTemplate(source);
        String published = "{\"brandEnglish\":\"YU Health\",\"brandStory\":\"YU HE TANG is a historical quote\"}";
        String draft = "{\"brandEnglish\":\"Custom English Brand\",\"version\":12}";
        jdbc.update("INSERT INTO site_config VALUES('brand',12,?,?)", published, draft);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM site_config WHERE id='brand'");
        assertEquals(12, row.get("revision"));
        assertEquals(published, row.get("published_json"));
        assertEquals(draft, row.get("draft_json"));
    }

    @Test void upgradeRenamesDraftWithoutOverwritingPublishedContent() throws Exception {
        var source = database();
        var jdbc = new JdbcTemplate(source);
        String published = "{\"brandEnglish\":\"YU Health\",\"brandStory\":\"保持发布内容\"}";
        jdbc.update("INSERT INTO site_config VALUES('brand',3,?,?)", published, "{\"brandEnglish\":\"YU HE TANG\",\"brandStory\":\"待发布内容\"}");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM site_config WHERE id='brand'");
        assertEquals(4, row.get("revision"));
        assertEquals(published, row.get("published_json"));
        assertEquals("YU Health", json.readTree((String) row.get("draft_json")).path("brandEnglish").asText());
        assertEquals("待发布内容", json.readTree((String) row.get("draft_json")).path("brandStory").asText());
    }

    private DriverManagerDataSource database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:brand_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("11").load().migrate();
        return source;
    }
}
