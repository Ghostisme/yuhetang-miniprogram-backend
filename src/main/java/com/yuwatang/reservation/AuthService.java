package com.yuwatang.reservation;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import static com.yuwatang.reservation.Data.*;

@Service
public class AuthService {
    final org.springframework.transaction.support.TransactionTemplate tx; final Data d; final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder(12);
    final boolean local; final String secretConfig,dir; JwtEncoder encoder; JwtDecoder decoder;
    AuthService(Data d,@Value("${app.mode}") String mode,@Value("${app.jwt-secret}") String secret,@Value("${app.data-dir}") String dir,org.springframework.transaction.PlatformTransactionManager tm){this.tx=new org.springframework.transaction.support.TransactionTemplate(tm);this.d=d;this.local="local".equals(mode);this.secretConfig=secret;this.dir=dir;}
    @PostConstruct void init() throws Exception{
        String secret=secretConfig;
        if(secret.length()<32){
            if(!local)throw new IllegalStateException("Production JWT_SECRET must contain at least 32 characters.");
            Path file=Path.of(dir).resolve("local-jwt-secret");Files.createDirectories(file.getParent());
            if(Files.exists(file))secret=Files.readString(file);else{byte[] b=new byte[48];new SecureRandom().nextBytes(b);secret=Base64.getEncoder().encodeToString(b);Files.writeString(file,secret,StandardOpenOption.CREATE_NEW);}
        }
        var key=new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256");
        encoder=new NimbusJwtEncoder(new ImmutableSecret<>(key));decoder=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        ((NimbusJwtDecoder)decoder).setJwtValidator(JwtValidators.createDefaultWithIssuer("yuwatang-reservation"));
    }
    String token(String subject,String kind,int version){var claims=JwtClaimsSet.builder().issuer("yuwatang-reservation").subject(subject).issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofHours(kind.equals("ADMIN")?8:72))).claim("kind",kind).claim("ver",version).build();return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();}
    Jwt jwt(HttpServletRequest request,String kind){try{String h=request.getHeader("Authorization");if(h==null||!h.startsWith("Bearer "))throw error(401,"请先登录。");Object cached=request.getAttribute("verified-jwt");Jwt j=cached instanceof Jwt value?value:decoder.decode(h.substring(7));request.setAttribute("verified-jwt",j);if(!kind.equals(j.getClaimAsString("kind")))throw error(401,"登录身份不正确。");return j;}catch(Exception e){throw error(401,"登录已失效，请重新登录。");}}
    Admin admin(HttpServletRequest request){Object cached=request.getAttribute("admin");if(cached instanceof Admin a)return a;Jwt j=jwt(request,"ADMIN");var user=d.one("SELECT * FROM app_user WHERE id=?",j.getSubject());if(!bool(user,"enabled")||integer(user,"tokenVersion",0)!=((Number)j.getClaim("ver")).intValue())throw error(401,"账号或权限已变更，请重新登录。");var roles=d.rows("SELECT r.* FROM app_role r JOIN user_role ur ON r.id=ur.role_id WHERE ur.user_id=?",j.getSubject());Set<String> ids=new HashSet<>(),perms=new HashSet<>();boolean global=false;for(var r:roles){ids.add(str(r,"id"));global|="GLOBAL".equals(str(r,"dataScope"));}for(var p:d.rows("SELECT DISTINCT rp.permission_code FROM role_permission rp JOIN user_role ur ON ur.role_id=rp.role_id WHERE ur.user_id=?",j.getSubject()))perms.add(str(p,"permissionCode"));String store=str(user,"storeId");Admin a=new Admin(j.getSubject(),str(user,"displayName"),store,ids,perms,global&&store.isEmpty());request.setAttribute("admin",a);return a;}
    String customer(HttpServletRequest request){var jwt=jwt(request,"CUSTOMER");if(d.count("SELECT COUNT(*) FROM customer_identity WHERE id=?",jwt.getSubject())!=1)throw error(401,"请重新登录。");return jwt.getSubject();}
    Map<String,Object> login(Map<String,Object> body){var users=d.rows("SELECT * FROM app_user WHERE username=?",text(body,"username",1,80));if(users.isEmpty()||!passwords.matches(str(body,"password"),str(users.get(0),"passwordHash"))||!bool(users.get(0),"enabled"))throw error(401,"账号或密码不正确。");var u=users.get(0);return Map.of("token",token(str(u,"id"),"ADMIN",integer(u,"tokenVersion",0)));}
    Map<String,Object> customerSession(String openid,boolean dev){return tx.execute(status->createSession(openid,dev));}
    private Map<String,Object> createSession(String openid,boolean dev){new BookingLocks(d).acquire("identity:"+openid);var users=d.rows("SELECT * FROM customer_identity WHERE openid=?",openid);String uid;if(users.isEmpty()){uid=id();d.update("INSERT INTO customer_identity VALUES(?,?,?,?)",uid,openid,dev,now());}else uid=str(users.get(0),"id");CustomerProfiles.recordLogin(d,uid);return Map.of("token",token(uid,"CUSTOMER",0),"development",dev);}
    Map<String,Object> me(Admin a){return Map.of("id",a.id(),"name",a.name(),"storeId",a.storeId(),"hq",a.hq(),"permissions",a.permissions(),"roleIds",a.roleIds());}
}
