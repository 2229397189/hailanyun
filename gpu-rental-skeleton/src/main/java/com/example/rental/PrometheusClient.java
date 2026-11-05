package com.example.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Prometheus HTTP API 客户端（PromQL 即时/范围查询）。
 *
 * 只在配置了 rental.prometheus.url 时注册；查不到指标就返回空，绝不合成数值——
 * 监控页面显示“未接入 DCGM”比显示一条好看的假曲线诚实得多。
 */
@Component
@ConditionalOnProperty(name = "rental.prometheus.url")
public class PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public PrometheusClient(@Value("${rental.prometheus.url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        log.info("Prometheus 客户端已配置：{}", this.baseUrl);
    }

    /** 服务是否可达（真实请求，不是 ping 状态猜的） */
    public boolean healthy() {
        try {
            JsonNode root = get("/api/v1/query", "query", "up");
            return "success".equals(root.path("status").asText());
        } catch (Exception e) {
            log.warn("Prometheus 不可达：{}", e.getMessage());
            return false;
        }
    }

    /** 即时查询：返回第一个样本的值，没有样本返回 null */
    public String instantValue(String query) {
        try {
            JsonNode result = get("/api/v1/query", "query", query).path("data").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return null;
            }
            JsonNode value = result.get(0).path("value");
            return value.isArray() && value.size() >= 2 ? value.get(1).asText() : null;
        } catch (Exception e) {
            log.warn("PromQL 即时查询失败 {}：{}", query, e.getMessage());
            return null;
        }
    }

    /**
     * 范围查询：返回按分钟步长的 [epochMs, value][]，没有样本返回空列表。
     * 单位换算用 multiplier（如功率 mW → W 传 0.001）。
     */
    public List<double[]> range(String query, int minutes, double multiplier) {
        try {
            JsonNode result = get("/api/v1/query_range",
                    "query", query,
                    "start", String.valueOf(System.currentTimeMillis() / 1000L - minutes * 60L),
                    "end", String.valueOf(System.currentTimeMillis() / 1000L),
                    "step", "60").path("data").path("result");
            List<double[]> out = new ArrayList<>();
            if (!result.isArray()) {
                return out;
            }
            JsonNode values = result.get(0).path("values");
            if (!values.isArray()) {
                return out;
            }
            for (JsonNode v : values) {
                if (v.isArray() && v.size() >= 2) {
                    double ts = v.get(0).asDouble() * 1000;
                    String valText = v.get(1).asText();
                    if (valText == null || valText.equals("NaN")) {
                        continue;
                    }
                    out.add(new double[]{ts, Double.parseDouble(valText) * multiplier});
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("PromQL 范围查询失败 {}：{}", query, e.getMessage());
            return List.of();
        }
    }

    private JsonNode get(String path, String... params) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl).append(path).append('?');
        for (int i = 0; i < params.length; i += 2) {
            if (i > 0) {
                url.append('&');
            }
            url.append(URLEncoder.encode(params[i], StandardCharsets.UTF_8))
               .append('=')
               .append(URLEncoder.encode(params[i + 1], StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body());
    }
}
