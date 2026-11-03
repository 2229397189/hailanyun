package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 账号密码认证 + 会话管理。
 *
 * 设计要点（都是企业项目里会被问到的地方）：
 * - 密码只存 BCrypt 哈希，库被拖走也拿不到明文
 * - 连续 5 次失败锁定 10 分钟，防暴力破解
 * - 会话落在 DB（sessions 表），可以服务端强制下线、可以在线会话审计
 * - 登录成功/失败都写审计，出事能查
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int MAX_FAIL = 5;
    private static final long LOCK_MINUTES = 10;
    private static final long SESSION_HOURS = 8;
    private static final long SESSION_HOURS_REMEMBER = 24 * 7;

    /** 密码策略：至少 8 位，含大写、小写、数字、符号 */
    private static final Pattern PWD_UPPER = Pattern.compile("[A-Z]");
    private static final Pattern PWD_LOWER = Pattern.compile("[a-z]");
    private static final Pattern PWD_DIGIT = Pattern.compile("[0-9]");
    private static final Pattern PWD_SYMBOL = Pattern.compile("[^A-Za-z0-9]");

    private final Db db;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(Db db) {
        this.db = db;
    }

    // ------------------------------------------------------------ 登录

    public Map<String, Object> login(String username, String password, boolean remember,
                                     String ip, String userAgent) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new AuthException(400, "请输入账号和密码");
        }
        String name = username.trim();

        Map<String, Object> u = db.userByUsername(name);
        if (u == null) {
            db.insertAudit(name, "LOGIN_FAIL", name, "账号不存在 ip=" + ip);
            // 不区分“账号不存在”和“密码错误”，避免账号枚举
            throw new AuthException(401, "账号或密码错误");
        }

        String userId = (String) u.get("id");
        String status = String.valueOf(u.get("status"));

        if ("disabled".equals(status)) {
            db.insertAudit(name, "LOGIN_FAIL", name, "账号已停用");
            throw new AuthException(403, "账号已停用，请联系平台管理员");
        }

        Timestamp locked = (Timestamp) u.get("locked_until");
        long now = System.currentTimeMillis();
        if (locked != null && locked.getTime() > now) {
            long minutes = (locked.getTime() - now) / 60000 + 1;
            db.insertAudit(name, "LOGIN_FAIL", name, "账号锁定中");
            throw new AuthException(423, "账号已锁定，请 " + minutes + " 分钟后再试");
        }

        String hash = (String) u.get("password_hash");
        if (hash == null || !encoder.matches(password, hash)) {
            int fails = u.get("fail_count") == null ? 1 : ((Number) u.get("fail_count")).intValue() + 1;
            db.onLoginFailure(userId);
            if (fails >= MAX_FAIL) {
                db.lockUser(userId, new Timestamp(now + LOCK_MINUTES * 60_000));
                db.insertAudit(name, "LOGIN_LOCK", name, "连续 " + fails + " 次密码错误，锁定 " + LOCK_MINUTES + " 分钟");
                log.warn("账号 {} 连续失败 {} 次，已锁定", name, fails);
                throw new AuthException(423, "连续 " + MAX_FAIL + " 次密码错误，账号已锁定 " + LOCK_MINUTES + " 分钟");
            }
            db.insertAudit(name, "LOGIN_FAIL", name, "密码错误 第" + fails + "次 ip=" + ip);
            throw new AuthException(401, "账号或密码错误，还可尝试 " + (MAX_FAIL - fails) + " 次");
        }

        // 登录成功
        db.onLoginSuccess(userId);
        db.purgeExpiredSessions();

        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long hours = remember ? SESSION_HOURS_REMEMBER : SESSION_HOURS;
        Timestamp expireAt = new Timestamp(now + hours * 3600_000L);
        String ua = userAgent == null ? "" : userAgent.substring(0, Math.min(userAgent.length(), 190));

        db.insertSession(token, userId, name, String.valueOf(u.get("role")),
                (String) u.get("tenant_id"), ip, ua, expireAt);
        // 同一账号最多保留 5 个活跃会话，避免多设备/测试把在线列表刷爆
        db.trimSessions(userId, 5);
        db.insertAudit(name, "LOGIN_SUCCESS", name, "ip=" + ip + " 有效期 " + hours + " 小时");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("expireAt", expireAt);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", userId);
        user.put("username", name);
        user.put("displayName", u.get("display_name"));
        user.put("role", u.get("role"));
        user.put("tenantId", u.get("tenant_id"));
        out.put("user", user);
        return out;
    }

    // ------------------------------------------------------------ 会话

    /** 校验 token，返回会话信息；无效返回 null */
    public Map<String, Object> validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Map<String, Object> s = db.session(token);
        if (s == null) {
            return null;
        }
        Timestamp expire = (Timestamp) s.get("expire_at");
        if (expire == null || expire.getTime() < System.currentTimeMillis()) {
            db.deleteSession(token);
            return null;
        }
        // 用户被停用后，已签发的会话立即失效
        Map<String, Object> u = db.userById((String) s.get("user_id"));
        if (u == null || !"active".equals(String.valueOf(u.get("status")))) {
            db.deleteSession(token);
            return null;
        }
        db.touchSession(token);
        return s;
    }

    public void logout(String token) {
        Map<String, Object> s = db.session(token);
        if (s != null) {
            db.deleteSession(token);
            db.insertAudit(String.valueOf(s.get("username")), "LOGOUT",
                    String.valueOf(s.get("username")), "主动退出");
        }
    }

    // ------------------------------------------------------------ 改密

    public void changePassword(String token, String oldPwd, String newPwd) {
        Map<String, Object> s = validate(token);
        if (s == null) {
            throw new AuthException(401, "登录已失效，请重新登录");
        }
        if (newPwd == null || newPwd.length() < 8
                || !PWD_UPPER.matcher(newPwd).find()
                || !PWD_LOWER.matcher(newPwd).find()
                || !PWD_DIGIT.matcher(newPwd).find()
                || !PWD_SYMBOL.matcher(newPwd).find()) {
            throw new AuthException(400, "新密码至少 8 位，且需包含大写字母、小写字母、数字和符号");
        }
        if (newPwd.equals(oldPwd)) {
            throw new AuthException(400, "新密码不能与原密码相同");
        }

        Map<String, Object> u = db.userById((String) s.get("user_id"));
        String hash = (String) u.get("password_hash");
        if (hash == null || !encoder.matches(oldPwd, hash)) {
            db.insertAudit(String.valueOf(s.get("username")), "PASSWORD_CHANGE_FAIL",
                    String.valueOf(s.get("username")), "原密码错误");
            throw new AuthException(400, "原密码不正确");
        }

        db.updatePassword((String) s.get("user_id"), encoder.encode(newPwd));
        db.insertAudit(String.valueOf(s.get("username")), "PASSWORD_CHANGE",
                String.valueOf(s.get("username")), "密码已修改");
        // 改密后踢掉其它会话（当前会话保留）
        int kicked = 0;
        for (Map<String, Object> other : db.activeSessions()) {
            if (s.get("user_id").equals(other.get("user_id")) && !token.equals(other.get("id"))) {
                db.deleteSession((String) other.get("id"));
                kicked++;
            }
        }
        log.info("用户 {} 修改密码，踢出 {} 个其它会话", s.get("username"), kicked);
    }
}
