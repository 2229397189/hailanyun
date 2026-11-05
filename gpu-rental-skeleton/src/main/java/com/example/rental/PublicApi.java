package com.example.rental;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未登录可访问的公开信息（WebConfig 放行 /api/public/**）。
 *
 * 白标（white-label）：平台名、Logo 文案、主色、版权都读 settings 表，
 * 交付给不同客户只改参数，不动代码。
 */
@RestController
@RequestMapping("/api/public")
public class PublicApi {

    private final Db db;
    private final BizApi biz;

    public PublicApi(Db db, BizApi biz) {
        this.db = db;
        this.biz = biz;
    }

    /**
     * 官网未登录也能提交商务需求（裸金属申请、演示机型开通、大客户咨询）。
     * 走 /api/public/** 是因为它在 AuthInterceptor 的放行名单里；落库后进管理端「商务需求」列表。
     */
    @PostMapping("/leads")
    public Map<String, Object> createLead(@RequestBody Map<String, Object> body) {
        return biz.submitLead(null, body, "guest");
    }

    /**
     * 官网首页的「智能助手」未登录也能问。回答逻辑与登录态完全一致，
     * 只是上下文里不带某个租户的余额与配额。
     */
    @PostMapping("/assistant")
    public Map<String, Object> assistant(@RequestBody Map<String, Object> body) {
        return biz.ask(body, null, "guest");
    }

    /**
     * 注册页拿邀请码反查邀请方与返券规则。只回企业名称，不暴露租户 id、余额等内部信息。
     */
    @GetMapping("/invite")
    public Map<String, Object> inviteInfo(@RequestParam(name = "code", required = false) String code) {
        Map<String, Object> inviter = code == null || code.isBlank() ? null : db.tenantByInviteCode(code.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", inviter != null);
        out.put("company", inviter == null ? "" : String.valueOf(inviter.getOrDefault("name", "")));
        out.put("rule", Map.of(
                "minRecharge", get("invite.min_recharge", "1"),
                "rewardInvitee", get("invite.reward_invitee", "5"),
                "rewardInviter", get("invite.reward_inviter", "3"),
                "couponDays", get("invite.coupon_days", "90")));
        return out;
    }

    @GetMapping("/branding")    public Map<String, Object> branding() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", get("brand.name", "TokenArk 创元方舟"));
        m.put("slogan", get("brand.slogan", "GPU 算力即服务"));
        m.put("logoText", get("brand.logo_text", "TokenArk"));
        m.put("primaryColor", get("brand.primary_color", "#7c3aed"));
        m.put("copyright", get("brand.copyright", "© 2026 TokenArk 创元方舟 · 保留所有权利"));
        m.put("icp", get("brand.icp", ""));
        return m;
    }

    private String get(String key, String fallback) {
        String v = db.setting(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
