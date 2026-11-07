package com.yuwatang.reservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static com.yuwatang.reservation.Data.*;

/** WeChat message-push signature and safe-mode envelope. No external XML entities. */
@Component
class WechatMessageCodec {
    final Data d; final String token,aesKey,appId; final boolean allowPlaintext;
    WechatMessageCodec(Data d,@Value("${app.wechat.message-token:}") String token,
        @Value("${app.wechat.message-aes-key:}") String key,@Value("${app.wechat.app-id:}") String appId,
        @Value("${app.wechat.message-allow-plaintext:false}") boolean plain){this.d=d;this.token=token;this.aesKey=key;this.appId=appId;allowPlaintext=plain;}
    void verify(String signature,String timestamp,String nonce,String encrypted){
        if(token.isBlank())throw error(503,"微信客服消息推送尚未配置。");
        try{if(Math.abs(Instant.now().getEpochSecond()-Long.parseLong(timestamp))>300||nonce==null||nonce.length()>256)throw new IllegalArgumentException();
            var parts=new ArrayList<>(List.of(token,timestamp,nonce));if(encrypted!=null)parts.add(encrypted);Collections.sort(parts);
            String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(String.join("",parts).getBytes(StandardCharsets.UTF_8)));
            if(signature==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException();
        }catch(Exception e){throw error(403,"微信消息签名无效或已过期。");}
    }
    Map<String,Object> parse(String body){
        if(body==null||body.getBytes(StandardCharsets.UTF_8).length>65536)throw error(413,"消息内容过大。");
        if(body.stripLeading().startsWith("{"))return d.object(body);
        try{var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());Element root=builder.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            if(!"xml".equals(root.getTagName()))throw new IllegalArgumentException();Map<String,Object> result=new LinkedHashMap<>();var nodes=root.getChildNodes();for(int i=0;i<nodes.getLength();i++)if(nodes.item(i) instanceof Element el)result.put(el.getTagName(),el.getTextContent());return result;
        }catch(Exception e){throw error(400,"微信消息格式不正确。");}
    }
    Map<String,Object> decode(String body,String signature,String msgSignature,String timestamp,String nonce){
        var envelope=parse(body);String encrypted=str(envelope,"Encrypt");
        if(!encrypted.isBlank()){verify(msgSignature,timestamp,nonce,encrypted);return parse(decrypt(encrypted));}
        if(!allowPlaintext)throw error(403,"请在微信平台启用安全模式，或明确配置明文兼容。");
        verify(signature,timestamp,nonce,null);return envelope;
    }
    String decrypt(String encrypted){
        try{if(aesKey.length()!=43||appId.isBlank())throw new IllegalArgumentException();byte[] key=Base64.getDecoder().decode(aesKey+"=");
            var cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));byte[] bytes=cipher.doFinal(Base64.getDecoder().decode(encrypted));
            int pad=bytes[bytes.length-1]&255;if(pad<1||pad>32||bytes.length<20+pad)throw new IllegalArgumentException();for(int i=bytes.length-pad;i<bytes.length;i++)if((bytes[i]&255)!=pad)throw new IllegalArgumentException();
            int length=ByteBuffer.wrap(bytes,16,4).getInt(),end=bytes.length-pad;if(length<0||length>end-20)throw new IllegalArgumentException();
            if(!appId.equals(new String(bytes,20+length,end-20-length,StandardCharsets.UTF_8)))throw new IllegalArgumentException();
            return new String(bytes,20,length,StandardCharsets.UTF_8);
        }catch(Exception e){throw error(403,"微信消息解密失败，请检查小程序 AppID 和 EncodingAESKey。");}
    }
}
