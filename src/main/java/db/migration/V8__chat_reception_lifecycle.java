package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.sql.*;
import java.util.*;

/** Preserve ended conversations as separate records; only one live conversation per customer/store. */
public class V8__chat_reception_lifecycle extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection connection=context.getConnection();
        boolean h2=connection.getMetaData().getDatabaseProductName().equals("H2");
        try(Statement sql=connection.createStatement()){
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN assigned_admin_id VARCHAR(64)");
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN assigned_admin_name VARCHAR(80)");
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN accepted_at VARCHAR(40)");
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN ended_at VARCHAR(40)");
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN ended_by VARCHAR(80)");
            sql.execute("ALTER TABLE chat_conversation ADD COLUMN active_key VARCHAR(8) DEFAULT 'LIVE'");
            sql.execute("UPDATE chat_conversation SET active_key=NULL,ended_at=updated_at,read_seq=last_seq,reply_remaining=0 WHERE status='RESOLVED'");
            // V2 used an unnamed UNIQUE. Discover its database-generated name instead of guessing it.
            Map<String,Set<String>> unique=new LinkedHashMap<>();
            if(h2){
                try(ResultSet rows=sql.executeQuery("SELECT k.CONSTRAINT_NAME,k.COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON t.CONSTRAINT_CATALOG=k.CONSTRAINT_CATALOG AND t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND t.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE t.TABLE_SCHEMA=CURRENT_SCHEMA() AND t.TABLE_NAME='chat_conversation' AND t.CONSTRAINT_TYPE='UNIQUE'")){
                    while(rows.next())unique.computeIfAbsent(rows.getString(1),k->new HashSet<>()).add(rows.getString(2).toLowerCase(Locale.ROOT));
                }
            }else{
                try(ResultSet rows=connection.getMetaData().getIndexInfo(connection.getCatalog(),null,"chat_conversation",true,false)){
                    while(rows.next())if(rows.getString("INDEX_NAME")!=null&&rows.getString("COLUMN_NAME")!=null)
                        unique.computeIfAbsent(rows.getString("INDEX_NAME"),k->new HashSet<>()).add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            String legacy=unique.entrySet().stream().filter(e->e.getValue().equals(Set.of("openid","channel","store_id"))).map(Map.Entry::getKey).findFirst().orElseThrow(()->new SQLException("Legacy chat conversation unique constraint not found"));
            if(!legacy.matches("[A-Za-z0-9_$]+"))throw new SQLException("Unexpected chat constraint identifier");
            sql.execute("CREATE UNIQUE INDEX ux_chat_live ON chat_conversation(openid,channel,store_id,active_key)");
            sql.execute("ALTER TABLE chat_conversation DROP "+(h2?"CONSTRAINT \""+legacy+"\"":"INDEX `"+legacy+"`"));
            sql.execute("CREATE INDEX ix_chat_history ON chat_conversation(openid,channel,store_id,created_at)");
        }
    }
}
