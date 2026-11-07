package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
class NotificationChannels {
    final Data d;
    NotificationChannels(Data d){this.d=d;}
    static final List<String> FLAGS=List.of("pcEnabled","wechatStaffEnabled","wechatCustomerEnabled","smsStaffEnabled","smsCustomerEnabled");
    Map<String,Object> get(String store){
        var rows=d.rows("SELECT * FROM notification_channel_config WHERE store_id=?",store);
        if(!rows.isEmpty())return rows.get(0);
        return new LinkedHashMap<>(Map.of("storeId",store,"pcEnabled",true,"wechatStaffEnabled",true,
                "wechatCustomerEnabled",true,"smsStaffEnabled",false,"smsCustomerEnabled",false,"smsPhone","","revision",0));
    }
    @Transactional public Map<String,Object> save(Admin a,Map<String,Object> in){
        String store=text(in,"storeId",1,64);a.store(store);d.one("SELECT id FROM store WHERE id=? FOR UPDATE",store);
        for(String flag:FLAGS)if(!(in.get(flag) instanceof Boolean))throw error(400,"请填写全部通知渠道开关。");
        String phone=str(in,"smsPhone").trim();
        if(!phone.isEmpty()&&!phone.matches("1[3-9]\\d{9}")||bool(in,"smsStaffEnabled")&&phone.isEmpty())throw error(400,"请填写正确的门店短信接收手机号。");
        int revision=integer(in,"revision",-1);if(revision<0)throw error(400,"缺少通知配置版本，请刷新。");
        if(revision==0){
            if(d.count("SELECT COUNT(*) FROM notification_channel_config WHERE store_id=?",store)>0)throw error(409,"通知配置已更新，请刷新后重试。");
            d.update("INSERT INTO notification_channel_config VALUES(?,?,?,?,?,?,?,?,?)",store,bool(in,"pcEnabled"),bool(in,"wechatStaffEnabled"),bool(in,"wechatCustomerEnabled"),bool(in,"smsStaffEnabled"),bool(in,"smsCustomerEnabled"),phone,1,now());
        }else if(d.update("UPDATE notification_channel_config SET pc_enabled=?,wechat_staff_enabled=?,wechat_customer_enabled=?,sms_staff_enabled=?,sms_customer_enabled=?,sms_phone=?,revision=revision+1,updated_at=? WHERE store_id=? AND revision=?",
                bool(in,"pcEnabled"),bool(in,"wechatStaffEnabled"),bool(in,"wechatCustomerEnabled"),bool(in,"smsStaffEnabled"),bool(in,"smsCustomerEnabled"),phone,now(),store,revision)!=1)throw error(409,"通知配置已更新，请刷新后重试。");
        // Pending messages keep their original recipient. Configuration changes never redirect them.
        d.update("UPDATE notification_outbox SET delivery_status='CANCELLED',last_error='短信接收设置已变更，原待发通知停止发送。' WHERE store_id=? AND channel='SMS' AND delivery_status IN ('PENDING','RETRY','BLOCKED')",store);
        if(!bool(in,"wechatStaffEnabled"))cancel(store,"SERVICE");
        if(!bool(in,"wechatCustomerEnabled"))cancel(store,"SUBSCRIBE");
        d.audit(a,store,"notification.channels",store,"更新 PC、微信个人消息、短信通知渠道");
        return get(store);
    }
    void cancel(String store,String channel){d.update("UPDATE notification_outbox SET delivery_status='CANCELLED',last_error='该通知渠道已关闭。' WHERE store_id=? AND channel=? AND delivery_status IN ('PENDING','RETRY','BLOCKED')",store,channel);}
    static String group(String channel){return switch(channel){case "INBOX"->"PC";case "SERVICE","SUBSCRIBE"->"WECHAT";case "SMS"->"SMS";default->"";};}
}
