package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
class WechatMenuService {
    static final String TABLE="wechat_service_menu", MINI_APP_ID="wxce054e18303efb8a";
    static final Set<String> KEY_TYPES=Set.of("click","scancode_push","scancode_waitmsg","pic_sysphoto","pic_photo_or_album","pic_weixin","location_select");
    static final Set<String> MEDIA_TYPES=Set.of("media_id","view_limited"), ARTICLE_TYPES=Set.of("article_id","article_view_limited");
    final Data d;final WechatGateway wx;final WechatMenuGateway gateway;
    WechatMenuService(Data d,WechatGateway wx,WechatMenuGateway gateway){this.d=d;this.wx=wx;this.gateway=gateway;}

    static List<Map<String,String>> pages(){return List.of(
        Map.of("name","小程序首页","path","pages/home/index"),
        Map.of("name","在线预约","path","pages/booking/index"),
        Map.of("name","养护项目","path","pages/catalog/index"),
        Map.of("name","门店查询","path","pages/stores/index"),
        Map.of("name","联系客服","path","pages/contact/index"),
        Map.of("name","我的预约","path","pages/orders/index"));}

    Map<String,Object> state(Admin actor){
        allowed(actor,"wechat-menu:read");
        var row=row(false);var out=new LinkedHashMap<String,Object>();
        out.put("revision",row.get("revision"));out.put("draft",d.object(row.get("draftJson")));
        out.put("savedAt",str(row,"savedAt"));out.put("publishedAt",str(row,"publishedAt"));
        out.put("backupAt",str(row,"backupAt"));out.put("hasBackup",row.get("backupJson")!=null);
        out.put("serviceAppId",wx.serviceId);out.put("miniAppId",wx.appId.isBlank()?MINI_APP_ID:wx.appId);
        out.put("credentialsConfigured",!wx.serviceId.isBlank()&&!wx.serviceSecret.isBlank());
        out.put("pages",pages());
        return out;
    }

    @Transactional public Map<String,Object> save(Admin actor,Map<String,Object> input){
        allowed(actor,"wechat-menu:write");
        var menu=normalize(d.object(input.get("menu")),false);var row=row(true);revision(row,input);
        d.update("UPDATE "+TABLE+" SET revision=revision+1,draft_json=?,saved_at=? WHERE id='default'",d.stringify(menu),now());
        d.audit(actor,null,"wechat-menu.draft","service","保存菜单草稿");return state(actor);
    }

    Map<String,Object> remote(Admin actor){
        allowed(actor,"wechat-menu:read");
        return remote();
    }

    private Map<String,Object> remote(){
        var result=gateway.read();var out=new LinkedHashMap<String,Object>();
        out.put("menu",normalize(d.object(result.get("menu")),false));
        out.put("exists",result.get("exists"));out.put("conditionalCount",result.get("conditionalCount"));
        out.put("readAt",now());return out;
    }

    // Keep the pre-publication backup even if WeChat rejects or times out. The
    // provider update cannot participate in the database transaction.
    @Transactional(timeout=60,noRollbackFor=ResponseStatusException.class)
    public Map<String,Object> publish(Admin actor,Map<String,Object> input){
        allowed(actor,"wechat-menu:publish");
        var row=row(true);revision(row,input);var menu=normalize(d.object(row.get("draftJson")),true);
        var before=remote();
        d.update("UPDATE "+TABLE+" SET backup_json=?,backup_at=? WHERE id='default'",d.stringify(before),before.get("readAt"));
        try{gateway.publish(menu);}
        catch(ResponseStatusException failure){
            d.audit(actor,null,"wechat-menu.publish-failed","service","微信未确认发布，保留草稿和发布前菜单");throw failure;
        }
        String time=now();
        d.update("UPDATE "+TABLE+" SET published_json=?,published_at=? WHERE id='default'",d.stringify(menu),time);
        d.audit(actor,null,"wechat-menu.publish","service","微信已接受菜单；草稿版本="+row.get("revision"));
        var out=new LinkedHashMap<>(state(actor));out.put("accepted",true);out.put("conditionalCount",before.get("conditionalCount"));return out;
    }

    @Transactional public Map<String,Object> restore(Admin actor,Map<String,Object> input){
        allowed(actor,"wechat-menu:write");
        var row=row(true);revision(row,input);
        if(row.get("backupJson")==null)throw error(409,"还没有发布前菜单备份。");
        var backup=d.object(row.get("backupJson"));
        d.update("UPDATE "+TABLE+" SET revision=revision+1,draft_json=?,saved_at=? WHERE id='default'",
            d.stringify(normalize(d.object(backup.get("menu")),false)),now());
        d.audit(actor,null,"wechat-menu.restore-draft","service","将发布前菜单恢复为草稿，尚未发布到微信");return state(actor);
    }

    private Map<String,Object> row(boolean lock){return d.one("SELECT * FROM "+TABLE+" WHERE id='default'"+(lock?" FOR UPDATE":""));}
    private static void allowed(Admin a,String permission){AdminService.hq(a);a.require("wechat-menu:read");a.require(permission);}
    private static void revision(Map<String,Object> row,Map<String,Object> input){if(integer(input,"revision",-1)!=integer(row,"revision",0))throw error(409,"菜单草稿已被其他人修改，请刷新后核对再操作。");}

    Map<String,Object> normalize(Map<String,Object> menu,boolean publish){
        if(!(menu.get("button") instanceof List<?> buttons)||buttons.size()>3||(publish&&buttons.isEmpty()))
            throw error(400,"请配置 1 至 3 个一级菜单后发布。");
        var normalized=new ArrayList<Map<String,Object>>();
        for(Object value:buttons)normalized.add(button(value,false,publish));
        var out=Map.<String,Object>of("button",normalized);
        if(d.stringify(out).getBytes(StandardCharsets.UTF_8).length>60000)throw error(400,"菜单配置过长，请缩短链接或页面参数。");
        return out;
    }

    private Map<String,Object> button(Object value,boolean child,boolean publish){
        if(!(value instanceof Map<?,?>))throw error(400,"菜单结构不正确。");
        var input=d.object(value);var out=new LinkedHashMap<String,Object>();
        String name=field(input,"name",publish,child?60:16,child?"二级菜单名称":"一级菜单名称");out.put("name",name);
        String type=str(input,"type");Object nested=input.get("sub_button");
        if(nested instanceof Map<?,?> group)nested=group.get("list");
        if(nested!=null&&!(nested instanceof List<?>))throw error(400,"子菜单结构不正确。");
        var children=nested instanceof List<?> list?list:List.of();
        boolean parent=!children.isEmpty()||type.equals("submenu")||(nested!=null&&type.isBlank());
        if(parent){
            if(child||children.size()>5||(publish&&children.isEmpty()))throw error(400,"每个一级菜单需包含 1 至 5 个二级菜单，最多支持两级。");
            if(!type.isBlank()&&!type.equals("submenu"))throw error(400,"带子菜单的一级菜单不能同时设置跳转动作。");
            var items=new ArrayList<Map<String,Object>>();for(Object item:children)items.add(button(item,true,publish));
            out.put("sub_button",items);return out;
        }
        out.put("type",type);
        if(type.equals("miniprogram")){
            String id=field(input,"appid",publish,32,"小程序 AppID");
            if(!id.isEmpty()&&!id.matches("wx[0-9a-fA-F]{16}"))throw error(400,"小程序 AppID 格式不正确，应为 wx 开头的 18 位标识。");
            String path=field(input,"pagepath",publish,1024,"小程序页面路径").replaceFirst("^/+","");
            if(!path.isEmpty()&&(!path.matches("[A-Za-z0-9_./-]+(?:\\?[^\\s#]*)?")||path.split("\\?",2)[0].contains("..")))
                throw error(400,"小程序页面路径格式不正确，请填写 pages/… 形式的页面路径，可附带查询参数。");
            out.put("appid",id);out.put("pagepath",path);out.put("url",url(input,publish));
        }else if(type.equals("view"))out.put("url",url(input,publish));
        else if(KEY_TYPES.contains(type))out.put("key",field(input,"key",publish,128,"菜单事件标识"));
        else if(MEDIA_TYPES.contains(type))out.put("media_id",field(input,"media_id",publish,256,"素材标识"));
        else if(ARTICLE_TYPES.contains(type))out.put("article_id",field(input,"article_id",publish,256,"图文标识"));
        else throw error(400,"当前菜单动作不受支持，请改为打开小程序、打开网页或有效的微信菜单动作。");
        return out;
    }

    private static String field(Map<String,Object> input,String key,boolean required,int maxBytes,String label){
        if(input.get(key)!=null&&!(input.get(key) instanceof String))throw error(400,label+"必须是文字。");
        String value=str(input,key).trim();
        if((required&&value.isBlank())||value.getBytes(StandardCharsets.UTF_8).length>maxBytes||value.chars().anyMatch(c->c<32||c==127))
            throw error(400,label+"未填写或长度不符合微信要求。");
        return value;
    }
    private static String url(Map<String,Object> input,boolean required){
        String value=field(input,"url",required,1024,"网页地址（小程序按钮需填写兼容网页）");
        if(!value.isEmpty())try{
            var uri=URI.create(value);
            if(!Set.of("https","http").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))||uri.getHost()==null||uri.getUserInfo()!=null)
                throw new IllegalArgumentException();
        }catch(IllegalArgumentException failure){throw error(400,"网页地址需为完整的 https:// 或 http:// 链接。");}
        return value;
    }
}
