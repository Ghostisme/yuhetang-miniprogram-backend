package com.yuwatang.reservation;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Component
class Bootstrap implements CommandLineRunner {
    final Data d; final AuthService auth; final String password,resultMapping,reminderMapping;
    @Value("${app.wechat.service-template-mapping:}") String serviceMapping="";
    @Value("${app.wechat.service-template:}") String serviceTemplateId="";
    Bootstrap(Data d,AuthService auth,@Value("${app.admin-password}")String password,
              @Value("${app.wechat.customer-template-mapping:}")String resultMapping,
              @Value("${app.wechat.customer-reminder-template-mapping:}")String reminderMapping){
        this.d=d;this.auth=auth;this.password=password;this.resultMapping=resultMapping;this.reminderMapping=reminderMapping;
    }
    // Deployment defaults initialize missing settings. HQ edits remain authoritative on restart.
    void initializeTemplateMappings(){
        initializeMapping("customerTemplateMapping",resultMapping);
        initializeMapping("customerReminderTemplateMapping",reminderMapping);
        if(StaffAppointmentTemplate.ID.equals(serviceTemplateId)){
            // This private template has a fixed field contract; repair stale settings
            // left by the former subscription template without changing other templates.
            String expected=d.stringify(StaffAppointmentTemplate.FIELDS);
            if(d.count("SELECT COUNT(*) FROM app_setting WHERE setting_key='serviceTemplateMapping'")==0)
                d.update("INSERT INTO app_setting VALUES('serviceTemplateMapping',?)",expected);
            else if(!StaffAppointmentTemplate.FIELDS.equals(d.object(d.one("SELECT setting_value FROM app_setting WHERE setting_key='serviceTemplateMapping'").get("settingValue"))))
                d.update("UPDATE app_setting SET setting_value=? WHERE setting_key='serviceTemplateMapping'",expected);
        }else initializeMapping("serviceTemplateMapping",serviceMapping);
    }
    void initializeMapping(String key,String value){
        if(value.isBlank()||d.count("SELECT COUNT(*) FROM app_setting WHERE setting_key=?",key)>0)return;
        Map<String,Object> mapping=d.object(value);
        if(mapping.isEmpty()||value.length()>2000)throw new IllegalStateException("Invalid WeChat template mapping: "+key);
        if(!NotificationFields.valid(key,mapping))throw new IllegalStateException("Invalid WeChat template field: "+key);
        d.update("INSERT INTO app_setting VALUES(?,?)",key,d.stringify(mapping));
    }
    static final String[][] PERMISSIONS={
        {"wechat-menu:read","查看服务号菜单（总部）","服务号菜单"},{"wechat-menu:write","编辑服务号菜单草稿（总部）","服务号菜单"},{"wechat-menu:publish","发布服务号菜单（总部）","服务号菜单"},
        {"services:read","查看本店养护项目","品牌与内容"},{"services:write","维护本店养护项目","品牌与内容"},{"chat:read","查看客户会话","客服"},{"chat:reply","回复客户消息","客服"},{"chat:route","总部客服分流","客服"},
        {"dashboard:read","查看工作台","运营"},{"appointments:read","查看预约","预约"},{"appointments:write","代约与改约","预约"},{"appointments:review","确认与拒绝","预约"},{"appointments:status","到店与完成","预约"},{"appointments:phone","查看完整电话","预约"},{"appointments:export","导出预约","预约"},
        {"content:read","预览品牌内容","品牌与内容"},{"content:write","编辑品牌草稿（总部）","品牌与内容"},{"content:publish","发布品牌内容（总部）","品牌与内容"},{"stores:write","维护门店","门店"},{"rules:write","维护时段规则","门店"},{"therapists:read","查看调理师","人员"},{"therapists:write","维护调理师","人员"},
        {"customers:read","查看本店客户","客户"},{"customers:write","客户私有跟进","客户"},{"customers:master","维护品牌会员主档","客户"},{"customers:shared","跨店基础精确检索","客户"},{"customers:export","导出客户","客户"},{"assets:read","查看图片素材","品牌与内容"},{"assets:write","上传图片素材","品牌与内容"},
        {"users:read","查看账号","权限"},{"users:write","管理账号","权限"},{"roles:read","查看角色","权限"},{"roles:write","配置角色权限","权限"},{"notifications:read","查看消息记录","通知"},{"notifications:write","配置通知渠道与接收人","通知"},{"audit:read","查看操作日志","系统"},{"imports:write","导入初始资料","系统"},{"integrations:read","查看扩展配置","系统"}
    };
    static Map<String,Object> defaultRules(){return new LinkedHashMap<>(Map.ofEntries(Map.entry("opens","10:00"),Map.entry("closes","22:00"),Map.entry("interval",15),Map.entry("capacity",4),Map.entry("advanceDays",7),Map.entry("minLeadMinutes",30),Map.entry("cancelHours",2),Map.entry("maxHeadcount",4),Map.entry("phoneOpenLimit",3),Map.entry("remindMinutes",15),Map.entry("closedDates",List.of())));}
    Map<String,Object> initialSite()throws Exception{
        if(auth.local)try(var input=new ClassPathResource("brand-seed.json").getInputStream()){
            return d.object(d.json.convertValue(d.json.readTree(input).path("site"),Map.class));
        }
        // Production starts with a valid editor configuration, without example business content.
        Map<String,Object> site=new LinkedHashMap<>();
        site.put("brandName","裕和堂");site.put("brandEnglish","YU HE TANG");
        site.put("tagline","");site.put("theme","sage");site.put("brandStory","");
        site.put("hero",Map.of("eyebrow","","title","","subtitle","","image","","button",""));
        site.put("contact",Map.of("phone","","wechat","","message",""));
        site.put("sections",List.of(Map.of("id","services","name","养护项目","enabled",true),
            Map.of("id","space","name","门店空间","enabled",true),
            Map.of("id","products","name","养护好物","enabled",true),
            Map.of("id","brand","name","品牌故事","enabled",true)));
        for(String key:List.of("services","stores","products","galleryGroups","assetLibrary"))site.put(key,List.of());
        return site;
    }
    @Override @Transactional public void run(String...args)throws Exception{
        new BookingLocks(d).acquire("bootstrap");
        initializeTemplateMappings();
        if(d.count("SELECT COUNT(*) FROM app_user")>0)return;
        String pwd=password;if(pwd.isEmpty()){if(!auth.local)throw new IllegalStateException("Set ADMIN_PASSWORD before the first production start.");pwd="Yht@9800dev";}
        for(var p:PERMISSIONS)if(d.count("SELECT COUNT(*) FROM permission WHERE code=?",p[0])==0)d.update("INSERT INTO permission VALUES(?,?,?)",(Object[])p);
        role("hq","总部管理员","GLOBAL",Arrays.stream(PERMISSIONS).map(p->p[0]).toList());
        role("manager","门店店长","STORE",List.of("services:read","services:write","chat:read","chat:reply","dashboard:read","appointments:read","appointments:write","appointments:review","appointments:status","appointments:phone","content:read","stores:write","rules:write","therapists:read","therapists:write","customers:read","customers:write","assets:read","assets:write","users:read","users:write","roles:read","notifications:read","notifications:write","audit:read"));
        role("frontdesk","门店前台","STORE",List.of("services:read","services:write","chat:read","chat:reply","dashboard:read","appointments:read","appointments:write","appointments:review","appointments:status","appointments:phone","therapists:read","customers:read","customers:write","notifications:read"));
        Map<String,Object> site=initialSite();
        site.put("version",1);site.put("sharedLookupEnabled",false);site.put("customerServiceEnabled",true);
        List<Map<String,Object>> stores=new ArrayList<>();String[][] st={{"yuyao","余姚路店","静安区","store-01.webp"},{"bai-le-men","百乐门店","静安区","store-02.webp"},{"huashan","华山路店","静安区","store-03.webp"},{"taikoo","太古汇店","静安区","store-04.webp"},{"wujiang","吴江路店","静安区","store-05.webp"},{"shanghai","上海中心店","浦东新区","store-06.webp"},{"meihua","梅花路店","浦东新区","store-07.webp"},{"sichuan","四川北路店","虹口区","store-08.webp"},{"changde","常德路店","静安区","store-09.webp"}};
        if(auth.local)for(var s:st){Map<String,Object> v=new LinkedHashMap<>();v.put("id",s[0]);v.put("name",s[1]);v.put("district",s[2]);v.put("image","/media/"+s[3]);v.put("gallery",List.of("/media/"+s[3],"/media/space-08-full.webp"));v.put("address","门店地址待品牌确认");v.put("hours","10:00–22:00");v.put("phone","");v.put("tag","东方养护空间");v.put("description","留一点时间，感受从容与舒展。");v.put("enabled",true);v.put("onlineBooking",!s[0].equals("meihua"));v.put("disabledServiceIds",List.of());stores.add(v);d.update("INSERT INTO store VALUES(?,?,?,?,?)",s[0],s[1],true,d.stringify(v),d.stringify(defaultRules()));
            if(auth.local)for(int i=1;i<=3;i++){String tid=s[0]+"-t"+i;Map<String,Object> t=Map.of("grade",i==1?"资深调理师":"调理师","specialty",i==1?"经络舒缓 · 艾草养护":"日常养护 · 放松舒展","serviceIds",List.of("moxa","massage","foot","guasha"),"image","/media/ritual.webp","resting",false,"showOnClient",true,"sample",true);d.update("INSERT INTO therapist VALUES(?,?,?,?,?)",tid,s[0],new String[]{"","林老师（示例）","陈老师（示例）","周老师（示例）"}[i],d.stringify(t),true);}
        }
        site.put("stores",stores);d.update("INSERT INTO site_config VALUES(?,?,?,?)","brand",1,d.stringify(site),d.stringify(site));d.update("INSERT INTO app_setting VALUES(?,?)","sharedLookupEnabled","false");d.update("INSERT INTO app_setting VALUES(?,?)","bookingLock","1");
        user("hq","admin","总部管理员",null,"hq",pwd);
        if(auth.local){user("yuyao-manager","yuyao","余姚路店店长","yuyao","manager",pwd);user("bai-manager","bailemen","百乐门店店长","bai-le-men","manager",pwd);user("yuyao-frontdesk","frontdesk","余姚路店前台","yuyao","frontdesk",pwd);}
    }
    void role(String id,String name,String scope,List<String> perms){d.update("INSERT INTO app_role VALUES(?,?,?,?)",id,name,scope,true);for(String p:perms)d.update("INSERT INTO role_permission VALUES(?,?)",id,p);}
    void user(String id,String username,String name,String store,String role,String pwd){d.update("INSERT INTO app_user VALUES(?,?,?,?,?,?,?,?)",id,username,name,auth.passwords.encode(pwd),store,true,0,now());d.update("INSERT INTO user_role VALUES(?,?)",id,role);}
}
