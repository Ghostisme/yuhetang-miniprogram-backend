package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** Menu operations use a separate stable token without refreshing the messaging token. */
@Service
class WechatMenuGateway {
    private final WechatGateway wx;
    private String token="";
    private Instant expires=Instant.EPOCH;

    WechatMenuGateway(WechatGateway wx){this.wx=wx;}

    synchronized String access(){
        if(wx.serviceId.isBlank()||wx.serviceSecret.isBlank())
            throw error(409,"服务器尚未配置服务号 AppID / AppSecret，请先配置 SERVICE_APP_ID 和 SERVICE_APP_SECRET。");
        if(!token.isBlank()&&Instant.now().isBefore(expires))return token;
        var result=wx.post("https://api.weixin.qq.com/cgi-bin/stable_token",Map.of(
            "grant_type","client_credential","appid",wx.serviceId,"secret",wx.serviceSecret,"force_refresh",false));
        check(result,false);
        String value=str(result,"access_token");
        if(value.isBlank())throw error(502,"微信没有返回有效的服务号凭据，请检查服务号配置。");
        token=value;expires=Instant.now().plusSeconds(Math.max(1,integer(result,"expires_in",7200)-300));
        return value;
    }

    Map<String,Object> read(){
        var result=wx.get("https://api.weixin.qq.com/cgi-bin/menu/get?access_token="+WechatGateway.enc(access()));
        if(integer(result,"errcode",0)==46003)return Map.of("menu",Map.of("button",List.of()),"conditionalCount",0,"exists",false);
        check(result,false);
        if(!(result.get("menu") instanceof Map<?,?> menu)||!(menu.get("button") instanceof List<?>))
            throw error(502,"微信没有返回有效的菜单结构，请稍后重新读取。");
        int count=result.get("conditionalmenu") instanceof List<?> list?list.size():0;
        return Map.of("menu",menu,"conditionalCount",count,"exists",true);
    }

    void publish(Map<String,Object> menu){
        String credential=access();
        try{
            var result=wx.post("https://api.weixin.qq.com/cgi-bin/menu/create?access_token="+WechatGateway.enc(credential),menu);
            check(result,true);
        }catch(WechatGateway.ExchangeException failure){
            throw error(502,failure.getReason()+" 发布结果尚未确认，请先读取微信菜单核对，再决定是否重试。");
        }
    }

    private void check(Map<String,Object> result,boolean requireCode){
        if(requireCode&&!result.containsKey("errcode"))throw error(502,"微信没有返回发布确认，请先读取微信菜单核对结果。");
        int code=integer(result,"errcode",0);
        if(code==0)return;
        if(Set.of(40001,40014,42001).contains(code))synchronized(this){token="";expires=Instant.EPOCH;}
        String reason=switch(code){
            case 40013 -> "服务号 AppID 无效，请核对 SERVICE_APP_ID。";
            case 40125 -> "服务号 AppSecret 无效，请核对 SERVICE_APP_SECRET。";
            case 40164 -> "服务器出口 IP 未加入服务号 API IP 白名单，请在微信开发者平台添加。";
            case 40001,40014,42001 -> "服务号凭据已失效，请重新操作。";
            case 48001,48002 -> "服务号没有菜单操作权限，请核对认证状态、菜单权限和第三方授权。";
            case 40018 -> "菜单名称长度不符合微信要求，请缩短名称后重试。";
            case 45009 -> "微信接口调用次数已达限额，请稍后重试。";
            default -> "微信未接受菜单操作，请核对菜单内容、小程序关联关系和正式版页面路径。";
        };
        throw error(502,reason+"（微信错误码 "+code+"）");
    }
}
