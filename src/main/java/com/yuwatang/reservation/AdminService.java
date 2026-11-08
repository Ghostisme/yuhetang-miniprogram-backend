package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.LocalDate;
import static com.yuwatang.reservation.Data.*;

@Service
class AdminService {
    final Data d;final AuthService auth;final CatalogService catalog;final BookingService bookings;
    AdminService(Data d,AuthService auth,CatalogService catalog,BookingService bookings){this.d=d;this.auth=auth;this.catalog=catalog;this.bookings=bookings;}
    String scope(Admin a,String requested){if(a.hq()){if(requested!=null&&!requested.isBlank())catalog.store(requested);return requested==null?"":requested;}if(requested!=null&&!requested.isBlank())a.store(requested);return a.storeId();}
    List<Map<String,Object>> therapists(Admin a,String requested){String store=scope(a,requested);return store.isEmpty()?catalog.allTherapists():catalog.therapists(store,false);}
    record Query(String where,List<Object> args){}
    Query appointmentQuery(Admin a,Map<String,String> filters){String store=scope(a,filters.get("storeId"));StringBuilder sql=new StringBuilder(" WHERE 1=1");List<Object> args=new ArrayList<>();if(!store.isEmpty()){sql.append(" AND store_id=?");args.add(store);}String[] keys={"status","from","to"},fields={"status=?","booking_date>=?","booking_date<=?"};for(int i=0;i<keys.length;i++){String val=filters.get(keys[i]);if(val!=null&&!val.isBlank()){if(i>0)LocalDate.parse(val);sql.append(" AND ").append(fields[i]);args.add(val);}}String q=filters.get("search");if(q!=null&&!q.isBlank()){if(q.length()>80)throw error(400,"搜索条件过长。");sql.append(" AND (customer_name LIKE ? OR phone LIKE ? OR id LIKE ?)");for(int i=0;i<3;i++)args.add("%"+q.trim().replace("%","").replace("_","")+"%");}return new Query(sql.toString(),args);}
    Map<String,Object> appointments(Admin a,Map<String,String> filters){Query q=appointmentQuery(a,filters);int page=Math.max(1,Math.min(100000,parse(filters.get("page"),1))),size=Math.max(1,Math.min(100,parse(filters.get("pageSize"),20)));int total=d.count("SELECT COUNT(*) FROM appointment"+q.where,q.args.toArray());List<Object> args=new ArrayList<>(q.args);args.add(size);args.add((page-1)*size);return Map.of("items",bookings.views(d.rows("SELECT * FROM appointment"+q.where+" ORDER BY created_at DESC LIMIT ? OFFSET ?",args.toArray()),true),"total",total,"page",page,"pageSize",size);}
    int parse(String s,int def){try{return s==null?def:Integer.parseInt(s);}catch(Exception e){throw error(400,"分页参数不正确。");}}
    Map<String,Object> dashboard(Admin a,String store){
        String s=scope(a,store);String where=s.isEmpty()?"":" WHERE store_id=?";
        Object[] args=s.isEmpty()?new Object[]{}:new Object[]{s};
        var by=d.rows("SELECT status,COUNT(*) total FROM appointment"+where+" GROUP BY status",args);
        var recent=bookings.views(d.rows("SELECT * FROM appointment"+where+" ORDER BY created_at DESC LIMIT 6",args),true);
        LocalDate today=LocalDate.now(ZONE);List<Object> trendArgs=new ArrayList<>(Arrays.asList(args));
        trendArgs.add(today.minusDays(6).toString());trendArgs.add(today.plusDays(1).toString());
        Map<String,Integer> days=new HashMap<>();
        for(var row:d.rows("SELECT SUBSTRING(created_at,1,10) day_key,COUNT(*) total FROM appointment"+(s.isEmpty()?" WHERE":" WHERE store_id=? AND")+" created_at>=? AND created_at<? GROUP BY SUBSTRING(created_at,1,10)",trendArgs.toArray()))days.put(str(row,"dayKey"),integer(row,"total",0));
        List<Map<String,Object>> trend=new ArrayList<>();
        for(int i=6;i>=0;i--){String day=today.minusDays(i).toString();trend.add(Map.of("date",day,"count",days.getOrDefault(day,0)));}
        return Map.of("counts",by,"recent",recent,"trend",trend,"sources",d.rows("SELECT source,COUNT(*) total FROM appointment"+where+" GROUP BY source",args));
    }
    List<Map<String,Object>> users(Admin a){return d.rows("SELECT id,username,display_name,store_id,enabled,created_at FROM app_user"+(a.hq()?"":" WHERE store_id=?")+" ORDER BY username",a.hq()?new Object[]{}:new Object[]{a.storeId()}).stream().peek(u->u.put("roleIds",d.rows("SELECT role_id FROM user_role WHERE user_id=?",u.get("id")).stream().map(r->r.get("roleId")).toList())).toList();}
    List<Map<String,Object>> roles(Admin a){return d.rows("SELECT * FROM app_role"+(a.hq()?"":" WHERE data_scope='STORE'")+" ORDER BY builtin DESC,name").stream().peek(r->r.put("permissions",d.rows("SELECT permission_code FROM role_permission WHERE role_id=?",r.get("id")).stream().map(p->p.get("permissionCode")).toList())).toList();}
    @Transactional public void saveUser(Admin a,Map<String,Object> in){String uid=text(in,"id",0,64);var existing=uid.isEmpty()?null:d.one("SELECT * FROM app_user WHERE id=?",uid);String store=text(in,"storeId",0,64);if(!store.isEmpty())catalog.store(store);List<String> roles=d.strings(in.get("roleIds"));if(roles.isEmpty()||roles.size()>5)throw error(400,"请配置1至5个角色。");boolean global=false;for(String r:roles){var role=d.one("SELECT * FROM app_role WHERE id=?",r);global|="GLOBAL".equals(str(role,"dataScope"));}
        if(!a.hq()){a.store(store);if(existing!=null)a.store(str(existing,"storeId"));if(!roles.equals(List.of("frontdesk"))||uid.equals(a.id()))throw error(403,"店长只能管理本店前台账号，不能调整总部、店长或自身份权限。");if(existing!=null&&!d.rows("SELECT role_id FROM user_role WHERE user_id=?",uid).stream().allMatch(r->str(r,"roleId").equals("frontdesk")))throw error(403,"不能修改其他管理角色账号。");}
        if(global&&!store.isEmpty()||!global&&store.isEmpty())throw error(400,"总部角色不绑定门店；门店角色必须绑定门店。");
        if(uid.equals(a.id())&&(!bool(in,"enabled")||!roles.contains("hq")))throw error(400,"请保留自己的有效总部权限，由其他总部管理员协助调整。");String name=text(in,"displayName",1,60),username=text(in,"username",3,40),password=text(in,"password",0,100);if(!username.matches("[A-Za-z0-9_.-]+"))throw error(400,"账号仅允许字母、数字、点、横线、下划线。");if(!password.isEmpty()&&password.length()<10)throw error(400,"密码至少10位。");
        if(existing==null){if(password.isBlank())throw error(400,"新账号需要初始密码。");uid=id();d.update("INSERT INTO app_user VALUES(?,?,?,?,?,?,?,?)",uid,username,name,auth.passwords.encode(password),store.isEmpty()?null:store,bool(in,"enabled"),0,now());}else{d.update("UPDATE app_user SET username=?,display_name=?,store_id=?,enabled=?,token_version=token_version+1 WHERE id=?",username,name,store.isEmpty()?null:store,bool(in,"enabled"),uid);if(!password.isEmpty())d.update("UPDATE app_user SET password_hash=? WHERE id=?",auth.passwords.encode(password),uid);}
        d.update("DELETE FROM user_role WHERE user_id=?",uid);for(String r:roles)d.update("INSERT INTO user_role VALUES(?,?)",uid,r);d.audit(a,store,"user.save",uid,"账号与角色变更；旧登录失效");
    }
    @Transactional public void saveRole(Admin a,Map<String,Object> in){
        hq(a);String rid=text(in,"id",0,64),name=text(in,"name",1,60),scope=text(in,"dataScope",1,10);
        if(!Set.of("STORE","GLOBAL").contains(scope))throw error(400,"数据范围无效。");
        Set<String> perms=RbacPolicy.normalize(d,d.strings(in.get("permissions")),scope);
        if(rid.isEmpty()){
            rid=id();d.update("INSERT INTO app_role VALUES(?,?,?,?)",rid,name,scope,false);
        }else{
            var r=d.one("SELECT * FROM app_role WHERE id=? FOR UPDATE",rid);
            if(bool(r,"builtin")&&"GLOBAL".equals(str(r,"dataScope")))throw error(400,"系统总部管理员保留完整权限，其他总部岗位请新建角色配置。");
            if(bool(r,"builtin")&&!str(r,"dataScope").equals(scope))throw error(400,"内置门店角色的数据范围固定为本店。");
            if(!str(r,"dataScope").equals(scope)&&d.count("SELECT COUNT(*) FROM user_role WHERE role_id=?",rid)>0)throw error(409,"角色已关联账号，不能直接更换数据范围。");
            d.update("UPDATE app_role SET name=?,data_scope=? WHERE id=?",name,scope,rid);
        }
        d.update("DELETE FROM role_permission WHERE role_id=?",rid);
        for(String p:perms)d.update("INSERT INTO role_permission VALUES(?,?)",rid,p);
        d.update("UPDATE app_user SET token_version=token_version+1 WHERE id IN (SELECT user_id FROM user_role WHERE role_id=?)",rid);
        d.audit(a,null,"role.save",rid,"权限数="+perms.size()+"；关联账号旧登录失效");
    }
    static void hq(Admin a){if(!a.hq())throw error(403,"该操作仅总部管理员可用。");}
    static void validateStoreImport(Map<String,Object> row){
        if(!text(row,"id",1,64).matches("[A-Za-z0-9_-]+"))throw error(400,"门店编号仅支持字母数字横线下划线。");
        text(row,"name",1,60);
        for(String field:List.of("district","address","phone","hours","image"))text(row,field,0,300);
        String phone=str(row,"phone");
        if(!phone.isBlank()&&!phone.matches("[0-9+ -]{6,25}"))throw error(400,"联系电话格式不正确。");
        safeImages(row);
    }
    @Transactional public void importStore(Admin a,Map<String,Object> row){
        hq(a);validateStoreImport(row);String sid=str(row,"id");catalog.guard("store:"+sid);
        var previous=d.rows("SELECT * FROM store WHERE id=? FOR UPDATE",sid);
        Map<String,Object> profile;
        if(previous.isEmpty()){
            profile=new LinkedHashMap<>();
            for(String field:List.of("district","address","phone","hours","image","tag","description"))profile.put(field,"");
            profile.put("id",sid);profile.put("enabled",true);profile.put("onlineBooking",false);
            profile.put("gallery",List.of());profile.put("disabledServiceIds",List.of());
        }else profile=d.object(previous.get(0).get("profileJson"));
        for(String field:List.of("name","district","address","phone","hours","image"))if(!str(row,field).isBlank())profile.put(field,row.get(field));
        if(previous.isEmpty()){
            d.update("INSERT INTO store VALUES(?,?,?,?,?)",sid,profile.get("name"),true,d.stringify(profile),d.stringify(Bootstrap.defaultRules()));
            catalog.invalidateSite();d.audit(a,sid,"store.create",sid,"导入新门店；在线预约默认关闭，待核对资料与时段后启用");
        }else saveStore(a,sid,profile);
    }
    @Transactional public Map<String,Object> saveStore(Admin a,String sid,Map<String,Object> in){a.store(sid);catalog.guard("store:"+sid);catalog.invalidateSite();var previous=d.object(catalog.store(sid).get("profileJson"));String name=text(in,"name",1,60);Map<String,Object> p=new LinkedHashMap<>(in);p.remove("services");p.put("id",sid);p.put("name",name);for(String field:List.of("address","phone","hours","description","district","image"))text(p,field,0,field.equals("description")?1000:300);if(!str(p,"phone").isEmpty()&&!str(p,"phone").matches("[0-9+ -]{6,25}"))throw error(400,"联系电话格式不正确。");if(!a.hq()){p.put("enabled",previous.get("enabled"));p.put("onlineBooking",previous.get("onlineBooking"));p.put("catalogMode",previous.getOrDefault("catalogMode","INHERIT"));}else{p.putIfAbsent("catalogMode",previous.getOrDefault("catalogMode","INHERIT"));if(!Set.of("INHERIT","INDEPENDENT").contains(str(p,"catalogMode")))throw error(400,"请选择有效的门店品项管理方式。");}safeImages(p);d.update("UPDATE store SET name=?,enabled=?,profile_json=? WHERE id=?",name,bool(p,"enabled"),d.stringify(p),sid);d.audit(a,sid,"store.save",sid,"更新门店公开资料");return p;}
    @Transactional public void saveTherapist(Admin a,Map<String,Object> in){
        String store=text(in,"storeId",1,64);a.store(store);var target=catalog.store(store);
        String tid=text(in,"id",0,64),previousStore="";
        String observed=tid.isEmpty()?"":str(d.one("SELECT store_id FROM therapist WHERE id=?",tid),"storeId");
        catalog.guard("store:"+store,"store:"+observed);
        if(!tid.isEmpty()){
            var previous=d.one("SELECT * FROM therapist WHERE id=? FOR UPDATE",tid);
            previousStore=str(previous,"storeId");if(!observed.equals(previousStore))throw error(409,"Store changed; refresh before retrying.");a.store(previousStore);
            String original=text(in,"originalStoreId",0,64);
            if(!original.isEmpty()&&!original.equals(previousStore))throw error(409,"调理师所属门店已被调整，请刷新后重新编辑。");
            if(!store.equals(previousStore)){
                hq(a);
                if(!original.equals(previousStore))throw error(409,"请刷新调理师资料，确认原所属门店后再调整。");
                if(!bool(target,"enabled"))throw error(400,"目标门店已停用，请选择启用中的门店。");
                int active=d.count("SELECT COUNT(*) FROM appointment WHERE assigned_therapist_id=? AND status IN ('PENDING','CONFIRMED','ARRIVED')",tid);
                if(active>0)throw error(409,"该调理师还有"+active+"笔未结束预约已实际分配，请先在原门店重新分配或完成接待，再调整所属门店。");
            }
        }
        String name=text(in,"name",1,60);Map<String,Object> p=new LinkedHashMap<>(in);
        for(String field:List.of("id","name","storeId","storeName","originalStoreId"))p.remove(field);
        safeImages(p);for(String s:d.strings(p.get("serviceIds")))if(catalog.storeServices(store,false).stream().noneMatch(v->s.equals(str(v,"id"))))throw error(400,"调理师项目范围须属于本门店项目。");
        if(tid.isEmpty()){tid=id();d.update("INSERT INTO therapist VALUES(?,?,?,?,?)",tid,store,name,d.stringify(p),bool(in,"enabled"));}
        else d.update("UPDATE therapist SET store_id=?,name=?,profile_json=?,enabled=? WHERE id=?",store,name,d.stringify(p),bool(in,"enabled"),tid);
        boolean transferred=!previousStore.isEmpty()&&!store.equals(previousStore);
        d.audit(a,store,transferred?"therapist.transfer":"therapist.save",tid,transferred?"所属门店："+str(catalog.store(previousStore),"name")+" → "+str(target,"name")+"；原预约保留原门店":"维护本店人员资料");
    }
    Map<String,Object> content(Admin a){if(!a.hq())return catalog.site();var row=d.one("SELECT * FROM site_config WHERE id='brand'");var draft=d.object(row.get("draftJson"));draft.put("version",row.get("revision"));return draft;}
    @Transactional public Map<String,Object> saveContent(Admin a,Map<String,Object> input,boolean publish){hq(a);catalog.invalidateSite();var row=d.one("SELECT * FROM site_config WHERE id='brand' FOR UPDATE");int revision=integer(row,"revision",0);if(integer(input,"version",-1)!=revision)throw error(409,"内容已被其他人修改，请刷新后再保存。");Map<String,Object> source=publish&&!a.permissions().contains("content:write")?d.object(row.get("draftJson")):input;Map<String,Object> site=new LinkedHashMap<>();for(String k:List.of("brandName","brandEnglish","tagline","theme","hero","brandStory","contact","sections","services","products","galleryGroups","customerServiceEnabled"))site.put(k,source.get(k));text(site,"brandName",1,30);text(site,"brandStory",0,10000);if(!Set.of("sage","sand","ink").contains(str(site,"theme")))throw error(400,"请选择预置配色。");if(!(site.get("hero") instanceof Map<?,?>))throw error(400,"请完善首页封面。");Set<String> ids=new HashSet<>();for(var s:catalog.collection(site,"services")){String id=text(s,"id",1,64);if(!ids.add(id))throw error(400,"项目编号重复。");text(s,"name",1,60);text(s,"description",0,3000);}if(publish&&ids.isEmpty())throw error(400,"发布前请至少配置一个服务项目。");safeImages(site);site.put("assetLibrary",d.object(row.get("publishedJson")).getOrDefault("assetLibrary",List.of()));site.put("version",revision+1);site.put("stores",catalog.site().get("stores"));if(publish)d.update("UPDATE site_config SET revision=revision+1,published_json=?,draft_json=? WHERE id='brand'",d.stringify(site),d.stringify(site));else d.update("UPDATE site_config SET revision=revision+1,draft_json=? WHERE id='brand'",d.stringify(site));d.audit(a,null,publish?"content.publish":"content.draft","brand","版本="+(revision+1));return site;}
    static void safeImages(Object obj){if(obj instanceof Map<?,?> map)for(var e:map.entrySet()){if(Set.of("image","url").contains(e.getKey())&&e.getValue() instanceof String value){if(!value.isBlank()&&(!(value.startsWith("/media/")||value.startsWith("/uploads/"))||value.contains("..")||value.contains("\\")))throw error(400,"图片须从授权素材库选择。");}else safeImages(e.getValue());}else if(obj instanceof Collection<?> list)for(var v:list)safeImages(v);}
    Map<String,Object> customers(Admin a,String requested,int page){String store=scope(a,requested);List<Object> args=new ArrayList<>();String where="";if(!store.isEmpty()){where=" WHERE sc.store_id=?";args.add(store);}int total=d.count("SELECT COUNT(*) FROM customer_profile cp JOIN store_customer sc ON sc.customer_profile_id=cp.id"+where,args.toArray());args.add(30);args.add(Math.max(0,page-1)*30);return Map.of("total",total,"items",d.rows("SELECT cp.*,sc.store_id,sc.private_note,sc.source,sc.preferred_therapist_id FROM customer_profile cp JOIN store_customer sc ON sc.customer_profile_id=cp.id"+where+" ORDER BY cp.created_at DESC LIMIT ? OFFSET ?",args.toArray()).stream().peek(c->c.put("phone",BookingService.mask(str(c,"phone")))).toList());}
    @Transactional public void saveCustomer(Admin a,String id,Map<String,Object> in){String store=text(in,"storeId",1,64);a.store(store);d.one("SELECT * FROM store_customer WHERE customer_profile_id=? AND store_id=?",id,store);d.update("UPDATE store_customer SET private_note=?,source=? WHERE customer_profile_id=? AND store_id=?",text(in,"privateNote",0,1000),text(in,"source",0,80),id,store);if(a.permissions().contains("customers:master")&&a.hq()){d.update("UPDATE customer_profile SET member_verified=?,member_grade=?,shared_note=? WHERE id=?",bool(in,"memberVerified"),text(in,"memberGrade",0,60),text(in,"sharedNote",0,300),id);}d.audit(a,store,"customer.update",id,"更新基础标签与门店私有跟进");}
    Map<String,Object> lookup(Admin a,String phone){a.require("customers:shared");if(!"true".equals(str(d.one("SELECT setting_value FROM app_setting WHERE setting_key='sharedLookupEnabled'"),"settingValue")))throw error(403,"总部尚未开启品牌基础档案共享。");if(!phone.matches("1[3-9]\\d{9}"))throw error(400,"只支持完整手机号精确检索，不支持模糊搜索。");d.audit(a,a.storeId(),"customer.shared-lookup",BookingService.mask(phone),"精确查询白名单基础字段");var list=d.rows("SELECT name,member_verified,member_grade,original_store_id,shared_note FROM customer_profile WHERE phone=?",phone);return Map.of("found",!list.isEmpty(),"profile",list.isEmpty()?Map.of():list.get(0));}
}
