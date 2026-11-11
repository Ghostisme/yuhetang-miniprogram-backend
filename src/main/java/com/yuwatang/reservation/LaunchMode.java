package com.yuwatang.reservation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.yuwatang.reservation.Data.*;

/** One brand-wide switch, independent of draft content and server environment files. */
@Service
class LaunchMode {
    static final String KEY = "miniprogramLaunchMode";
    final Data d;
    LaunchMode(Data d) { this.d = d; }

    static Map<String,Object> defaults() {
        return new LinkedHashMap<>(Map.of("enabled", false, "revision", 0,
            "title", Map.of("zh", "温馨提示", "en", "A warm reminder", "ja", "ご案内"),
            "message", Map.of(
                "zh", "裕和堂小程序正在建设中，功能尚未正式上线。目前仅供浏览，登录、预约、改约、取消、资料提交及客服对话暂不可用，不构成有效预约或业务办理。正式上线后，我们会通过官方渠道告知您。感谢您的理解与耐心等待。",
                "en", "YU Health is under construction and has not officially launched. Browsing is available; sign-in, bookings, changes, cancellations, profile updates and chat are unavailable. No valid booking or business request can be made. We will announce the launch through our official channels. Thank you for your patience.",
                "ja", "YU Healthのミニプログラムは現在準備中で、正式公開前です。閲覧のみ可能です。ログイン、予約・変更・キャンセル、情報の送信、チャットはご利用いただけず、有効な予約や手続きにはなりません。正式公開は公式のご案内でお知らせします。ご理解のほどお願いいたします。"),
            "updatedAt", ""));
    }

    Map<String,Object> status() {
        var rows = d.rows("SELECT setting_value FROM app_setting WHERE setting_key=?", KEY);
        return rows.isEmpty() ? defaults() : d.object(rows.get(0).get("settingValue"));
    }

    @Transactional
    public Map<String,Object> save(Admin actor, Map<String,Object> input) {
        actor.require("integrations:write");
        AdminService.hq(actor);
        if (!(input.get("enabled") instanceof Boolean)) throw error(400, "请明确选择是否启用建设中提示。");
        var rows = d.rows("SELECT setting_value FROM app_setting WHERE setting_key=?", KEY);
        String previous = rows.isEmpty() ? null : str(rows.get(0), "settingValue");
        var current = previous == null ? defaults() : d.object(previous);
        if (integer(input, "revision", -1) != integer(current, "revision", 0)) throw error(409, "提示配置已被其他人修改，请重新加载后保存。");
        Map<String,Object> next = new LinkedHashMap<>();
        next.put("enabled", input.get("enabled"));
        next.put("title", translations(input.get("title"), 40));
        next.put("message", translations(input.get("message"), 360));
        next.put("revision", integer(current, "revision", 0) + 1);
        next.put("updatedAt", now());
        String encoded = d.stringify(next);
        if (encoded.length() > 2000) throw error(400, "三语提示总长度过长，请适当缩短。");
        if (previous == null) {
            try { d.update("INSERT INTO app_setting VALUES(?,?)", KEY, encoded); }
            catch (org.springframework.dao.DuplicateKeyException e) { throw error(409, "提示配置已被其他人修改，请重新加载后保存。"); }
        } else if (d.update("UPDATE app_setting SET setting_value=? WHERE setting_key=? AND setting_value=?", encoded, KEY, previous) != 1) {
            throw error(409, "提示配置已被其他人修改，请重新加载后保存。");
        }
        d.audit(actor, null, "miniprogram.launch-mode", "brand", bool(next, "enabled") ? "开启建设中模式：顾客仅可浏览，暂停顾客提交" : "关闭建设中模式：恢复顾客提交");
        return next;
    }

    private Map<String,String> translations(Object value, int max) {
        if (!(value instanceof Map<?,?>)) throw error(400, "请填写中文、英文、日文提示。");
        var source = d.object(value);
        Map<String,String> result = new LinkedHashMap<>();
        for (String language : List.of("zh", "en", "ja")) {
            if (!(source.get(language) instanceof String)) throw error(400, "请填写完整的三语提示。");
            String text = text(source, language, 1, max);
            if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw error(400, "提示包含无效字符。");
            result.put(language, text);
        }
        return result;
    }

    static boolean customerWrite(String method, String path) {
        if (Set.of("GET", "HEAD", "OPTIONS").contains(method)) return false;
        return path.equals("/api/appointments") || path.startsWith("/api/appointments/")
            || path.equals("/api/me") || path.startsWith("/api/me/") || path.startsWith("/api/auth/");
    }

    void check(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!customerWrite(request.getMethod(), path)) return;
        var value = status();
        if (bool(value, "enabled")) {
            String language = Objects.toString(request.getHeader("Accept-Language"), "zh").toLowerCase(Locale.ROOT);
            String locale = language.startsWith("en") ? "en" : language.startsWith("ja") ? "ja" : "zh";
            throw new ConstructionModeException(value, str(d.object(value.get("message")), locale));
        }
    }
}

class ConstructionModeException extends RuntimeException {
    final Map<String,Object> status;
    ConstructionModeException(Map<String,Object> status, String message) { super(message); this.status = status; }
}

@RestController
class LaunchModeApi {
    final LaunchMode mode; final AuthService auth;
    LaunchModeApi(LaunchMode mode, AuthService auth) { this.mode = mode; this.auth = auth; }
    @GetMapping("/api/launch-status") Map<String,Object> status() { return mode.status(); }
    @GetMapping("/api/admin/launch-mode") @Permit("integrations:read")
    Map<String,Object> admin(HttpServletRequest request) { AdminService.hq(auth.admin(request)); return mode.status(); }
    @PutMapping("/api/admin/launch-mode") @Permit("integrations:write")
    Map<String,Object> save(HttpServletRequest request, @RequestBody Map<String,Object> input) { return mode.save(auth.admin(request), input); }
}
