package com.example.rental;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：给容器探针和负载均衡用，不需要登录。
 * 返回 200 只代表进程活着；status=UP 才代表数据库可用。
 */
@RestController
public class HealthApi {

    private final JdbcTemplate jdbc;
    private final K8sClient k8s;
    private final long startedAt = System.currentTimeMillis();

    public HealthApi(JdbcTemplate jdbc, K8sClient k8s) {
        this.jdbc = jdbc;
        this.k8s = k8s;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        boolean dbUp;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbUp = true;
        } catch (Exception e) {
            dbUp = false;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", dbUp ? "UP" : "DOWN");
        out.put("db", dbUp ? "UP" : "DOWN");
        out.put("k8sMode", k8s.mode());
        out.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        out.put("version", "1.0.0");
        return out;
    }
}
