package com.yuwatang.reservation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Component
public class Data {
    final JdbcTemplate jdbc; final ObjectMapper json;
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    Data(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    List<Map<String,Object>> rows(String sql,Object...args){return jdbc.query(sql,(rs,n)->{Map<String,Object> row=new LinkedHashMap<>();var md=rs.getMetaData();for(int i=1;i<=md.getColumnCount();i++){String label=md.getColumnLabel(i).toLowerCase(Locale.ROOT);StringBuilder key=new StringBuilder();boolean upper=false;for(char c:label.toCharArray()){if(c=='_'){upper=true;continue;}key.append(upper?Character.toUpperCase(c):c);upper=false;}row.put(key.toString(),rs.getObject(i));}return row;},args);}
    Map<String,Object> one(String sql,Object...args){var list=rows(sql,args);if(list.isEmpty())throw error(404,"记录不存在或不在您的权限范围内。");return list.get(0);}
    int update(String sql,Object...args){return jdbc.update(sql,args);}
    int count(String sql,Object...args){Integer n=jdbc.queryForObject(sql,Integer.class,args);return n==null?0:n;}
    String stringify(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("数据格式不正确。");}}
    Map<String,Object> object(Object value){try{if(value instanceof Map<?,?>)return json.convertValue(value,new TypeReference<>(){});return json.readValue(String.valueOf(value),new TypeReference<>(){});}catch(Exception e){throw error(400,"资料格式不正确。");}}
    List<String> strings(Object value){if(value instanceof Collection<?> c)return c.stream().map(String::valueOf).toList();return List.of();}
    static String id(){return UUID.randomUUID().toString();}
    static String now(){return OffsetDateTime.now(ZONE).toString();}
    static String str(Map<String,?> row,String key){return Objects.toString(row.get(key),"");}
    static String text(Map<String,?> row,String key,int min,int max){String s=str(row,key).trim();if(s.length()<min||s.length()>max)throw error(400,"请检查字段："+key);return s;}
    static int integer(Map<String,?> row,String key,int def){try{return row.containsKey(key)?Integer.parseInt(row.get(key).toString()):def;}catch(Exception e){throw error(400,"数字格式不正确："+key);}}
    static boolean bool(Map<String,?> row,String key){return Boolean.parseBoolean(str(row,key));}
    static ResponseStatusException error(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
    void audit(Admin actor,String store,String action,String target,String detail){update("INSERT INTO audit_log VALUES(?,?,?,?,?,?,?,?)",id(),actor.id(),actor.name(),store,action,target,detail,now());}
}

record Admin(String id,String name,String storeId,Set<String> roleIds,Set<String> permissions,boolean hq){
    void store(String requested){if(!hq&&!Objects.equals(storeId,requested))throw Data.error(404,"记录不存在或不在您的权限范围内。");}
    void require(String p){if(!p.isEmpty()&&!permissions.contains(p))throw Data.error(403,"当前角色没有此操作权限。");}
}
