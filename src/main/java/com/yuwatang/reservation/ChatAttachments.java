package com.yuwatang.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** Conversation attachments are private files; only scoped chat APIs issue read tickets. */
@Service
class ChatAttachments {
    static final long IMAGE_LIMIT=2*1024*1024, VIDEO_LIMIT=10*1024*1024;
    final Data d;final AuthService auth;final WechatGateway wx;final Path root;final String publicUrl;
    ChatAttachments(Data d,AuthService auth,WechatGateway wx,@Value("${app.data-dir}")String dir,@Value("${app.public-url:}")String publicUrl){
        this.d=d;this.auth=auth;this.wx=wx;this.publicUrl=publicUrl;root=Path.of(dir,"uploads","chat-private").toAbsolutePath().normalize();
    }
    record Upload(String kind,String name,String mime,String extension,byte[] bytes,byte[] thumbnail,String sha){}
    Upload validate(MultipartFile file,MultipartFile thumbnail)throws IOException{
        if(file.isEmpty()||file.getSize()>VIDEO_LIMIT)throw error(400,"请选择图片或MP4视频；图片不超过2MB，视频不超过10MB。");
        byte[] bytes=file.getBytes();String kind,mime,extension;
        if(bytes.length>12&&bytes[4]=='f'&&bytes[5]=='t'&&bytes[6]=='y'&&bytes[7]=='p'){
            String brand=new String(bytes,8,4,StandardCharsets.US_ASCII);
            if(!Set.of("isom","iso2","mp41","mp42","avc1","M4V ","MSNV").contains(brand))throw error(400,"视频请使用MP4格式。");
            kind="video";mime="video/mp4";extension="mp4";
        }else{
            if(bytes.length>3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8){mime="image/jpeg";extension="jpg";}
            else if(bytes.length>8&&bytes[0]==(byte)0x89&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'){mime="image/png";extension="png";}
            else if(bytes.length>6&&new String(bytes,0,6,StandardCharsets.US_ASCII).matches("GIF8[79]a")){mime="image/gif";extension="gif";}
            else throw error(400,"图片支持JPG、PNG、GIF；视频支持MP4。");
            if(bytes.length>IMAGE_LIMIT)throw error(400,"图片不能超过2MB，请压缩后发送。");
            checkImage(bytes);kind="image";
        }
        byte[] poster=null;
        if(kind.equals("video")){
            if(thumbnail!=null&&!thumbnail.isEmpty()){
                if(thumbnail.getSize()>64*1024)throw error(400,"视频封面不能超过64KB。");
                poster=thumbnail.getBytes();
                if(poster.length<3||poster[0]!=(byte)0xff||poster[1]!=(byte)0xd8)throw error(400,"视频封面应为JPG图片。");
                checkImage(poster);
            }else poster=defaultPoster();
        }
        String name=Objects.requireNonNullElse(file.getOriginalFilename(),"附件."+extension).replaceAll("[\\p{Cntrl}\\\\/]","_");
        name=name.substring(0,Math.min(name.length(),100));if(name.isBlank())name="附件."+extension;
        return new Upload(kind,name,mime,extension,bytes,poster,sha(bytes));
    }
    static void checkImage(byte[] bytes)throws IOException{
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw error(400,"图片损坏或格式不受支持。");
            var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||w>12000||h>12000||(long)w*h>40000000)throw error(400,"图片尺寸过大，请缩小后发送。");
            }finally{reader.dispose();}
        }catch(IOException e){throw error(400,"图片损坏或格式不受支持，请重新选择图片。");}
    }
    static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    static byte[] defaultPoster()throws IOException{
        var image=new BufferedImage(480,270,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{g.setColor(new Color(225,233,219));g.fillRect(0,0,480,270);g.setColor(new Color(67,94,75));g.fillOval(195,90,90,90);g.setColor(Color.WHITE);g.fillPolygon(new int[]{231,231,258},new int[]{115,155,135},3);}finally{g.dispose();}
        var out=new ByteArrayOutputStream();ImageIO.write(image,"jpg",out);return out.toByteArray();
    }
    Map<String,Object> store(Upload upload)throws IOException{
        Files.createDirectories(root);String name=id()+"."+upload.extension(),thumb=upload.thumbnail()==null?"":id()+".jpg";
        Map<String,Object> meta=new LinkedHashMap<>(Map.of("file",name,"name",upload.name(),"mime",upload.mime(),"size",upload.bytes().length,"sha",upload.sha(),"kind",upload.kind(),"thumbnail",thumb));
        try{Files.write(path(name),upload.bytes(),StandardOpenOption.CREATE_NEW);if(!thumb.isBlank())Files.write(path(thumb),upload.thumbnail(),StandardOpenOption.CREATE_NEW);return meta;}
        catch(IOException|RuntimeException e){discard(meta);throw e;}
    }
    void discard(Map<String,Object> meta){for(String key:java.util.List.of("file","thumbnail"))try{String name=str(meta,key);if(!name.isBlank())Files.deleteIfExists(path(name));}catch(IOException ignored){}}
    Path path(String name){if(!name.matches("[A-Za-z0-9-]+\\.(jpg|png|gif|mp4)"))throw error(404,"附件不存在。");return root.resolve(name);}
    Map<String,Object> view(Map<String,Object> message){
        var result=new LinkedHashMap<>(message);result.remove("attachmentJson");result.remove("mediaId");result.remove("conversationId");
        String kind=str(message,"kind"),mid=str(message,"id");
        if(!Set.of("image","video").contains(kind))return result;
        Map<String,Object> meta=message.get("attachmentJson")==null?new LinkedHashMap<>():d.object(message.get("attachmentJson"));
        if(meta.isEmpty()&&str(message,"mediaId").isBlank())return result;
        String name=meta.isEmpty()?(kind.equals("image")?"客户图片.jpg":"客户视频.mp4"):str(meta,"name");
        var attachment=new LinkedHashMap<String,Object>();attachment.put("name",name);attachment.put("kind",kind);
        attachment.put("size",meta.getOrDefault("size",0));attachment.put("url",url(mid,"file"));
        if(!str(meta,"thumbnail").isBlank()||kind.equals("video"))attachment.put("thumbnailUrl",url(mid,"thumbnail"));
        result.put("attachment",attachment);return result;
    }
    String url(String mid,String part){
        return url(mid,part,7200);
    }
    String url(String mid,String part,long seconds){
        var claims=JwtClaimsSet.builder().issuer("yuwatang-reservation").subject(mid).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(seconds)).claim("kind","CHAT_ATTACHMENT").claim("part",part).build();
        String ticket=auth.encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        return "/api/chat-media/"+mid+"/"+part+"?ticket="+ticket;
    }
    void verify(String mid,String part,String ticket){
        try{var jwt=auth.decoder.decode(ticket);if(!mid.equals(jwt.getSubject())||!"CHAT_ATTACHMENT".equals(jwt.getClaimAsString("kind"))||!part.equals(jwt.getClaimAsString("part")))throw new IllegalArgumentException();}
        catch(Exception e){throw error(403,"附件链接已过期，请重新打开会话。");}
    }
    Map<String,String> videoCard(String mid){
        String origin;
        try{
            var uri=java.net.URI.create(publicUrl);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(Objects.requireNonNullElse(uri.getPath(),"")))throw new IllegalArgumentException();
            origin=publicUrl.replaceAll("/+$","");
        }catch(Exception e){throw new WechatGateway.CustomerSendNotAttemptedException("视频尚未发送给客户：请将服务器 PUBLIC_URL 配置为可访问的管理后台 HTTPS 域名。");}
        return Map.of("title","客服发来的视频","description","点击播放视频，链接7天有效。","url",origin+url(mid,"watch",7*86400),"thumb_url",origin+url(mid,"thumbnail",7*86400));
    }
    ResponseEntity<String> watch(String mid,String ticket){
        verify(mid,"watch",ticket);
        d.one("SELECT id FROM chat_message WHERE id=? AND kind='video'",mid);
        String video=org.springframework.web.util.HtmlUtils.htmlEscape(url(mid,"file")),poster=org.springframework.web.util.HtmlUtils.htmlEscape(url(mid,"thumbnail"));
        String html="<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>客服视频 · YU Health</title><style>body{margin:0;background:#f8f8f2;color:#435e4b;font:16px system-ui,sans-serif}main{max-width:800px;margin:0 auto;padding:24px 16px}h1{font-size:20px}video{display:block;width:100%;max-height:75vh;background:#25362b;border-radius:10px}p{line-height:1.8;color:#78836f}</style></head><body><main><h1>客服发来的视频</h1><video controls playsinline preload='metadata' poster='"+poster+"' src='"+video+"'></video><p>点击播放按钮观看。返回微信可继续咨询门店。</p></main></body></html>";
        return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
            .header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; media-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'")
            .header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff").body(html);
    }
    ResponseEntity<Resource> read(String mid,String part,String ticket)throws IOException{
        if(!Set.of("file","thumbnail").contains(part))throw error(404,"附件不存在。");
        verify(mid,part,ticket);
        var message=d.one("SELECT * FROM chat_message WHERE id=? AND kind IN ('image','video')",mid);
        Map<String,Object> meta=message.get("attachmentJson")==null?new LinkedHashMap<>():d.object(message.get("attachmentJson"));
        if(meta.isEmpty()){
            boolean video="video".equals(message.get("kind"));byte[] bytes;String ext,mime;
            if(video){
                bytes=wx.customerVideo(str(message,"mediaId"));
                if(bytes.length<12||!new String(bytes,4,4,StandardCharsets.US_ASCII).equals("ftyp"))throw error(410,"微信视频格式暂不支持播放。");
                ext="mp4";mime="video/mp4";
            }else{
                if(!part.equals("file"))throw error(410,"该微信附件已过期或暂不可用。");
                bytes=wx.customerImage(str(message,"mediaId"));checkImage(bytes);
                ext=bytes[0]==(byte)0x89?"png":bytes[0]=='G'?"gif":"jpg";mime="image/"+(ext.equals("jpg")?"jpeg":ext);
            }
            meta=store(new Upload(video?"video":"image",(video?"客户视频.":"客户图片.")+ext,mime,ext,bytes,video?defaultPoster():null,sha(bytes)));
            if(d.update("UPDATE chat_message SET attachment_json=? WHERE id=? AND attachment_json IS NULL",d.stringify(meta),mid)==0){discard(meta);meta=d.object(d.one("SELECT attachment_json FROM chat_message WHERE id=?",mid).get("attachmentJson"));}
        }
        String filename=str(meta,part.equals("thumbnail")?"thumbnail":"file");if(filename.isBlank()||!Files.isRegularFile(path(filename)))throw error(410,"附件文件暂不可用，请联系管理员核对文件备份。");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(part.equals("thumbnail")?"image/jpeg":str(meta,"mime")))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(str(meta,"name"),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noStore()).body(new FileSystemResource(path(filename)));
    }
}
