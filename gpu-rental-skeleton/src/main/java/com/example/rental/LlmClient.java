package com.example.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 大模型调用（OpenAI 兼容 /v1/chat/completions）。
 * 未配置参数或调用失败时不硬造结果，返回 ok=false 让调用方回退到内置规则建议。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Db db;

    public LlmClient(Db db) {
        this.db = db;
    }

    public record Result(boolean ok, String content, String message) {
    }

    public Result chat(String system, String user) {
        String base = db.setting("llm.base_url");
        if (base == null || base.isBlank()) {
            return new Result(false, null, "未配置大模型参数（系统参数 llm.base_url / llm.api_key），使用内置规则建议");
        }
        String key = db.setting("llm.api_key");
        String model = db.setting("llm.model");
        String url = base.endsWith("/chat/completions")
                ? base : base.replaceAll("/+$", "") + "/chat/completions";
        try {
            String body = MAPPER.createObjectNode()
                    .put("model", model == null || model.isBlank() ? "gpt-4o-mini" : model)
                    .set("messages", MAPPER.createArrayNode()
                            .add(MAPPER.createObjectNode().put("role", "system").put("content", system))
                            .add(MAPPER.createObjectNode().put("role", "user").put("content", user)))
                    .toString();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (key != null && !key.isBlank()) {
                b.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> resp = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return new Result(false, null, "大模型接口返回 HTTP " + resp.statusCode() + "，使用内置规则建议");
            }
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                return new Result(false, null, "大模型返回为空，使用内置规则建议");
            }
            return new Result(true, content.asText().trim(), "已生成 AI 诊断");
        } catch (Exception e) {
            log.warn("大模型调用失败：{}", e.getMessage());
            return new Result(false, null, "大模型调用失败（" + e.getMessage() + "），使用内置规则建议");
        }
    }
}
