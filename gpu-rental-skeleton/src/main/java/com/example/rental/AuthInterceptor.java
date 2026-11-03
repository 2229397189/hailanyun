package com.example.rental;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * 统一鉴权：所有 /api/** 都要带有效 Bearer token（/api/auth/login 除外）。
 *
 * 除了“有没有登录”，还做了两件企业项目必须有的事：
 * 1. 校验 X-User 与会话用户一致，防止伪造审计归属；
 * 2. 按角色做接口级 RBAC——管理接口只给 admin/ops，系统管理只给 admin。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            return true;
        }

        Map<String, Object> session = auth.validate(AuthApi.token(req));
        if (session == null) {
            return reject(res, 401, "未登录或登录已过期，请重新登录");
        }

        String username = String.valueOf(session.get("username"));
        String claimed = req.getHeader("X-User");
        if (claimed != null && !claimed.isBlank() && !claimed.equals(username)) {
            return reject(res, 403, "身份信息与会话不一致");
        }

        String role = String.valueOf(session.get("role"));
        String path = req.getRequestURI();

        if (path.startsWith("/api/admin")) {
            if (!"admin".equals(role) && !"ops".equals(role)) {
                return reject(res, 403, "当前账号无权访问管理接口");
            }
            boolean adminOnly = path.startsWith("/api/admin/settings")
                    || path.startsWith("/api/admin/users")
                    || path.startsWith("/api/admin/sessions");
            if (adminOnly && !"admin".equals(role)) {
                return reject(res, 403, "仅平台管理员可访问系统管理");
            }
        }

        req.setAttribute("authUsername", username);
        req.setAttribute("authRole", role);
        req.setAttribute("authTenant", session.get("tenant_id"));
        return true;
    }

    private boolean reject(HttpServletResponse res, int status, String message) throws Exception {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"ok\":false,\"message\":\"" + message + "\"}");
        return false;
    }
}
