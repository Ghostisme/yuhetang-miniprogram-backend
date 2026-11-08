package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Rename the legacy English brand in published content and drafts independently. */
public class V12__yu_health_brand_english extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var select = connection.prepareStatement("SELECT revision,published_json,draft_json FROM site_config WHERE id='brand'");
             var rows = select.executeQuery()) {
            if (!rows.next()) return;
            String published = rows.getString("published_json"), draft = rows.getString("draft_json");
            String nextPublished = rename(published), nextDraft = rename(draft);
            if (published.equals(nextPublished) && draft.equals(nextDraft)) return;
            try (var update = connection.prepareStatement("UPDATE site_config SET revision=?,published_json=?,draft_json=? WHERE id='brand'")) {
                update.setInt(1, rows.getInt("revision") + 1);
                update.setString(2, nextPublished);
                update.setString(3, nextDraft);
                update.executeUpdate();
            }
        }
    }

    private String rename(String json) throws Exception {
        var mapper = new ObjectMapper();
        var content = mapper.readTree(json);
        if (content instanceof ObjectNode object && object.path("brandEnglish").asText().matches("(?i)\\s*YU\\s+HE\\s+TANG\\s*")) {
            object.put("brandEnglish", "YU Health");
            return mapper.writeValueAsString(object);
        }
        return json;
    }
}
