package com.example.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MaaS 网关：OpenAI 兼容 /v1/chat/completions 与 /v1/models。
 *
 * - 鉴权：Authorization: Bearer sk-…，库里只存 SHA-256 哈希，按哈希命中且状态 on 才放行；
 * - 计费：按模型目录单价 × token 数，从租户余额扣减并落 maas_usage，余额不足返回 402；
 * - 上游：配置了 llm.base_url 就透传真实模型并取回真实 usage，否则返回本地生成的演示回复
 *   （token 计量与扣费仍然真实，回复内容标注为演示）。
 *
 * 路径 /v1 不挂在 /api/** 下，不走会话鉴权拦截器，仅凭 API Key 认证。
 */
@RestController
@RequestMapping("/v1")
public class MaaSController {

    private static final Logger log = LoggerFactory.getLogger(MaaSController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final Db db;

    public MaaSController(Db db) {
        this.db = db;
    }

    private record UpstreamResult(String content, int promptTokens, int completionTokens, String upstream) {
    }

    // ============================================================ 鉴权

    private Map<String, Object> authKey(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) {
            return null;
        }
        String full = h.substring(7).trim();
        if (full.isEmpty()) {
            return null;
        }
        return db.apiKeyByHash(sha256(full));
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String type, String message) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("message", message);
        e.put("type", type);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", e);
        return ResponseEntity.status(status).body(out);
    }

    // ============================================================ 模型目录

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> models(HttpServletRequest req) {
        if (authKey(req) == null) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_request_error", "无效的 API Key");
        }
        Map<String, Map<String, Object>> states = db.maasModelStates();
        List<Map<String, Object>> data = new ArrayList<>();
        for (MaasCatalog.Model m : MaasCatalog.MODELS) {
            Map<String, Object> st = states.get(m.id());
            // 运营下架的模型不再出现在可用列表里
            if (st != null && "offline".equals(String.valueOf(st.get("status")))) {
                continue;
            }
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", m.id());
            o.put("object", "model");
            o.put("owned_by", "tokenark");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("input", m.inPerM());
            p.put("output", m.outPerM());
            o.put("pricing_yuan_per_1m_tokens", p);
            o.put("group", st == null ? "通用" : st.get("grp"));
            data.add(o);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "list");
        out.put("data", data);
        return ResponseEntity.ok(out);
    }

    // ============================================================ 对话补全

    @PostMapping("/chat/completions")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody(required = false) String raw,
                                                    HttpServletRequest req) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> key = authKey(req);
        if (key == null) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_request_error", "无效的 API Key");
        }
        // Key 级限流与月度额度：先拦，再解析请求体，省得白干
        String keyId = String.valueOf(key.get("id"));
        Object rpmObj = key.get("rpm");
        if (rpmObj instanceof Number && ((Number) rpmObj).intValue() > 0) {
            int rpm = ((Number) rpmObj).intValue();
            int used = db.keyCallsLastMinute(keyId);
            if (used >= rpm) {
                logCall(key, "-", 0, 0, BigDecimal.ZERO, "error", 429, t0, null);
                return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded",
                        "超过速率限制：" + rpm + " 次/分钟，请降低频率后重试");
            }
        }
        Object quotaObj = key.get("quota_yuan");
        if (quotaObj instanceof BigDecimal) {
            BigDecimal quota = (BigDecimal) quotaObj;
            BigDecimal used = db.keyMonthCost(keyId);
            if (quota.signum() > 0 && used.compareTo(quota) >= 0) {
                logCall(key, "-", 0, 0, BigDecimal.ZERO, "error", 402, t0, null);
                return error(HttpStatus.PAYMENT_REQUIRED, "key_quota_exceeded",
                        "该 Key 本月额度已用完：已用 ¥" + used + " / 上限 ¥" + quota);
            }
        }
        JsonNode body;
        try {
            body = MAPPER.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request_error", "请求体不是合法 JSON");
        }
        String modelId = body.path("model").asText("");
        MaasCatalog.Model model = MaasCatalog.byId(modelId);
        if (model == null) {
            logCall(key, modelId, 0, 0, BigDecimal.ZERO, "error", 404, t0);
            return error(HttpStatus.NOT_FOUND, "invalid_request_error", "模型不存在：" + modelId + "，可用 /v1/models 查询");
        }
        Map<String, Object> st = db.maasModelStates().get(modelId);
        if (st != null && "offline".equals(String.valueOf(st.get("status")))) {
            logCall(key, modelId, 0, 0, BigDecimal.ZERO, "error", 403, t0);
            return error(HttpStatus.FORBIDDEN, "model_offline", "模型已下架：" + modelId);
        }
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request_error", "messages 不能为空");
        }
        int maxTokens = body.path("max_tokens").asInt(512);
        if (maxTokens < 1 || maxTokens > 4096) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request_error", "max_tokens 需在 1 ~ 4096 之间");
        }

        String tenantId = String.valueOf(key.get("tenant_id"));
        Map<String, Object> tenant = db.tenant(tenantId);
        if (tenant == null) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_request_error", "密钥所属租户不存在");
        }
        BigDecimal balance = (BigDecimal) tenant.get("balance_yuan");
        // 可用额度 = 余额 + 未用完的 Token 套餐（套餐优先抵扣）
        BigDecimal packLeft = db.packRemaining(tenantId, "token");
        BigDecimal available = balance.add(packLeft);
        int promptTokens = estimateTokens(promptText(messages));

        // 预估扣费（按 max_tokens 上限），余额不足直接拒绝，不产生调用
        BigDecimal estimated = cost(model, promptTokens, maxTokens);
        if (available.compareTo(estimated) < 0) {
            logCall(key, modelId, promptTokens, 0, BigDecimal.ZERO, "error", 402, t0);
            return error(HttpStatus.PAYMENT_REQUIRED, "insufficient_quota",
                    "余额不足：预计本次调用需 ¥" + estimated + "（按 max_tokens=" + maxTokens + " 上限估算），当前可用 ¥"
                            + available + "（余额 " + balance + " + 套餐 " + packLeft + "），请先充值或购买套餐");
        }

        UpstreamResult up = generate(modelId, messages, maxTokens, promptTokens);
        BigDecimal actual = cost(model, up.promptTokens(), up.completionTokens());
        if (actual.compareTo(available) > 0) {
            logCall(key, modelId, up.promptTokens(), up.completionTokens(), BigDecimal.ZERO, "error", 402, t0);
            return error(HttpStatus.PAYMENT_REQUIRED, "insufficient_quota",
                    "余额不足：本次调用需 ¥" + actual + "，当前可用 ¥" + available + "（本次调用未计费）");
        }
        // 先扣 Token 套餐，扣完再走余额
        BigDecimal fromPack = db.consumePack(tenantId, "token", actual);
        BigDecimal fromBalance = actual.subtract(fromPack);
        if (fromBalance.signum() > 0) {
            db.deductBalance(tenantId, fromBalance);
        }
        db.insertMaaSUsage(newId("mu"), tenantId, String.valueOf(key.get("id")), modelId,
                up.promptTokens(), up.completionTokens(), actual);
        db.touchApiKey(String.valueOf(key.get("id")));
        logCall(key, modelId, up.promptTokens(), up.completionTokens(), actual, "ok", 200, t0, up.upstream());
        log.info("MaaS 计费：tenant={} model={} prompt={} completion={} cost={}", tenantId, modelId,
                up.promptTokens(), up.completionTokens(), actual);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", up.content());
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", "stop");
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", up.promptTokens());
        usage.put("completion_tokens", up.completionTokens());
        usage.put("total_tokens", up.promptTokens() + up.completionTokens());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
        out.put("object", "chat.completion");
        out.put("created", System.currentTimeMillis() / 1000);
        out.put("model", modelId);
        out.put("choices", List.of(choice));
        out.put("usage", usage);
        return ResponseEntity.ok(out);
    }

    // ============================================================ 回复生成

    /** 配置了上游就透传真实模型（取真实 usage），否则回退本地演示回复 */
    /**
     * 多上游路由：按「模型匹配 + 成本最低」选，失败自动切下一家，全挂才回退演示。
     * 上游来自 llm_upstreams（可运营配置），没有配置时退回 settings.llm.base_url 单上游。
     */
    private UpstreamResult generate(String modelId, JsonNode messages, int maxTokens, int promptTokens) {
        java.util.List<Map<String, Object>> candidates = new java.util.ArrayList<>();
        for (Map<String, Object> u : db.enabledUpstreams()) {
            String models = String.valueOf(u.get("models"));
            boolean match = models.contains("*")
                    || java.util.Arrays.stream(models.split(","))
                        .map(String::trim).anyMatch(m -> m.equals(modelId));
            if (match) {
                candidates.add(u);
            }
        }
        String fallbackBase = db.setting("llm.base_url");
        if (candidates.isEmpty() && fallbackBase != null && !fallbackBase.isBlank()) {
            Map<String, Object> legacy = new java.util.LinkedHashMap<>();
            legacy.put("name", "default");
            legacy.put("base_url", fallbackBase);
            legacy.put("api_key", db.setting("llm.api_key"));
            candidates.add(legacy);
        }
        for (Map<String, Object> up : candidates) {
            try {
                UpstreamResult r = callUpstream(String.valueOf(up.get("name")), String.valueOf(up.get("base_url")),
                        up.get("api_key") == null ? null : String.valueOf(up.get("api_key")),
                        modelId, messages, maxTokens, promptTokens);
                return r;
            } catch (Exception e) {
                log.warn("上游 {} 调用失败（{}），尝试下一家", up.get("name"), e.getMessage());
            }
        }
        return demoReply(messages, maxTokens, promptTokens);
    }

    private UpstreamResult callUpstream(String name, String base, String apiKey, String modelId,
                                        JsonNode messages, int maxTokens, int promptTokens) throws Exception {
        String url = base.endsWith("/chat/completions") ? base : base.replaceAll("/+$", "") + "/chat/completions";
        ObjectNode node = MAPPER.createObjectNode();
        node.put("model", modelId);
        node.set("messages", messages);
        node.put("max_tokens", maxTokens);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(node)));
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("上游返回 HTTP " + resp.statusCode());
        }
        JsonNode root = MAPPER.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        int upPrompt = root.path("usage").path("prompt_tokens").asInt(promptTokens);
        int upCompletion = root.path("usage").path("completion_tokens").asInt(estimateTokens(content));
        log.info("MaaS 上游透传：upstream={} model={} prompt={} completion={}", name, modelId, upPrompt, upCompletion);
        return new UpstreamResult(content, upPrompt, upCompletion, name);
    }

    private UpstreamResult demoReply(JsonNode messages, int maxTokens, int promptTokens) {
        JsonNode last = messages.get(messages.size() - 1);
        String user = last == null ? "" : last.path("content").asText("");
        String reply = "【演示回复】网关未配置上游大模型（系统参数 llm.base_url），本条为本地占位回复。"
                + "已收到消息：" + truncate(user, 240);
        reply = truncate(reply, maxTokens);
        return new UpstreamResult(reply, promptTokens, estimateTokens(reply), null);
    }

    // ============================================================ 工具

    private static String promptText(JsonNode messages) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode m : messages) {
            sb.append(m.path("content").asText()).append('\n');
        }
        return sb.toString();
    }

    /** 粗略估算：汉字 ≈ 1 token，其它字符 ≈ 4 字符 / token */
    private static int estimateTokens(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, cjk + (other + 3) / 4);
    }

    private static BigDecimal cost(MaasCatalog.Model m, int promptTokens, int completionTokens) {
        BigDecimal in = m.inPerM().multiply(BigDecimal.valueOf(promptTokens))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        BigDecimal out = m.outPerM().multiply(BigDecimal.valueOf(completionTokens))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return in.add(out).setScale(4, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int maxChars) {
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    /** 请求日志：成功失败都落库，支持按租户审计与对账 */
    private void logCall(Map<String, Object> key, String modelId, int promptTokens, int completionTokens,
                         BigDecimal cost, String status, int httpStatus, long t0) {
        logCall(key, modelId, promptTokens, completionTokens, cost, status, httpStatus, t0, null);
    }

    private void logCall(Map<String, Object> key, String modelId, int promptTokens, int completionTokens,
                         BigDecimal cost, String status, int httpStatus, long t0, String upstream) {
        try {
            int latency = (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - t0);
            db.insertMaasLog(newId("ml"), String.valueOf(key.get("tenant_id")), String.valueOf(key.get("id")),
                    modelId, promptTokens, completionTokens, cost, status, httpStatus, latency, upstream);
        } catch (Exception e) {
            log.warn("写 MaaS 请求日志失败：{}", e.getMessage());
        }
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
