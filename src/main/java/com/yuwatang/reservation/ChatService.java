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
    Map<String,Object> conversation(String openid,String channel,String store){var list=d.rows("SELECT * FROM chat_conversation WHERE openid=? AND channel=? AND store_id=?",openid,channel,store);if(!list.isEmpty())return list.get(0);String cid=id();String name="访客";var bookings=d.rows("SELECT customer_name FROM appointment a JOIN customer_identity c ON c.id=a.customer_id WHERE c.openid=? ORDER BY a.created_at DESC LIMIT 1",openid);if(!bookings.isEmpty())name=str(bookings.get(0),"customerName");d.update("INSERT INTO chat_conversation(id,openid,channel,store_id,display_name,status,last_preview,created_at,updated_at) VALUES(?,?,?,?,?,'OPEN','',?,?)",cid,openid,channel,store,name,now(),now());return d.one("SELECT * FROM chat_conversation WHERE id=?",cid);}
    @Transactional public Map<String,Object> webOpen(String customer,String store){store(store);lock();return publicConversation(conversation(openid(customer),"WEB",store));}
    Map<String,Object> owned(String customer,String id){var c=d.one("SELECT * FROM chat_conversation WHERE id=? AND channel='WEB' AND openid=?",id,openid(customer));return c;}
    Map<String,Object> scoped(Admin a,String id){var c=d.one("SELECT * FROM chat_conversation WHERE id=?",id);a.store(str(c,"storeId"));return c;}
    Map<String,Object> publicConversation(Map<String,Object> c){
        var out=new LinkedHashMap<>(c);out.remove("openid");
        var stores=d.rows("SELECT name FROM store WHERE id=?",str(c,"storeId"));out.put("storeName",stores.isEmpty()?"总部客服":str(stores.get(0),"name"));
        boolean active=!"WECHAT".equals(c.get("channel"))||d.count("SELECT COUNT(*) FROM chat_route WHERE conversation_id=?",c.get("id"))>0;
        out.put("activeRoute",active);return out;
    }
    List<Map<String,Object>> messages(String cid,long after,long before){return d.rows("SELECT seq,id,direction,kind,content,delivery_status,error_message,sender_name,created_at FROM chat_message WHERE conversation_id=? AND seq>? AND seq<? ORDER BY seq "+(after>0?"ASC":"DESC")+" LIMIT 100",cid,Math.max(0,after),before<=0?Long.MAX_VALUE:before).stream().sorted(Comparator.comparingLong(m->((Number)m.get("seq")).longValue())).toList();}
    String message(String cid,String direction,String kind,String content,String media,String delivery,String sender,String key){String mid=id();d.update("INSERT INTO chat_message(id,conversation_id,direction,kind,content,media_id,delivery_status,sender_name,client_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",mid,cid,direction,kind,content,media,delivery,sender,key,now());var row=d.one("SELECT seq FROM chat_message WHERE id=?",mid);d.update("UPDATE chat_conversation SET last_seq=?,last_preview=?,updated_at=?,status='OPEN' WHERE id=?",row.get("seq"),content.substring(0,Math.min(content.length(),180)),now(),cid);return mid;}
    String clientKey(Map<String,Object> in){String key=text(in,"clientKey",8,80);if(!key.matches("[A-Za-z0-9_-]+"))throw error(400,"消息标识格式不正确。");return key;}
    String content(Map<String,Object> in){String value=text(in,"content",1,2000);if(value.getBytes(StandardCharsets.UTF_8).length>4000)throw error(400,"消息内容过长，请分段发送。");return value;}
    @Transactional public Map<String,Object> webSend(String customer,String cid,Map<String,Object> in){var c=owned(customer,cid);store(str(c,"storeId"));d.one("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE",cid);String key=clientKey(in),body=content(in);var existing=d.rows("SELECT * FROM chat_message WHERE conversation_id=? AND client_key=?",cid,"web-"+key);if(!existing.isEmpty()){if(!body.equals(str(existing.get(0),"content")))throw error(409,"同一消息标识的内容发生变化，请重新发送。");return existing.get(0);}String mid=message(cid,"IN","text",body,null,"RECEIVED","客户","web-"+key);d.update("UPDATE chat_conversation SET last_customer_at=? WHERE id=?",Instant.now().getEpochSecond(),cid);return d.one("SELECT id,seq,delivery_status FROM chat_message WHERE id=?",mid);}
    Map<String,Object> list(Admin a,String store,String status,String q,int page){String scope;if(a.hq()){scope=store;if(!scope.isBlank())a.store(scope);}else{a.store(a.storeId());scope=a.storeId();}String where=" WHERE 1=1";List<Object> args=new ArrayList<>();if(!scope.isBlank()){where+=" AND c.store_id=?";args.add(scope);}if(!status.isBlank()){if(!Set.of("OPEN","RESOLVED").contains(status))throw error(400,"会话状态不正确。");where+=" AND c.status=?";args.add(status);}if(!q.isBlank()){where+=" AND (c.display_name LIKE ? OR c.last_preview LIKE ?)";args.add("%"+q.substring(0,Math.min(q.length(),80))+"%");args.add(args.get(args.size()-1));}int total=d.count("SELECT COUNT(*) FROM chat_conversation c"+where,args.toArray());int unread=d.count("SELECT COUNT(*) FROM chat_message m JOIN chat_conversation c ON c.id=m.conversation_id"+where+" AND m.direction='IN' AND m.seq>c.read_seq",args.toArray());int actualPage=Math.max(1,Math.min(page,Math.max(1,(total+29)/30)));args.add(30);args.add((actualPage-1)*30);return Map.of("total",total,"unreadTotal",unread,"page",actualPage,"pageSize",30,"items",d.rows("SELECT c.id,c.channel,c.store_id,c.display_name,c.status,c.last_preview,c.reply_deadline,c.reply_remaining,c.last_seq,c.read_seq,c.updated_at,(SELECT COUNT(*) FROM chat_message m WHERE m.conversation_id=c.id AND m.direction='IN' AND m.seq>c.read_seq) unread, s.name store_name FROM chat_conversation c LEFT JOIN store s ON s.id=c.store_id"+where+" ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?",args.toArray()));}
    @Transactional public void markRead(Admin a,String cid,long through){var c=scoped(a,cid);long last=((Number)c.get("lastSeq")).longValue();d.update("UPDATE chat_conversation SET read_seq=GREATEST(read_seq,?) WHERE id=?",Math.min(Math.max(0,through),last),cid);}
    @Transactional public void status(Admin a,String cid,String status){var c=scoped(a,cid);if(!Set.of("OPEN","RESOLVED").contains(status))throw error(400,"会话状态不正确。");d.update("UPDATE chat_conversation SET status=? WHERE id=?",status,cid);d.audit(a,str(c,"storeId"),"chat.status",cid,status);}
    @Transactional public void route(Admin a,String cid,String store){AdminService.hq(a);store(store);if(store.isBlank())throw error(400,"请选择接待门店。");lock();var c=scoped(a,cid);if(!str(c,"storeId").isBlank())throw error(409,"只有总部待分配会话可分流，已有门店会话不会跨店移动。");if(d.count("SELECT COUNT(*) FROM chat_conversation WHERE openid=? AND channel=? AND store_id=?",c.get("openid"),c.get("channel"),store)>0)throw error(409,"该客户已有本店会话，请联系本店从原会话接待。");d.update("UPDATE chat_conversation SET store_id=? WHERE id=?",store,cid);d.audit(a,store,"chat.route",cid,"总部转交门店");}
    record Prepared(Map<String,Object> c,String messageId,boolean send,long inboundSeq){}
    Map<String,Object> reply(Admin a,String cid,Map<String,Object> in){String key="admin-"+clientKey(in),body=content(in);
        Prepared p=tx.execute(t->{scoped(a,cid);d.one("SELECT id FROM chat_conversation WHERE id=? FOR UPDATE",cid);var c=scoped(a,cid);var old=d.rows("SELECT * FROM chat_message WHERE conversation_id=? AND client_key=?",cid,key);if(!old.isEmpty()){if(!body.equals(str(old.get(0),"content")))throw error(409,"同一消息标识的内容发生变化，请重新发送。");return new Prepared(c,str(old.get(0),"id"),false,0);}
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
                    if(inboundSeq==p.inboundSeq)d.update("UPDATE chat_conversation SET reply_remaining=reply_remaining+1 WHERE id=? AND reply_deadline=? AND last_customer_at=?",cid,p.c.get("replyDeadline"),p.c.get("lastCustomerAt"));
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
            if(store!=null&&(routes.isEmpty()||time>=((Number)routes.get(0).get("eventTime")).longValue()))c=conversation(openid,"WECHAT",store);
        }
        if(c==null&&!routes.isEmpty())c=routes.get(0);
        if(c==null){var last=d.rows("SELECT a.store_id FROM appointment a JOIN customer_identity u ON u.id=a.customer_id WHERE u.openid=? ORDER BY a.created_at DESC LIMIT 1",openid);c=conversation(openid,"WECHAT",last.isEmpty()?"":str(last.get(0),"storeId"));}
        String cid=str(c,"id");c=d.one("SELECT * FROM chat_conversation WHERE id=? FOR UPDATE",cid);if(routes.isEmpty())d.update("INSERT INTO chat_route VALUES(?,?,?)",openid,cid,time);else if(time>=((Number)routes.get(0).get("eventTime")).longValue())d.update("UPDATE chat_route SET conversation_id=?,event_time=? WHERE openid=?",cid,time,openid);
        if(kind.equals("event")){long deadline=time+60;if(deadline>((Number)c.get("replyDeadline")).longValue())d.update("UPDATE chat_conversation SET reply_deadline=?,reply_remaining=2,updated_at=?,status='OPEN' WHERE id=?",deadline,now(),cid);return "ENTRY";}
        String body=kind.equals("text")?str(in,"Content"):kind.equals("image")?"[客户图片]":"[小程序卡片] "+str(in,"Title");if(body.length()>16000)throw error(400,"消息内容过长。");message(cid,"IN",kind,body,kind.equals("image")?text(in,"MediaId",1,512):null,"RECEIVED","客户",null);
        if(time>=((Number)c.get("lastCustomerAt")).longValue())d.update("UPDATE chat_conversation SET last_customer_at=?,reply_deadline=?,reply_remaining=5 WHERE id=?",time,time+172800,cid);
        return "STORED";
    }
    byte[] image(Admin a,String cid,String mid){scoped(a,cid);var m=d.one("SELECT media_id FROM chat_message WHERE id=? AND conversation_id=? AND kind='image'",mid,cid);return wx.customerImage(str(m,"mediaId"));}
}
