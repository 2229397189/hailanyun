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

    /**
     * OpenAPI 3.0 描述：给客户做对接时直接导入 Postman / Apifox，
     * 也让 /docs 页面的 Code Samples 有唯一事实来源。
     */
    @org.springframework.web.bind.annotation.GetMapping("/openapi.json")
    public java.util.Map<String, Object> openapi() {
        java.util.Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", java.util.Map.of(
                "title", "TokenArk 算力平台 API",
                "version", "1.0.0",
                "description", "实例全生命周期 + OpenAI 兼容推理接口。推理接口用 sk- 开头的 API Key，其余用登录会话 Bearer Token。"));

        java.util.Map<String, Object> paths = new java.util.LinkedHashMap<>();

        paths.put("/v1/models", java.util.Map.of("get", java.util.Map.of(
                "summary", "可用模型列表（已下架模型不返回）",
                "security", java.util.List.of(java.util.Map.of("ApiKey", java.util.List.of())),
                "responses", java.util.Map.of("200", java.util.Map.of("description", "模型列表")))));

        java.util.Map<String, Object> chatReq = new java.util.LinkedHashMap<>();
        chatReq.put("required", true);
        chatReq.put("content", java.util.Map.of("application/json", java.util.Map.of(
                "schema", java.util.Map.of(
                        "type", "object",
                        "required", java.util.List.of("model", "messages"),
                        "properties", java.util.Map.of(
                                "model", java.util.Map.of("type", "string", "example", "deepseek-chat"),
                                "messages", java.util.Map.of("type", "array", "items", java.util.Map.of(
                                        "type", "object",
                                        "properties", java.util.Map.of(
                                                "role", java.util.Map.of("type", "string", "enum", java.util.List.of("system", "user", "assistant")),
                                                "content", java.util.Map.of("type", "string")))),
                                "max_tokens", java.util.Map.of("type", "integer", "default", 512, "maximum", 4096))))));
        paths.put("/v1/chat/completions", java.util.Map.of("post", java.util.Map.of(
                "summary", "OpenAI 兼容对话补全，按 Token 计费（先扣套餐再扣余额）",
                "security", java.util.List.of(java.util.Map.of("ApiKey", java.util.List.of())),
                "requestBody", chatReq,
                "responses", java.util.Map.of(
                        "200", java.util.Map.of("description", "补全结果"),
                        "402", java.util.Map.of("description", "余额/套餐不足"),
                        "403", java.util.Map.of("description", "模型已下架")))));

        paths.put("/api/orders", java.util.Map.of("post", java.util.Map.of(
                "summary", "创建实例订单（预扣余额，成功后在 K8s 建 Pod）",
                "security", java.util.List.of(java.util.Map.of("BearerAuth", java.util.List.of())),
                "requestBody", java.util.Map.of("required", true, "content", java.util.Map.of(
                        "application/json", java.util.Map.of("schema", java.util.Map.of(
                                "type", "object",
                                "required", java.util.List.of("sku", "hours", "orderNo"),
                                "properties", java.util.Map.of(
                                        "sku", java.util.Map.of("type", "string", "example", "1"),
                                        "hours", java.util.Map.of("type", "integer", "example", 1),
                                        "orderNo", java.util.Map.of("type", "string", "description", "幂等键"),
                                        "dataDiskGb", java.util.Map.of("type", "integer", "default", 0),
                                        "image", java.util.Map.of("type", "string")))))),
                "responses", java.util.Map.of("200", java.util.Map.of("description", "instanceId / gpuIds / prepaid")))));

        paths.put("/api/instances", java.util.Map.of("get", java.util.Map.of(
                "summary", "实例列表",
                "security", java.util.List.of(java.util.Map.of("BearerAuth", java.util.List.of())),
                "responses", java.util.Map.of("200", java.util.Map.of("description", "实例数组")))));

        spec.put("paths", paths);
        spec.put("components", java.util.Map.of("securitySchemes", java.util.Map.of(
                "ApiKey", java.util.Map.of("type", "http", "scheme", "bearer",
                        "description", "sk- 开头的 API Key，仅用于 /v1/**"),
                "BearerAuth", java.util.Map.of("type", "http", "scheme", "bearer",
                        "description", "登录接口返回的 token，用于 /api/**"))));
        return spec;
    }
}
