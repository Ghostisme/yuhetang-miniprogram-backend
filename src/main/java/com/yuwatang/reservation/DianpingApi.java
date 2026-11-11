package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

@RestController @RequestMapping("/api/admin/dianping")
class DianpingApi {
    final AuthService auth;final DianpingService service;final DianpingPort port;final FilesApi files;
    DianpingApi(AuthService auth,DianpingService service,DianpingPort port,FilesApi files){this.auth=auth;this.service=service;this.port=port;this.files=files;}
    @GetMapping("/config") @Permit("integrations:read")Map<String,Object> config(HttpServletRequest r){return service.config(auth.admin(r));}
    @PutMapping("/shops") @Permit("integrations:write")Map<String,Object> shop(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.saveShop(auth.admin(r),in);}
    @PutMapping("/products") @Permit("integrations:write")Map<String,Object> product(HttpServletRequest r,@RequestBody Map<String,Object> in){return service.saveProduct(auth.admin(r),in);}
    @GetMapping("/imports/{kind}/template") @Permit("imports:write")ResponseEntity<byte[]> template(HttpServletRequest r,@PathVariable String kind)throws IOException{
        AdminService.hq(auth.admin(r));return files.workbook("裕和堂-大众点评-"+kind+"-模板.xlsx",DianpingService.headers(DianpingService.kind(kind)),List.of());
    }
    @PostMapping("/imports/{kind}") @Permit("imports:write")Map<String,Object> upload(HttpServletRequest r,@PathVariable String kind,@RequestParam(defaultValue="false")boolean commit,@RequestPart MultipartFile file)throws IOException{
        var actor=auth.admin(r);AdminService.hq(actor);var type=DianpingService.kind(kind);List<Map<String,Object>> rows;
        try{rows=files.parse(file,DianpingService.headers(type));}
        catch(org.springframework.web.server.ResponseStatusException e){throw e;}
        catch(IOException|RuntimeException e){throw error(400,"无法读取xlsx文件，请重新下载模板并粘贴纯值。");}
        return port.ingest(actor,type,rows,commit,new DianpingPort.Source(Objects.toString(file.getOriginalFilename(),"dianping.xlsx"),DianpingService.fileHash(file.getBytes())));
    }
    @GetMapping("/orders") @Permit("appointments:read")Map<String,Object> orders(HttpServletRequest r,@RequestParam Map<String,String> filters){return service.orders(auth.admin(r),filters);}
    @GetMapping("/orders/{id}/slots") @Permit("appointments:write")Map<String,Object> slots(HttpServletRequest r,@PathVariable String id,@RequestParam String date){return service.slots(auth.admin(r),id,date);}
    @PostMapping("/orders/{id}/appointment") @Permit("appointments:write")Map<String,Object> convert(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return service.convert(auth.admin(r),id,in);}
    @PostMapping("/orders/{id}/reviewed") @Permit("appointments:write")Map<String,Object> reviewed(HttpServletRequest r,@PathVariable String id,@RequestBody Map<String,Object> in){return service.reviewed(auth.admin(r),id,in);}
}
