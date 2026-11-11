package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import static com.yuwatang.reservation.Data.*;

/** Import help and example rows share the exact headers used by the importers. */
@RestController @RequestMapping("/api/admin/import-guides")
class ImportGuides {
    final AuthService auth;final FilesApi files;
    ImportGuides(AuthService auth,FilesApi files){this.auth=auth;this.files=files;}
    record Field(String key,String label,boolean required,String example,String description){}
    record Guide(String kind,String title,List<Field> fields,List<Map<String,String>> rows,List<String> notes){}

    @GetMapping("/{kind}") @Permit("imports:write")
    Guide guide(HttpServletRequest request,@PathVariable String kind){AdminService.hq(auth.admin(request));return describe(kind);}

    @GetMapping("/{kind}/example") @Permit("imports:write")
    ResponseEntity<byte[]> example(HttpServletRequest request,@PathVariable String kind)throws IOException{
        AdminService.hq(auth.admin(request));Guide guide=describe(kind);
        var headers=guide.fields().stream().map(Field::key).toList();
        var rows=guide.rows().stream().map(row->headers.stream().map(key->(Object)row.getOrDefault(key,"")).toList()).toList();
        byte[] data=files.workbook("example.xlsx",headers,rows).getBody();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(Objects.requireNonNull(data)));var output=new ByteArrayOutputStream()){
            var text=book.createCellStyle();text.setWrapText(true);text.setVerticalAlignment(VerticalAlignment.TOP);text.setDataFormat(book.createDataFormat().getFormat("@"));
            var header=book.createCellStyle();header.cloneStyleFrom(text);header.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font=book.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());header.setFont(font);
            var sheet=book.getSheetAt(0);sheet.setAutoFilter(new CellRangeAddress(0,sheet.getLastRowNum(),0,headers.size()-1));
            for(int c=0;c<headers.size();c++){sheet.setColumnWidth(c,Math.min(60,Math.max(20,headers.get(c).length()+4))*256);sheet.setDefaultColumnStyle(c,text);}
            for(int r=1;r<=sheet.getLastRowNum();r++){sheet.getRow(r).setHeightInPoints(36);for(var cell:sheet.getRow(r))cell.setCellStyle(text);}
            var help=book.createSheet("填写说明");help.setColumnWidth(0,26*256);help.setColumnWidth(1,20*256);help.setColumnWidth(2,12*256);help.setColumnWidth(3,70*256);help.setColumnWidth(4,35*256);
            int index=0;
            var title=help.createRow(index++);title.setHeightInPoints(28);cell(title,0,guide.title()+" · 导入填写示例",header);help.addMergedRegion(new CellRangeAddress(0,0,0,4));
            for(String note:guide.notes()){var row=help.createRow(index);row.setHeightInPoints(44);cell(row,0,note,text);help.addMergedRegion(new CellRangeAddress(index,index,0,4));index++;}
            index++;var heading=help.createRow(index++);String[] labels={"原始列名（请勿修改）","含义","必填","填写规则","示例值"};for(int c=0;c<labels.length;c++)cell(heading,c,labels[c],header);
            for(Field field:guide.fields()){var row=help.createRow(index++);row.setHeightInPoints(54);String[] values={field.key(),field.label(),field.required()?"是":"否",field.description(),field.example()};for(int c=0;c<values.length;c++)cell(row,c,values[c],text);}
            help.createFreezePane(0,guide.notes().size()+3);book.write(output);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("裕和堂-"+guide.title()+"-填写示例.xlsx",java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(output.toByteArray());
        }
    }
    private static void cell(Row row,int index,String value,CellStyle style){var cell=row.createCell(index,CellType.STRING);cell.setCellValue(value);cell.setCellStyle(style);}
    private static Field f(String key,String label,boolean required,String example,String description){return new Field(key,label,required,example,description);}

    static Guide describe(String kind){
        String title;List<Field> fields;List<String> notes=new ArrayList<>(List.of(
            "所有示例均为虚构资料。请替换示例姓名、手机号、编号和地址；示例文件不会自动导入。正式导入前先预检。",
            "仅导入第一个“资料”工作表；首行原始列名和列顺序不要修改，第二个“填写说明”工作表仅供参考。仅支持 .xlsx、5MB 内、每次最多 5000 条；请粘贴纯值，不使用公式。",
            "手机号、会员号、门店与项目编号按文本填写，保留前导零。storeId 必须使用系统门店 ID，不是门店名称；下方示例编号需要替换为实际编号。"));
        switch(kind){
            case "members"->{title="会员基础资料";fields=List.of(
                f("name","客户姓名 / 称呼",true,"示例客户甲","1～60 个字符。已有同手机号档案时会更新称呼。"),
                f("phone","手机号",true,"13800000000","完整 11 位大陆手机号，文件内不可重复；系统按手机号匹配档案。"),
                f("storeId","关联门店 ID",true,"example-store","填写系统内已存在的门店 ID，可从本页门店编号对照中复制。"),
                f("memberVerified","会员标识",false,"true","支持 true/false、1/0、是/否。留空按非会员处理；会覆盖已有会员标识，务必核实。"),
                f("memberGrade","会员等级",false,"普通会员","最多 60 个字符；非会员可留空。"),
                f("externalId","外部会员 / 客户编号",false,"00012345","最多 80 个字符，保留前导零；用于记录原系统编号，不是微信 OpenID。"));
                notes.add("同手机号会更新姓名、会员标识、会员等级和外部编号，请先核实原有档案；本模板仅导入基础资料，不迁入储值余额、储值卡或历史消费。账户和消费另行维护。 ");}
            case "therapists"->{title="调理师资料";fields=List.of(
                f("id","调理师资料 ID",true,"example-therapist","1～64 位字母、数字、横线或下划线；相同 ID 会更新资料，不可通过导入直接转店。"),
                f("name","姓名 / 展示名",true,"示例调理师","1～60 个字符。"),
                f("storeId","所属门店 ID",true,"example-store","填写已存在门店的 ID。"),
                f("grade","等级",false,"高级调理师","最多 80 个字符。"),
                f("specialty","擅长介绍",false,"肩颈养护、日常放松","最多 500 个字符。"),
                f("serviceIds","可接待品项 ID",true,"example-service-a,example-service-b","填写当前品牌已发布的项目 ID；多个 ID 用英文逗号分隔，不填项目名称。请先导入品项并发布。"),
                f("enabled","启用",false,"true","支持 true/false、1/0、是/否；留空为 true。"),
                f("showOnClient","客户侧展示",false,"true","支持 true/false、1/0、是/否；留空为 true。"),
                f("resting","休息中",false,"false","支持 true/false、1/0、是/否；留空为 false。"));
                notes.add("调理师以 ID 匹配更新。跨店调动请使用调理师管理；展示、启用和可接待项目应与实际排班一致。");}
            case "stores"->{title="门店资料";fields=List.of(
                f("id","门店 ID",true,"example-store","1～64 位字母、数字、横线或下划线；相同 ID 会更新门店，不是新增副本。"),
                f("name","门店名称",true,"示例门店（请替换）","1～60 个字符。"),
                f("district","区域",false,"上海市静安区","最多 300 个字符。"),
                f("address","详细地址",false,"示例路 100 号 2 楼（请替换）","最多 300 个字符，请填写真实到店地址。"),
                f("phone","门店联系电话",false,"021-00000000","可含数字、+、空格和横线，6～25 个字符。"),
                f("hours","展示营业时间",false,"10:00–22:00","最多 300 个字符，仅为展示文案；实际可预约时段另在门店预约规则维护。"),
                f("image","封面图片地址",false,"/uploads/replace-with-your-image.jpg","使用实际上传后的图片路径或 HTTPS 图片地址，最多 300 个字符。"));
                notes.add("已有门店的同名列会更新，请勿用示例覆盖正式门店。新门店默认关闭在线预约；导入后补全本店项目、规则和人员，再决定开放。");}
            case "services"->{title="养护品项资料";fields=List.of(
                f("id","品牌品项 ID",true,"example-service-a","1～64 位字母、数字、横线或下划线；相同 ID 会更新草稿品项。"),
                f("name","项目名称",true,"示例肩颈养护","1～60 个字符。"),
                f("subtitle","副标题",false,"舒缓日常紧绷","展示短句，可留空。"),
                f("category","分类",false,"日常养护","填写便于展示和筛选的项目分类。"),
                f("duration","展示时长",false,"60分钟","客户侧展示的时长文案。"),
                f("image","图片地址",false,"/uploads/replace-with-your-image.jpg","填写实际上传后的图片路径或 HTTPS 图片地址。"),
                f("description","项目介绍",false,"示例介绍，请替换为审核后的正式项目说明。","最多 3000 个字符。"),
                f("enabled","上架状态",false,"false","支持 true/false、1/0、是/否；留空为 true。未确认的项目建议填 false。"));
                notes.add("本模板保存总部品牌品项草稿，需在“品牌与内容”核对并发布。它不会批量改写各店独立品项与价格；各店数据需分别维护。");}
            case "dianping-customers"->{title="大众点评客户";fields=List.of(
                f("externalShopId","点评门店编号",true,"example-dp-shop","填写在大众点评接入配置中已映射到本店的外部门店编号，不是本系统 storeId。"),
                f("externalCustomerId","点评客户编号",false,"example-dp-customer","可留空；有原始编号时按文本保留。"),
                f("name","客户称呼",true,"示例点评客户","填写已获授权使用的客户姓名或称呼。"),
                f("phone","手机号",true,"13800000000","完整 11 位大陆手机号，用于匹配本店客户档案。"));
                notes.add("先由具有扩展配置权限的总部账号完成点评门店映射。已有会员资料和本店私有跟进会保留；导入不会自动绑定客户微信身份。");}
            case "dianping-orders"->{title="大众点评订单与预约记录";fields=List.of(
                f("externalShopId","点评门店编号",true,"example-dp-shop","填写已映射的点评门店编号。"),
                f("externalOrderId","点评订单编号",true,"example-order-001","使用原订单编号；门店编号＋订单编号＋明细编号共同确定订单明细。"),
                f("externalLineId","订单明细编号",false,"main","每行一条订单明细；留空按 main 处理。同一订单的不同明细使用不同编号。"),
                f("externalProductId","点评商品编号",true,"example-dp-product","先映射到这家门店的实际养护项目，再导入。"),
                f("name","客户称呼",true,"示例点评客户","填写客户姓名或称呼。"),
                f("phone","手机号",false,"13800000000","可留空，或填完整 11 位手机号、138****1234 格式的脱敏号码；仅完整手机号可用于匹配客户档案。"),
                f("status","订单状态",true,"已付款","支持待付款、已付款、已预约、已核销、已取消、已退款；以原平台实际状态为准。"),
                f("amountYuan","金额（元）",true,"128.50","人民币元，最多两位小数，不填 ¥、逗号或单位。"),
                f("headcount","同行人数",false,"1","1～100 的整数，留空为 1；转预约时仍需满足本店人数限制。"),
                f("bookingDate","预约日期",false,"2026-12-20","格式 YYYY-MM-DD；无预约时间时与 startTime 同时留空。"),
                f("startTime","开始时间",false,"10:00","格式 HH:mm；有日期时必须同时填写。"),
                f("createdAt","原订单创建时间",true,"2026-11-01 10:00:00","北京时间，格式 YYYY-MM-DD HH:mm:ss。"),
                f("updatedAt","原订单更新时间",true,"2026-11-02 10:00:00","北京时间，不得早于 createdAt；更新时使用原订单的真实更新时间。"));
                notes.add("先完成点评门店与商品映射。导入订单不直接占用预约名额；在“预约管理 → 大众点评订单”核对并转预约后，才进入接待链路。");}
            default->throw error(404,"导入类型不存在。");
        }
        List<String> expected=kind.startsWith("dianping-")?DianpingService.headers(DianpingService.kind(kind.substring(9))):FilesApi.HEADERS.get(kind);
        if(!fields.stream().map(Field::key).toList().equals(expected))throw new IllegalStateException("Import guide headers do not match importer: "+kind);
        Map<String,String> first=new LinkedHashMap<>();for(var field:fields)first.put(field.key(),field.example());
        List<Map<String,String>> rows=new ArrayList<>();rows.add(first);
        if(kind.equals("members")){var second=new LinkedHashMap<>(first);second.put("name","示例客户乙");second.put("phone","13900000000");second.put("memberVerified","false");second.put("memberGrade","");second.put("externalId","00012346");rows.add(second);}
        return new Guide(kind,title,fields,rows,notes);
    }
}
