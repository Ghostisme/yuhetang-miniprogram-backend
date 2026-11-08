package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
class ChatService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ChatService.class);
    final Data d;final CatalogService catalog;final WechatGateway wx;final TransactionTemplate tx;
    ChatService(Data d,CatalogService c,WechatGateway w,PlatformTransactionManager tm){this.d=d;catalog=c;wx=w;tx=new TransactionTemplate(tm);}
    void lock(){d.one("SELECT * FROM app_setting WHERE setting_key='chatLock' FOR UPDATE");}
    String openid(String customer){return str(d.one("SELECT openid FROM customer_identity WHERE id=?",customer),"openid");}
    void store(String store){if(!store.isBlank()&&!bool(catalog.store(store),"enabled"))throw error(400,"该门店暂不可咨询。");}
    String entryStore(String openid,String source,long current){
        // Native contact is available before API login. This is public store context,
        // never an identity or permission; receive() only sees verified WeChat callbacks.
        if(source.startsWith("yht-store:")){
            String store=source.substring("yht-store:".length());
            if(store.isEmpty()||store.length()<=64&&d.count("SELECT COUNT(*) FROM store WHERE id=? AND enabled=TRUE",store)==1)return store;
            return null;
        }
        // Keep supporting identity-bound opaque entries issued to earlier clients.
        var entries=d.rows("SELECT * FROM chat_entry WHERE token=? AND openid=? AND expires_at>?",source,openid,current);
        if(entries.isEmpty())return null;
        String store=str(entries.get(0),"storeId");
        return store.isEmpty()||d.count("SELECT COUNT(*) FROM store WHERE id=? AND enabled=TRUE",store)==1?store:null;
    }
    @Transactional public Map<String,Object> entry(String customer,String store){store(store);String token="yht_"+id().replace("-","");d.update("DELETE FROM chat_entry WHERE expires_at<?",Instant.now().getEpochSecond());d.update("INSERT INTO chat_entry VALUES(?,?,?,?)",token,openid(customer),store,Instant.now().getEpochSecond()+86400);return Map.of("sessionFrom",token);}
    List<Map<String,Object>> conversations(String openid,String channel,String store){return d.rows("SELECT * FROM chat_conversation WHERE openid=? AND channel=? AND store_id=? ORDER BY CASE WHEN status='RESOLVED' THEN 1 ELSE 0 END,created_at DESC,id DESC LIMIT 1",openid,channel,store);}
    Map<String,Object> conversation(String openid,String channel,String store){var list=conversations(openid,channel,store);if(!list.isEmpty()&&!"RESOLVED".equals(list.get(0).get("status")))return list.get(0);String cid=id();String name="访客";var accounts=d.rows("SELECT a.display_name FROM customer_account a JOIN customer_identity i ON i.id=a.identity_id WHERE i.openid=?",openid);if(!accounts.isEmpty()&&!str(accounts.get(0),"displayName").isBlank())name=str(accounts.get(0),"displayName");var bookings=d.rows("SELECT customer_name FROM appointment a JOIN customer_identity c ON c.id=a.customer_id WHERE c.openid=? ORDER BY a.created_at DESC LIMIT 1",openid);if(name.equals("访客")&&!bookings.isEmpty())name=str(bookings.get(0),"customerName");d.update("INSERT INTO chat_conversation(id,openid,channel,store_id,display_name,status,last_preview,created_at,updated_at) VALUES(?,?,?,?,?,'OPEN','',?,?)",cid,openid,channel,store,name,now(),now());return d.one("SELECT * FROM chat_conversation WHERE id=?",cid);}
    @Transactional public Map<String,Object> webOpen(String customer,String store){store(store);lock();var list=conversations(openid(customer),"WEB",store);return publicConversation(list.isEmpty()?conversation(openid(customer),"WEB",store):list.get(0));}
    @Transactional public Map<String,Object> webRestart(String customer,String cid){lock();var c=owned(customer,cid);store(str(c,"storeId"));if(!"RESOLVED".equals(c.get("status")))throw error(409,"当前咨询尚未结束，请继续当前对话。");return publicConversation(conversation(openid(customer),"WEB",str(c,"storeId")));}
    Map<String,Object> owned(String customer,String id){var c=d.one("SELECT * FROM chat_conversation WHERE id=? AND channel='WEB' AND openid=?",id,openid(customer));return c;}
    Map<String,Object> scoped(Admin a,String id){var c=d.one("SELECT * FROM chat_conversation WHERE id=?",id);a.store(str(c,"storeId"));return c;}
    Map<String,Object> publicConversation(Map<String,Object> c){
        var out=new LinkedHashMap<>(c);out.remove("openid");out.remove("activeKey");out.remove("assignedAdminId");
        var stores=d.rows("SELECT name FROM store WHERE id=?",str(c,"storeId"));out.put("storeName",stores.isEmpty()?"总部客服":str(stores.get(0),"name"));
        boolean active=!"WECHAT".equals(c.get("channel"))||d.count("SELECT COUNT(*) FROM chat_route WHERE conversation_id=?",c.get("id"))>0;
        out.put("activeRoute",active);return out;
    }
    Map<String,Object> adminConversation(Admin a,Map<String,Object> c){
        var out=publicConversation(c);boolean permitted=a.permissions().contains("chat:reply"),mine=a.id().equals(str(c,"assignedAdminId"));
        boolean active="ACTIVE".equals(c.get("status")),waiting="OPEN".equals(c.get("status"));
        boolean route=bool(out,"activeRoute"),window=!"WECHAT".equals(c.get("channel"))||route&&((Number)c.get("replyDeadline")).longValue()>Instant.now().getEpochSecond()&&integer(c,"replyRemaining",0)>0;
        out.put("assignedAdminId",str(c,"assignedAdminId"));out.put("canAccept",permitted&&waiting&&route);
        out.put("canReply",permitted&&active&&mine&&window);out.put("canEnd",permitted&&active&&mine);
        out.put("canRelease",active&&(permitted&&mine||a.hq()&&a.permissions().contains("chat:route")));
        return out;
    }
    List<Map<String,Object>> messages(String cid,long after,long before){return d.rows("SELECT seq,id,direction,kind,content,delivery_status,error_message,sender_name,created_at FROM chat_message WHERE conversation_id=? AND seq>? AND seq<? ORDER BY seq "+(after>0?"ASC":"DESC")+" LIMIT 100",cid,Math.max(0,after),before<=0?Long.MAX_VALUE:before).stream().sorted(Comparator.comparingLong(m->((Number)m.get("seq")).longValue())).toList();}
    String message(String cid,String direction,String kind,String content,String media,String delivery,String sender,String key){String mid=id();d.update("INSERT INTO chat_message(id,conversation_id,direction,kind,content,media_id,delivery_status,sender_name,client_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",mid,cid,direction,kind,content,media,delivery,sender,key,now());var row=d.one("SELECT seq FROM chat_message WHERE id=?",mid);d.update("UPDATE chat_conversation SET last_seq=?,last_preview=?,updated_at=? WHERE id=?",row.get("seq"),content.substring(0,Math.min(content.length(),180)),now(),cid);return mid;}
    String clientKey(Map<String,Object> in){String key=text(in,"clientKey",8,80);if(!key.matches("[A-Za-z0-9_-]+"))throw error(400,"消息标识格式不正确。");return key;}
    String content(Map<String,Object> in){String value=text(in,"content",1,2000);if(value.getBytes(StandardCharsets.UTF_8).length>4000)throw error(400,"消息内容过长，请分段发送。");return value;}
    @Transactional public Map<String,Object> webSend(String customer,String cid,Map<String,Object> in){var c=owned(customer,cid);store(str(c,"storeId"));d.one("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE",cid);c=owned(customer,cid);String key=clientKey(in),body=content(in);var existing=d.rows("SELECT * FROM chat_message WHERE conversation_id=? AND client_key=?",cid,"web-"+key);if(!existing.isEmpty()){if(!body.equals(str(existing.get(0),"content")))throw error(409,"同一消息标识的内容发生变化，请重新发送。");return existing.get(0);}if("RESOLVED".equals(c.get("status")))throw error(409,"本次咨询已结束，请发起新咨询。");String mid=message(cid,"IN","text",body,null,"RECEIVED","客户","web-"+key);d.update("UPDATE chat_conversation SET last_customer_at=? WHERE id=?",Instant.now().getEpochSecond(),cid);return d.one("SELECT id,seq,delivery_status FROM chat_message WHERE id=?",mid);}
    Map<String,Object> list(Admin a,String store,String status,String q,int page){String scope;if(a.hq()){scope=store;if(!scope.isBlank())a.store(scope);}else{a.store(a.storeId());scope=a.storeId();}String where=" WHERE 1=1";List<Object> args=new ArrayList<>();if(!scope.isBlank()){where+=" AND c.store_id=?";args.add(scope);}if(!status.isBlank()){if(!Set.of("OPEN","ACTIVE","RESOLVED").contains(status))throw error(400,"会话状态不正确。");where+=" AND c.status=?";args.add(status);}if(!q.isBlank()){where+=" AND (c.display_name LIKE ? OR c.last_preview LIKE ?)";args.add("%"+q.substring(0,Math.min(q.length(),80))+"%");args.add(args.get(args.size()-1));}int total=d.count("SELECT COUNT(*) FROM chat_conversation c"+where,args.toArray());int unread=d.count("SELECT COUNT(*) FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id"+where+" AND m.direction='IN' AND m.seq>c.read_seq",args.toArray());int actualPage=Math.max(1,Math.min(page,Math.max(1,(total+29)/30)));args.add(30);args.add((actualPage-1)*30);return Map.of("total",total,"unreadTotal",unread,"page",actualPage,"pageSize",30,"items",d.rows("SELECT c.id,c.channel,c.store_id,c.display_name,c.status,c.assigned_admin_id,c.assigned_admin_name,c.accepted_at,c.ended_at,c.ended_by,c.last_preview,c.reply_deadline,c.reply_remaining,c.last_seq,c.read_seq,c.updated_at,(SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id=c.id AND m.direction='IN' AND m.seq>c.read_seq) unread, s.name store_name FROM chat_conversation c LEFT JOIN store s ON s.id=c.store_id"+where+" ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?",args.toArray()));}
    @Transactional public void markRead(Admin a,String cid,long through){var c=scoped(a,cid);long last=((Number)c.get("lastSeq")).longValue();d.update("UPDATE chat_conversation SET read_seq=GREATEST(read_seq,?) WHERE id=?",Math.min(Math.max(0,through),last),cid);}
    Map<String,Object> unread(Admin a){return d.one("SELECT COUNT(*) unread_total,COALESCE(MAX(m.seq),0) latest_seq FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id WHERE m.direction='IN' AND m.seq>c.read_seq"+(a.hq()?"":" AND c.store_id=?"),a.hq()?new Object[]{}:new Object[]{a.storeId()});}
    Map<String,Object> locked(Admin a,String cid){scoped(a,cid);d.one("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE",cid);return scoped(a,cid);}
    void receiver(Admin a,Map<String,Object> c){
        if("RESOLVED".equals(c.get("status")))throw error(409,"会话已结束，仅可查看记录。客户再次咨询后需接入新会话。");
        if(!"ACTIVE".equals(c.get("status")))throw error(409,"请先接入会话，再回复或结束对话。");
        if(!a.id().equals(str(c,"assignedAdminId")))throw error(409,"该会话由其他客服接待，请勿重复接入或回复。");
    }
    void noSending(String cid){
        if(d.count("SELECT COUNT(*) FROM chat_message WHERE conversation_id=? AND delivery_status='SENDING' AND created_at>?",cid,java.time.OffsetDateTime.now(ZONE).minusMinutes(2).toString())>0)throw error(409,"回复正在发送，请等待发送结果后再操作。");
        d.update("UPDATE chat_message SET delivery_status='UNKNOWN',error_message='发送过程未返回结果，请核实客户是否收到，避免重复发送。' WHERE conversation_id=? AND delivery_status='SENDING'",cid);
    }
    @Transactional public Map<String,Object> accept(Admin a,String cid){
        a.require("chat:reply");lock();var c=locked(a,cid);
        if("ACTIVE".equals(c.get("status"))&&a.id().equals(str(c,"assignedAdminId")))return adminConversation(a,c);
        if(!"OPEN".equals(c.get("status")))throw error(409,"会话已被其他客服接入或已结束，请刷新会话列表。");
        if(!bool(publicConversation(c),"activeRoute"))throw error(409,"客户已切换咨询门店，请由当前接待门店接入。");
        d.update("UPDATE chat_conversation SET status='ACTIVE',assigned_admin_id=?,assigned_admin_name=?,accepted_at=?,updated_at=? WHERE id=?",a.id(),a.name(),now(),now(),cid);
        d.audit(a,str(c,"storeId"),"chat.accept",cid,"接入客户会话");return adminConversation(a,scoped(a,cid));
    }
    @Transactional public Map<String,Object> end(Admin a,String cid){
        a.require("chat:reply");lock();var c=locked(a,cid);
        if("RESOLVED".equals(c.get("status"))&&a.id().equals(str(c,"assignedAdminId")))return adminConversation(a,c);
        receiver(a,c);noSending(cid);
        d.update("UPDATE chat_conversation SET status='RESOLVED',active_key=NULL,ended_at=?,ended_by=?,read_seq=last_seq,reply_remaining=0,updated_at=? WHERE id=?",now(),a.name(),now(),cid);
        d.audit(a,str(c,"storeId"),"chat.end",cid,"结束客户会话，保留历史记录");return adminConversation(a,scoped(a,cid));
    }
    @Transactional public Map<String,Object> release(Admin a,String cid){
        lock();var c=locked(a,cid);boolean supervisor=a.hq()&&a.permissions().contains("chat:route");
        if(!supervisor){a.require("chat:reply");receiver(a,c);}else if(!"ACTIVE".equals(c.get("status")))throw error(409,"只有接待中的会话可以退回待接入。");
        noSending(cid);d.update("UPDATE chat_conversation SET status='OPEN',assigned_admin_id=NULL,assigned_admin_name=NULL,accepted_at=NULL,updated_at=? WHERE id=?",now(),cid);
        d.audit(a,str(c,"storeId"),"chat.release",cid,"退回待接入，原接待人停止回复");return adminConversation(a,scoped(a,cid));
    }
    @Transactional public void status(Admin a,String cid,String status){
        scoped(a,cid);if(!"RESOLVED".equals(status))throw error(409,"旧会话不能重新打开，请通过接入新会话开始接待。");end(a,cid);
    }
    @Transactional public void route(Admin a,String cid,String store){AdminService.hq(a);store(store);if(store.isBlank())throw error(400,"请选择接待门店。");lock();var c=locked(a,cid);if(!"OPEN".equals(c.get("status")))throw error(409,"仅待接入会话可转交门店，请先退回待接入。");if(!str(c,"storeId").isBlank())throw error(409,"只有总部待分配会话可分流，已有门店会话不会跨店移动。");if(d.count("SELECT COUNT(*) FROM chat_conversation WHERE openid=? AND channel=? AND store_id=? AND active_key='LIVE'",c.get("openid"),c.get("channel"),store)>0)throw error(409,"该客户已有本店会话，请联系本店从原会话接待。");d.update("UPDATE chat_conversation SET store_id=?,updated_at=? WHERE id=?",store,now(),cid);d.audit(a,store,"chat.route",cid,"总部转交门店");}
    record Prepared(Map<String,Object> c,String messageId,boolean send,long inboundSeq){}
    Map<String,Object> reply(Admin a,String cid,Map<String,Object> in){String key="admin-"+clientKey(in),body=content(in);
        a.require("chat:reply");Prepared p=tx.execute(t->{var c=locked(a,cid);var old=d.rows("SELECT * FROM chat_message WHERE conversation_id=? AND client_key=?",cid,key);if(!old.isEmpty()){if(!body.equals(str(old.get(0),"content")))throw error(409,"同一消息标识的内容发生变化，请重新发送。");return new Prepared(c,str(old.get(0),"id"),false,0);}
            receiver(a,c);
            if("WECHAT".equals(c.get("channel"))){if(d.count("SELECT COUNT(*) FROM chat_route WHERE conversation_id=?",cid)==0)throw error(409,"客户已切换咨询门店，请由当前接待门店回复。");long deadline=((Number)c.get("replyDeadline")).longValue();if(deadline<=Instant.now().getEpochSecond()||integer(c,"replyRemaining",0)<=0)throw error(409,"微信回复窗口或可发条数已用完，请等待客户再次发消息。");d.update("UPDATE chat_conversation SET reply_remaining=reply_remaining-1 WHERE id=?",cid);}
            boolean send="WECHAT".equals(c.get("channel"));long inboundSeq=send?((Number)d.one("SELECT COALESCE(MAX(seq),0) seq FROM chat_message WHERE conversation_id=? AND direction='IN'",cid).get("seq")).longValue():0;
            String mid=message(cid,"OUT","text",body,null,send?"SENDING":"SENT",a.name(),key);d.audit(a,str(c,"storeId"),"chat.reply",mid,"发送客户回复");return new Prepared(c,mid,send,inboundSeq);});
        if(p.send){
            String state="UNKNOWN",reason="微信返回结果缺少有效错误码，发送结果尚未确认。请先核实客户是否收到，避免重复发送。",category="INVALID_RESULT";boolean notAttempted=false;
            try{
                var result=wx.sendCustomerText(str(p.c,"openid"),body);Object value=result==null?null:result.get("errcode");
                if(value instanceof Number number&&number.doubleValue()==number.intValue()){
                    int code=number.intValue();state=code==0?"SENT":"FAILED";reason=code==0?"":WechatGateway.failureReason(code);category="ERRCODE_"+code;
                }
            }catch(WechatGateway.CustomerSendNotAttemptedException e){state="FAILED";reason=e.getMessage();category="NOT_ATTEMPTED";notAttempted=true;}
            catch(WechatGateway.ExchangeException e){reason=e.getReason()+"发送结果尚未确认。请先核实客户是否收到，避免重复发送。";category=e.category;}
            catch(Exception e){reason="客服发送发生异常，发送结果尚未确认。请先核实客户是否收到，避免重复发送。";category="UNEXPECTED_"+e.getClass().getSimpleName();}
            if(!state.equals("SENT"))log.warn("WeChat customer send message={} state={} category={}",p.messageId,state,category);
            String delivery=state,detail=reason;boolean restoreQuota=notAttempted;
            tx.executeWithoutResult(t->{
                d.update("UPDATE chat_message SET delivery_status=?,error_message=? WHERE id=?",delivery,detail,p.messageId);
                if(restoreQuota){
                    d.one("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE",cid);
                    long inboundSeq=((Number)d.one("SELECT COALESCE(MAX(seq),0) seq FROM chat_message WHERE conversation_id=? AND direction='IN'",cid).get("seq")).longValue();
                    if(inboundSeq==p.inboundSeq)d.update("UPDATE chat_conversation SET reply_remaining=reply_remaining+1 WHERE id=? AND status='ACTIVE' AND reply_deadline=? AND last_customer_at=?",cid,p.c.get("replyDeadline"),p.c.get("lastCustomerAt"));
                }
            });
        }
        return d.one("SELECT seq,id,direction,kind,content,delivery_status,error_message,sender_name,created_at FROM chat_message WHERE id=?",p.messageId);
    }
    @Transactional public String receive(Map<String,Object> in){
        String openid=text(in,"FromUserName",1,160),kind=str(in,"MsgType"),event=str(in,"Event");
        if(!Set.of("text","image","miniprogrampage","event").contains(kind)||kind.equals("event")&&!event.equals("user_enter_tempsession"))return "IGNORED";
        String eventId=!str(in,"MsgId").isBlank()?"wx:"+str(in,"MsgId"):"event:"+UUID.nameUUIDFromBytes(d.stringify(in).getBytes(StandardCharsets.UTF_8));if(eventId.length()>160)throw error(400,"消息标识过长。");lock();if(d.count("SELECT COUNT(*) FROM chat_receipt WHERE event_id=?",eventId)>0)return "DUPLICATE";d.update("INSERT INTO chat_receipt VALUES(?,?)",eventId,now());
        long time;try{time=Long.parseLong(str(in,"CreateTime"));}catch(Exception e){throw error(400,"消息时间不正确。");}long current=Instant.now().getEpochSecond();time=Math.min(time,current);
        Map<String,Object> c=null;var routes=d.rows("SELECT r.event_time,c.* FROM chat_route r JOIN chat_conversation c ON c.id=r.conversation_id WHERE r.openid=?",openid);String entry=str(in,"SessionFrom");
        if(kind.equals("event")&&!entry.isBlank()){
            String store=entryStore(openid,entry,current);
            if(store!=null&&(routes.isEmpty()||time>=((Number)routes.get(0).get("eventTime")).longValue())){var previous=conversations(openid,"WECHAT",store);c=previous.isEmpty()?conversation(openid,"WECHAT",store):previous.get(0);}
        }
        if(c==null&&!routes.isEmpty())c=routes.get(0);
        if(c==null){var last=d.rows("SELECT a.store_id FROM appointment a JOIN customer_identity u ON u.id=a.customer_id WHERE u.openid=? ORDER BY a.created_at DESC LIMIT 1",openid);c=conversation(openid,"WECHAT",last.isEmpty()?"":str(last.get(0),"storeId"));}
        String cid=str(c,"id");c=d.one("SELECT * FROM chat_conversation WHERE id=? FOR UPDATE",cid);
        boolean historical=false;
        if("RESOLVED".equals(c.get("status"))){
            // Entering the native contact window is not a new customer message or an agent handover.
            if(kind.equals("event"))return "ENDED_ENTRY";
            long ended=java.time.OffsetDateTime.parse(str(c,"endedAt")).toEpochSecond();
            historical=time<ended;
            if(!historical){c=conversation(openid,"WECHAT",str(c,"storeId"));cid=str(c,"id");}
        }
        if(!historical){if(routes.isEmpty())d.update("INSERT INTO chat_route VALUES(?,?,?)",openid,cid,time);else if(time>=((Number)routes.get(0).get("eventTime")).longValue())d.update("UPDATE chat_route SET conversation_id=?,event_time=? WHERE openid=?",cid,time,openid);}
        if(kind.equals("event")){long deadline=time+60;if(deadline>((Number)c.get("replyDeadline")).longValue())d.update("UPDATE chat_conversation SET reply_deadline=?,reply_remaining=2,updated_at=? WHERE id=?",deadline,now(),cid);return "ENTRY";}
        String body=kind.equals("text")?str(in,"Content"):kind.equals("image")?"[客户图片]":"[小程序卡片] "+str(in,"Title");if(body.length()>16000)throw error(400,"消息内容过长。");message(cid,"IN",kind,body,kind.equals("image")?text(in,"MediaId",1,512):null,"RECEIVED","客户",null);
        if(historical){d.update("UPDATE chat_conversation SET read_seq=last_seq WHERE id=?",cid);return "HISTORY";}
        if(time>=((Number)c.get("lastCustomerAt")).longValue())d.update("UPDATE chat_conversation SET last_customer_at=?,reply_deadline=?,reply_remaining=5 WHERE id=?",time,time+172800,cid);
        return "STORED";
    }
    byte[] image(Admin a,String cid,String mid){scoped(a,cid);var m=d.one("SELECT media_id FROM chat_message WHERE id=? AND conversation_id=? AND kind='image'",mid,cid);return wx.customerImage(str(m,"mediaId"));}
}
