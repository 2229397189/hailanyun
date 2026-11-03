package com.example.rental;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthApi {

    private final AuthService auth;
    private final Db db;

    public AuthApi(AuthService auth, Db db) {
        this.auth = auth;
        this.db = db;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String username = body.get("username") == null ? "" : String.valueOf(body.get("username"));
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        boolean remember = Boolean.parseBoolean(String.valueOf(body.getOrDefault("remember", false)));
        return auth.login(username, password, remember, clientIp(req), req.getHeader("User-Agent"));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest req) {
        auth.logout(token(req));
        return Map.of("ok", true, "message", "已退出登录");
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Map<String, Object> s = auth.validate(token(req));
        if (s == null) {
            throw new AuthException(401, "登录已失效，请重新登录");
        }
        Map<String, Object> u = db.userById((String) s.get("user_id"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", s.get("username"));
        out.put("displayName", u == null ? s.get("username") : u.get("display_name"));
        out.put("role", s.get("role"));
        out.put("tenantId", s.get("tenant_id"));
        out.put("expireAt", s.get("expire_at"));
        out.put("lastSeen", s.get("last_seen"));
        out.put("ip", s.get("ip"));
        return out;
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String oldPwd = body.get("oldPassword") == null ? "" : String.valueOf(body.get("oldPassword"));
        String newPwd = body.get("newPassword") == null ? "" : String.valueOf(body.get("newPassword"));
        auth.changePassword(token(req), oldPwd, newPwd);
        return Map.of("ok", true, "message", "密码已修改，其它设备上的登录已失效");
    }

    static String token(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null) {
            return null;
        }
        return h.startsWith("Bearer ") ? h.substring(7).trim() : h.trim();
    }

    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
