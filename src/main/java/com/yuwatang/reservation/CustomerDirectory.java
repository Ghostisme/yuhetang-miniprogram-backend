package com.yuwatang.reservation;

import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** Scoped customer directory, including registered users who have not booked yet. */
final class CustomerDirectory {
    final Data d;
    CustomerDirectory(Data d){this.d=d;}
    static final String DIRECTORY="""
        SELECT cp.id,cp.name,cp.phone,cp.member_verified,cp.member_grade,cp.shared_note,cp.created_at,cp.original_store_id,
               sc.store_id,sc.private_note,sc.source,FALSE registration_only,
               ac.registered_name,ac.first_login_at,ac.last_login_at,ac.phone_verified_at
        FROM customer_profile cp LEFT JOIN store_customer sc ON sc.customer_profile_id=cp.id
        LEFT JOIN (SELECT customer_profile_id,MAX(display_name) registered_name,MIN(first_login_at) first_login_at,
                   MAX(last_login_at) last_login_at,MAX(phone_verified_at) phone_verified_at
                   FROM customer_account GROUP BY customer_profile_id) ac ON ac.customer_profile_id=cp.id
        UNION ALL
        SELECT a.identity_id,a.display_name,'',FALSE,'','',a.first_login_at,NULL,NULL,'','MINIPROGRAM',TRUE,
               a.display_name,a.first_login_at,a.last_login_at,NULL
        FROM customer_account a WHERE a.customer_profile_id IS NULL
        """;
    Map<String,Object> list(String store,Map<String,String> filters){
        StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object> args=new ArrayList<>();
        if(!store.isBlank()){where.append(" AND c.store_id=?");args.add(store);}
        String search=filters.getOrDefault("search","").trim();
        if(search.length()>80)throw error(400,"搜索内容过长。");
        if(!search.isBlank()){
            where.append(" AND (c.name LIKE ? OR c.registered_name LIKE ? OR c.phone LIKE ?)");
            for(int i=0;i<3;i++)args.add("%"+search.replace("%","").replace("_","").replace("\\","")+"%");
        }
        String member=filters.getOrDefault("member","");
        if(!member.isBlank()){
            if(!Set.of("MEMBER","NON_MEMBER").contains(member))throw error(400,"会员筛选条件不正确。");
            where.append(" AND c.member_verified=?");args.add(member.equals("MEMBER"));
        }
        String source=filters.getOrDefault("source","");
        if(!source.isBlank()){
            if(!CustomerProfiles.SOURCES.contains(source))throw error(400,"客户来源筛选条件不正确。");
            where.append(" AND ((c.registration_only=TRUE AND ?='MINIPROGRAM') OR EXISTS (SELECT 1 FROM customer_source cs WHERE cs.customer_profile_id=c.id AND cs.source=?) OR c.source=?)");
            args.add(source);args.add(source);args.add(source);
        }
        int page=page(filters.getOrDefault("page","1")),total=d.count("SELECT COUNT(*) FROM ("+DIRECTORY+") c"+where,args.toArray());
        args.add(30);args.add((page-1)*30);
        var rows=d.rows("SELECT c.* FROM ("+DIRECTORY+") c"+where+" ORDER BY c.created_at DESC,c.id,c.store_id LIMIT ? OFFSET ?",args.toArray());
        var ids=rows.stream().filter(r->!bool(r,"registrationOnly")).map(r->str(r,"id")).distinct().toList();
        Map<String,LinkedHashSet<String>> sources=new HashMap<>();
        if(!ids.isEmpty())for(var row:d.rows("SELECT customer_profile_id,source FROM customer_source WHERE customer_profile_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY first_seen_at,source",ids.toArray()))
            sources.computeIfAbsent(str(row,"customerProfileId"),key->new LinkedHashSet<>()).add(str(row,"source"));
        Map<String,String> storeNames=new HashMap<>();for(var storeRow:d.rows("SELECT id,name FROM store"))storeNames.put(str(storeRow,"id"),str(storeRow,"name"));
        for(var row:rows){
            var channels=new LinkedHashSet<>(sources.getOrDefault(str(row,"id"),new LinkedHashSet<>()));
            if(CustomerProfiles.SOURCES.contains(str(row,"source")))channels.add(str(row,"source"));
            if(!str(row,"firstLoginAt").isBlank())channels.add("MINIPROGRAM");
            row.put("sources",List.copyOf(channels));row.put("phone",BookingService.mask(str(row,"phone")));
            row.put("phoneVerified",!str(row,"phoneVerifiedAt").isBlank());
            row.put("memberStatus",bool(row,"memberVerified")?"MEMBER":"NON_MEMBER");
            row.put("memberStoreName",bool(row,"memberVerified")?storeNames.getOrDefault(str(row,"originalStoreId"),""):"");
        }
        return Map.of("items",rows,"total",total,"page",page,"pageSize",30);
    }
    static int page(String input){try{int page=Integer.parseInt(input);if(page<1||page>100000)throw new NumberFormatException();return page;}catch(NumberFormatException e){throw error(400,"分页参数不正确。");}}
    void save(Admin a,String id,Map<String,Object> in){
        String store=text(in,"storeId",0,64);a.store(store);var previous=d.one("SELECT * FROM customer_profile WHERE id=?",id);
        new BookingLocks(d).acquire("phone:"+str(previous,"phone"));
        previous=d.one("SELECT * FROM customer_profile WHERE id=?",id);
        if(!store.isBlank()){
            d.one("SELECT * FROM store_customer WHERE customer_profile_id=? AND store_id=?",id,store);
            d.update("UPDATE store_customer SET private_note=? WHERE customer_profile_id=? AND store_id=?",text(in,"privateNote",0,1000),id,store);
        }else if(!a.hq()||!a.permissions().contains("customers:master"))throw error(403,"只有总部可维护未关联门店的客户档案。");
        if(a.hq()&&a.permissions().contains("customers:master")){
            String name=in.containsKey("name")?CustomerProfiles.customerName(in):str(d.one("SELECT name FROM customer_profile WHERE id=?",id),"name");
            String home=in.containsKey("originalStoreId")?text(in,"originalStoreId",0,64):str(previous,"originalStoreId");
            if(!home.isBlank())d.one("SELECT id FROM store WHERE id=?",home);
            d.update("UPDATE customer_profile SET name=?,member_verified=?,member_grade=?,shared_note=?,original_store_id=? WHERE id=?",name,bool(in,"memberVerified"),bool(in,"memberVerified")?text(in,"memberGrade",0,60):"",text(in,"sharedNote",0,300),home.isBlank()?null:home,id);
        }
        d.audit(a,store.isBlank()?null:store,"customer.update",id,"更新客户称呼、会员标签与门店私有跟进；来源由系统记录");
    }
    Map<String,Object> phone(Admin a,String id){
        var profile=d.one("SELECT phone FROM customer_profile WHERE id=?",id);
        if(!a.hq())d.one("SELECT store_id FROM store_customer WHERE customer_profile_id=? AND store_id=?",id,a.storeId());
        d.audit(a,a.hq()?null:a.storeId(),"customer.phone",id,"查看客户完整授权/联系手机号");return Map.of("phone",profile.get("phone"));
    }
}
