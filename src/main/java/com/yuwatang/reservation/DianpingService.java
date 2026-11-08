package com.yuwatang.reservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@Service
class DianpingService implements DianpingPort {
    static final List<String> CUSTOMER_HEADERS = List.of("externalShopId","externalCustomerId","name","phone");
    static final List<String> ORDER_HEADERS = List.of("externalShopId","externalOrderId","externalLineId","externalProductId","name","phone","status","amountYuan","headcount","bookingDate","startTime","createdAt","updatedAt");
    static final Set<String> STATUSES = Set.of("CREATED","PAID","BOOKED","REDEEMED","CANCELLED","REFUNDED");
    static final Map<String,String> STATUS_NAMES = Map.of("CREATED","待付款","PAID","已付款","BOOKED","已预约","REDEEMED","已核销","CANCELLED","已取消","REFUNDED","已退款");
    final Data d; final CatalogService catalog; final BookingService bookings;
    DianpingService(Data d,CatalogService catalog,BookingService bookings){this.d=d;this.catalog=catalog;this.bookings=bookings;}

    static Kind kind(String value){return switch(value){case "customers" -> Kind.CUSTOMERS;case "orders" -> Kind.ORDERS;default -> throw error(404,"大众点评导入类型不存在。");};}
    static List<String> headers(Kind kind){return kind==Kind.CUSTOMERS?CUSTOMER_HEADERS:ORDER_HEADERS;}
    static String identifier(Map<String,?> row,String key,boolean optional){String value=text(row,key,optional?0:1,100);if(value.chars().anyMatch(Character::isISOControl))throw error(400,key+"不能包含控制字符");return value;}
    Map<String,Object> shop(String externalShop){var rows=d.rows("SELECT * FROM dianping_store_mapping WHERE external_shop_id=?",externalShop);if(rows.isEmpty())throw error(400,"尚未映射点评门店："+externalShop);return rows.get(0);}
    Map<String,Object> product(String externalShop,String product){var rows=d.rows("SELECT * FROM dianping_service_mapping WHERE external_shop_id=? AND external_product_id=?",externalShop,product);if(rows.isEmpty())throw error(400,"尚未映射点评品项："+externalShop+" / "+product);return rows.get(0);}
    Map<String,Object> localService(String store,String service){return catalog.storeServices(store,false).stream().filter(s->service.equals(str(s,"id"))).findFirst().orElseThrow(()->error(400,"映射的本店品项已不存在，请到品牌与内容核对。"));}

    Map<String,Object> config(Admin actor){
        actor.require("integrations:read");AdminService.hq(actor);
        var stores=d.rows("SELECT id,name FROM store ORDER BY name");
        for(var store:stores)store.put("services",catalog.storeServices(str(store,"id"),false).stream().map(s->Map.of("id",s.get("id"),"name",s.get("name"),"enabled",bool(s,"enabled"))).toList());
        return Map.of("schemaVersion",SCHEMA_VERSION,"port","DianpingPort","fileImportAvailable",true,"automaticSyncConnected",false,
            "stores",stores,"shops",d.rows("SELECT * FROM dianping_store_mapping ORDER BY external_shop_id"),
            "products",d.rows("SELECT * FROM dianping_service_mapping ORDER BY external_shop_id,external_product_id"),
            "orderCount",d.count("SELECT COUNT(*) FROM dianping_order"),"customerLinkCount",d.count("SELECT COUNT(*) FROM dianping_customer_link"),
            "batches",d.rows("SELECT * FROM dianping_import_batch ORDER BY created_at DESC,id DESC LIMIT 10"));
    }
    @Transactional public Map<String,Object> saveShop(Admin actor,Map<String,Object> in){
        actor.require("imports:write");AdminService.hq(actor);catalog.bookingLock();
        String external=identifier(in,"externalShopId",false),name=text(in,"externalShopName",1,100),store=text(in,"storeId",1,64);catalog.store(store);
        var old=d.rows("SELECT * FROM dianping_store_mapping WHERE external_shop_id=?",external);int revision=old.isEmpty()?0:integer(old.get(0),"revision",0);
        if(integer(in,"revision",-1)!=revision)throw error(409,"点评门店映射已更新，请刷新后保存。");
        if(!old.isEmpty()&&!store.equals(str(old.get(0),"storeId"))&&(d.count("SELECT COUNT(*) FROM dianping_service_mapping WHERE external_shop_id=?",external)>0||d.count("SELECT COUNT(*) FROM dianping_customer_link WHERE external_shop_id=?",external)>0||d.count("SELECT COUNT(*) FROM dianping_order WHERE external_shop_id=?",external)>0))throw error(409,"该点评门店已有品项映射或导入记录，不能直接改绑门店。");
        if(old.isEmpty())d.update("INSERT INTO dianping_store_mapping VALUES(?,?,?,?,?)",external,name,store,1,now());
        else d.update("UPDATE dianping_store_mapping SET external_shop_name=?,store_id=?,revision=revision+1,updated_at=? WHERE external_shop_id=?",name,store,now(),external);
        d.audit(actor,store,"dianping.shop.map",external,"点评门店映射");return d.one("SELECT * FROM dianping_store_mapping WHERE external_shop_id=?",external);
    }
    @Transactional public Map<String,Object> saveProduct(Admin actor,Map<String,Object> in){
        actor.require("imports:write");AdminService.hq(actor);catalog.bookingLock();
        String external=identifier(in,"externalShopId",false),product=identifier(in,"externalProductId",false),name=text(in,"externalProductName",1,100),service=text(in,"serviceId",1,64);
        String store=str(shop(external),"storeId");localService(store,service);
        var old=d.rows("SELECT * FROM dianping_service_mapping WHERE external_shop_id=? AND external_product_id=?",external,product);int revision=old.isEmpty()?0:integer(old.get(0),"revision",0);
        if(integer(in,"revision",-1)!=revision)throw error(409,"点评品项映射已更新，请刷新后保存。");
        if(!old.isEmpty()&&!service.equals(str(old.get(0),"serviceId"))&&d.count("SELECT COUNT(*) FROM dianping_order WHERE external_shop_id=? AND external_product_id=?",external,product)>0)throw error(409,"该点评品项已有导入订单，不能直接改绑本店项目。");
        if(old.isEmpty())d.update("INSERT INTO dianping_service_mapping VALUES(?,?,?,?,?,?)",external,product,name,service,1,now());
        else d.update("UPDATE dianping_service_mapping SET external_product_name=?,service_id=?,revision=revision+1,updated_at=? WHERE external_shop_id=? AND external_product_id=?",name,service,now(),external,product);
        d.audit(actor,store,"dianping.product.map",product,"点评品项映射；点评门店="+external);return d.one("SELECT * FROM dianping_service_mapping WHERE external_shop_id=? AND external_product_id=?",external,product);
    }

    String timestamp(Map<String,Object> in,String key){
        String value=text(in,key,1,40);
        try{return OffsetDateTime.parse(value).toInstant().toString();}catch(DateTimeParseException ignored){}
        try{return LocalDateTime.parse(value,DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).atZone(ZONE).toInstant().toString();}
        catch(DateTimeParseException e){throw error(400,key+"应为yyyy-MM-dd HH:mm:ss或带时区的ISO时间");}
    }
    Map<String,Object> normalize(Kind kind,Map<String,Object> in){
        Map<String,Object> out=new LinkedHashMap<>();String external=identifier(in,"externalShopId",false);String store=str(shop(external),"storeId");
        out.put("externalShopId",external);out.put("storeId",store);out.put("name",text(in,"name",1,40));String phone=text(in,"phone",kind==Kind.CUSTOMERS?11:0,32);
        boolean fullPhone=phone.matches("1[3-9]\\d{9}");
        if(!fullPhone&&!(kind==Kind.ORDERS&&(phone.isEmpty()||phone.matches("1[3-9]\\d[*•]{4}\\d{4}"))))throw error(400,kind==Kind.CUSTOMERS?"客户导入必须提供完整11位手机号":"电话须为空、完整手机号或138****1234格式的脱敏号码");
        out.put("phone",phone);
        if(kind==Kind.CUSTOMERS){out.put("externalCustomerId",identifier(in,"externalCustomerId",true));return out;}
        String order=identifier(in,"externalOrderId",false),line=identifier(in,"externalLineId",true),product=identifier(in,"externalProductId",false);
        String service=str(product(external,product),"serviceId");var local=localService(store,service);
        out.put("externalOrderId",order);out.put("externalLineId",line.isEmpty()?"main":line);out.put("externalProductId",product);out.put("serviceId",service);out.put("serviceName",local.get("name"));
        String status=text(in,"status",1,24).toUpperCase(Locale.ROOT);for(var entry:STATUS_NAMES.entrySet())if(entry.getValue().equals(status))status=entry.getKey();
        if(!STATUSES.contains(status))throw error(400,"status须为CREATED/PAID/BOOKED/REDEEMED/CANCELLED/REFUNDED或模板说明中的中文状态");out.put("status",status);
        try{String amount=text(in,"amountYuan",1,20);if(!amount.matches("\\d{1,10}(\\.\\d{1,2})?"))throw new NumberFormatException();long fen=new BigDecimal(amount).movePointRight(2).longValueExact();out.put("amountFen",fen);}
        catch(ArithmeticException|NumberFormatException e){throw error(400,"amountYuan应为非负金额，最多两位小数");}
        int people=str(in,"headcount").isBlank()?1:integer(in,"headcount",1);if(people<1||people>100)throw error(400,"headcount应为1至100的整数；预约转换时还需满足本店人数限制");out.put("headcount",people);
        String date=text(in,"bookingDate",0,10),time=text(in,"startTime",0,5);
        if(date.isEmpty()!=time.isEmpty())throw error(400,"bookingDate与startTime须同时填写或同时留空");
        if(!date.isEmpty())try{if(!LocalDate.parse(date).toString().equals(date)||!time.matches("\\d{2}:\\d{2}"))throw new DateTimeException("format");LocalTime.parse(time);}catch(DateTimeException e){throw error(400,"预约时间须为有效yyyy-MM-dd与HH:mm，历史日期可以导入");}
        out.put("bookingDate",date);out.put("startTime",time);String created=timestamp(in,"createdAt"),updated=timestamp(in,"updatedAt");
        if(Instant.parse(updated).isBefore(Instant.parse(created)))throw error(400,"updatedAt不能早于createdAt");out.put("createdAt",created);out.put("updatedAt",updated);return out;
    }
    String hash(Map<String,Object> payload){var external=new LinkedHashMap<>(payload);for(String key:List.of("storeId","serviceId","serviceName","_row","_action"))external.remove(key);return bookings.hash(external);}
    static String fileHash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    List<Map<String,Object>> existingOrder(Map<String,Object> row){return d.rows("SELECT * FROM dianping_order WHERE external_shop_id=? AND external_order_id=? AND external_line_id=?",row.get("externalShopId"),row.get("externalOrderId"),row.get("externalLineId"));}
    String action(Kind kind,Map<String,Object> row){
        if(kind==Kind.CUSTOMERS){
            String external=str(row,"externalCustomerId");var profile=d.rows("SELECT id FROM customer_profile WHERE phone=?",row.get("phone"));
            if(!external.isEmpty()){
                var link=d.rows("SELECT customer_profile_id FROM dianping_customer_link WHERE external_shop_id=? AND external_customer_id=?",row.get("externalShopId"),external);
                if(!link.isEmpty()&&(profile.isEmpty()||!Objects.equals(link.get(0).get("customerProfileId"),profile.get(0).get("id"))))throw error(400,"同一点评客户编号已关联其他手机号，请先核对客户资料");
                if(link.isEmpty())return "CREATE";
            }
            return profile.isEmpty()||d.count("SELECT COUNT(*) FROM store_customer WHERE store_id=? AND customer_profile_id=?",row.get("storeId"),profile.get(0).get("id"))==0?"CREATE":"SKIP";
        }
        var old=existingOrder(row);if(old.isEmpty())return "CREATE";
        if(!Objects.equals(old.get(0).get("storeId"),row.get("storeId"))||!Objects.equals(old.get(0).get("serviceId"),row.get("serviceId")))throw error(400,"订单更新不能改变已映射门店或本店品项");
        if(hash(row).equals(str(old.get(0),"dataHash")))return "SKIP";
        if(!Instant.parse(str(row,"updatedAt")).isAfter(Instant.parse(str(old.get(0),"externalUpdatedAt"))))throw error(400,"该订单已有同一时间或更新版本，不能覆盖；请核对updatedAt");
        return "UPDATE";
    }
    /** Whole-file validation precedes all writes; repeats are harmless and stale updates fail closed. */
    @Override @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> ingest(Admin actor,Kind kind,List<Map<String,Object>> input,boolean commit,Source source){
        actor.require("imports:write");AdminService.hq(actor);if(commit)catalog.bookingLock();
        if(input.size()>5000)throw error(400,"单次最多5000条资料。");
        List<String> errors=new ArrayList<>();List<Map<String,Object>> rows=new ArrayList<>();Set<List<String>> keys=new HashSet<>(),customerIds=new HashSet<>();
        int created=0,updated=0,skipped=0;
        if(input.isEmpty())errors.add("请至少填写一行资料。");
        for(var raw:input)try{
            var row=normalize(kind,raw);List<String> key=kind==Kind.CUSTOMERS?List.of(str(row,"externalShopId"),str(row,"phone")):List.of(str(row,"externalShopId"),str(row,"externalOrderId"),str(row,"externalLineId"));
            if(!keys.add(key))throw error(400,"文件内存在重复的客户或订单明细编号");
            if(kind==Kind.CUSTOMERS&&!str(row,"externalCustomerId").isEmpty()&&!customerIds.add(List.of(str(row,"externalShopId"),str(row,"externalCustomerId"))))throw error(400,"文件内点评客户编号重复");
            String action=action(kind,row);row.put("_action",action);row.put("_row",raw.getOrDefault("_row",rows.size()+2));rows.add(row);
            switch(action){case "CREATE"->created++;case "UPDATE"->updated++;default->skipped++;}
        }catch(ResponseStatusException e){if(errors.size()<100)errors.add("第"+raw.getOrDefault("_row","?")+"行："+e.getReason());}
        if(commit&&!errors.isEmpty())throw error(400,"文件未通过预检，未写入任何资料："+errors.get(0));
        String batch="";
        if(commit){
            List<String> resources=new ArrayList<>();for(var row:rows){resources.add("store:"+str(row,"storeId"));resources.add("phone:"+str(row,"phone"));}catalog.guard(resources);
            batch=id();String sourceName=Objects.toString(source.name(),"file");if(sourceName.length()>200)sourceName=sourceName.substring(sourceName.length()-200);
            d.update("INSERT INTO dianping_import_batch VALUES(?,?,?,?,?,?,?,?,?,?,?)",batch,kind.name(),sourceName,source.sha256(),input.size(),created,updated,skipped,actor.id(),actor.name(),now());
            for(var row:rows){String operation=(String)row.remove("_action");row.remove("_row");if(operation.equals("SKIP")){
                    var existing=d.rows("SELECT id FROM customer_profile WHERE phone=?",str(row,"phone"));
                    if(!existing.isEmpty())CustomerProfiles.source(d,str(existing.get(0),"id"),"DIANPING");
                    continue;
                }
                String phone=str(row,"phone"),store=str(row,"storeId");
                if(phone.matches("1[3-9]\\d{9}"))bookings.customerRelation(store,str(row,"name"),phone,"DIANPING");
                if(kind==Kind.CUSTOMERS){String external=str(row,"externalCustomerId");if(!external.isEmpty()&&d.count("SELECT COUNT(*) FROM dianping_customer_link WHERE external_shop_id=? AND external_customer_id=?",row.get("externalShopId"),external)==0)d.update("INSERT INTO dianping_customer_link VALUES(?,?,?)",row.get("externalShopId"),external,d.one("SELECT id FROM customer_profile WHERE phone=?",phone).get("id"));}
                else saveOrder(row,operation,batch);
            }
            d.audit(actor,null,"dianping.import."+kind.name().toLowerCase(Locale.ROOT),batch,"总数="+input.size()+"；新增="+created+"；更新="+updated+"；跳过="+skipped);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("valid",errors.isEmpty());result.put("total",input.size());result.put("created",created);result.put("updated",updated);result.put("skipped",skipped);result.put("errors",errors);
        result.put("rows",rows.stream().limit(8).map(row->{var masked=new LinkedHashMap<>(row);masked.put("phone",maskedPhone(str(row,"phone")));return masked;}).toList());result.put("imported",commit?created+updated:0);result.put("committed",commit);result.put("batchId",batch);return result;
    }
    void saveOrder(Map<String,Object> row,String action,String batch){
        String json=d.stringify(row),hash=hash(row),timestamp=now();
        if(action.equals("CREATE"))d.update("INSERT INTO dianping_order (id,external_shop_id,external_order_id,external_line_id,external_product_id,store_id,service_id,service_name,customer_name,phone,order_status,amount_fen,headcount,booking_date,start_time,external_created_at,external_updated_at,payload_json,data_hash,appointment_id,needs_review,revision,import_batch_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id(),row.get("externalShopId"),row.get("externalOrderId"),row.get("externalLineId"),row.get("externalProductId"),row.get("storeId"),row.get("serviceId"),row.get("serviceName"),row.get("name"),row.get("phone"),row.get("status"),row.get("amountFen"),row.get("headcount"),row.get("bookingDate"),row.get("startTime"),row.get("createdAt"),row.get("updatedAt"),json,hash,null,false,1,batch,timestamp,timestamp);
        else {
            var old=existingOrder(row).get(0);
            if(!Objects.equals(old.get("storeId"),row.get("storeId"))||!Objects.equals(old.get("serviceId"),row.get("serviceId")))throw error(409,"订单更新不能改变已映射门店或本店品项。");
            d.update("UPDATE dianping_order SET external_product_id=?,customer_name=?,phone=?,order_status=?,amount_fen=?,headcount=?,booking_date=?,start_time=?,external_created_at=?,external_updated_at=?,payload_json=?,data_hash=?,needs_review=?,revision=revision+1,import_batch_id=?,updated_at=? WHERE id=?",
                row.get("externalProductId"),row.get("name"),row.get("phone"),row.get("status"),row.get("amountFen"),row.get("headcount"),row.get("bookingDate"),row.get("startTime"),row.get("createdAt"),row.get("updatedAt"),json,hash,!str(old,"appointmentId").isEmpty(),batch,timestamp,old.get("id"));
        }
    }
    static String maskedPhone(String phone){return phone.isEmpty()?"":phone.length()==11?phone.substring(0,3)+"****"+phone.substring(7):"";}
    Map<String,Object> order(Admin actor,String id){var row=d.one("SELECT * FROM dianping_order WHERE id=?",id);actor.store(str(row,"storeId"));return row;}
    Map<String,Object> orderView(Map<String,Object> row){var out=new LinkedHashMap<>(row);out.remove("payloadJson");out.remove("dataHash");out.put("phone",maskedPhone(str(row,"phone")));out.put("storeName",catalog.store(str(row,"storeId")).get("name"));out.put("statusLabel",STATUS_NAMES.get(str(row,"orderStatus")));return out;}
    Map<String,Object> orders(Admin actor,Map<String,String> filters){
        actor.require("appointments:read");String requested=filters.getOrDefault("storeId","");if(!requested.isBlank()){actor.store(requested);catalog.store(requested);}String store=actor.hq()?requested:actor.storeId();
        int page=Math.max(1,integer(filters,"page",1)),size=20;page=Math.min(page,1000000);List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
        if(!store.isBlank()){where+=" AND store_id=?";args.add(store);}String status=filters.getOrDefault("status","");if(!status.isBlank()){if(!STATUSES.contains(status))throw error(400,"点评订单状态无效。");where+=" AND order_status=?";args.add(status);}
        String search=filters.getOrDefault("search","").trim();if(search.length()>100)throw error(400,"搜索词过长。");if(!search.isEmpty()){where+=" AND (external_order_id=? OR external_product_id=?)";args.add(search);args.add(search);}
        int total=d.count("SELECT COUNT(*) FROM dianping_order"+where,args.toArray());args.add(size);args.add((page-1)*size);
        return Map.of("total",total,"items",d.rows("SELECT * FROM dianping_order"+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::orderView).toList());
    }
    Map<String,Object> slots(Admin actor,String id,String date){actor.require("appointments:write");var row=order(actor,id);return catalog.slots(str(row,"storeId"),date);}
    @Transactional public Map<String,Object> convert(Admin actor,String id,Map<String,Object> in){
        actor.require("appointments:write");catalog.bookingLock();var row=order(actor,id);
        if(!str(row,"appointmentId").isEmpty())return bookings.view(bookings.get(str(row,"appointmentId")),true);
        if(integer(in,"revision",-1)!=integer(row,"revision",0))throw error(409,"点评订单已更新，请刷新后确认。");
        if(!Set.of("PAID","BOOKED").contains(str(row,"orderStatus")))throw error(409,"仅已付款或已预约的点评订单可以确认新预约。");
        Map<String,Object> booking=new LinkedHashMap<>();booking.put("storeId",row.get("storeId"));booking.put("serviceId",row.get("serviceId"));booking.put("source","DIANPING");booking.put("name",row.get("customerName"));
        booking.put("phone",str(in,"phone").isBlank()?row.get("phone"):text(in,"phone",11,11));booking.put("date",text(in,"date",10,10));booking.put("startTime",text(in,"startTime",5,5));booking.put("headcount",integer(in,"headcount",integer(row,"headcount",1)));booking.put("memberClaim","UNKNOWN");booking.put("visitedClaim","UNKNOWN");booking.put("remark","大众点评订单："+row.get("externalOrderId")+"；明细："+row.get("externalLineId"));
        var created=bookings.create(booking,null,actor,"dianping-"+id);d.update("UPDATE dianping_order SET appointment_id=?,revision=revision+1,updated_at=? WHERE id=?",created.get("id"),now(),id);
        d.audit(actor,str(row,"storeId"),"dianping.order.book",id,"关联预约="+created.get("id"));return created;
    }
    @Transactional public Map<String,Object> reviewed(Admin actor,String id,Map<String,Object> in){
        actor.require("appointments:write");catalog.bookingLock();var row=order(actor,id);
        if(integer(in,"revision",-1)!=integer(row,"revision",0))throw error(409,"点评订单已更新，请刷新后核对。");
        if(str(row,"appointmentId").isEmpty())throw error(400,"该订单尚未关联预约。");
        d.update("UPDATE dianping_order SET needs_review=FALSE,revision=revision+1,updated_at=? WHERE id=?",now(),id);d.audit(actor,str(row,"storeId"),"dianping.order.review",id,"已核对平台更新与关联预约");return orderView(order(actor,id));
    }
}
