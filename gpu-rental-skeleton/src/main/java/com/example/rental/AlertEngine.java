package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 告警规则引擎：每 30 秒把 alert_rules 里的表达式套到台账现状上评估。
 *
 * 表达式是简化 DSL（与界面上填写的 PromQL 风格一致）：
 *   metric 操作符 阈值 [持续 时长]
 * 支持指标：gpus.temp_c / util_pct / power_w / mem_gb、nodes.status、
 * tenants.balance_yuan、free（空闲卡数）、status（指定状态的实例数）。
 * DCGM 类指标（XID、显存水位）未采集时诚实跳过，不硬造数据。
 *
 * 命中后：同一规则同一对象已有活跃告警则去重；命中静默期则跳过；
 * 生成告警并按规则的 notify 渠道投递（webhook 真发 HTTP，其余渠道演示环境记日志）。
 * 「持续 N 分钟」用进程内首次命中时间近似（真实环境应由 Prometheus for 子句负责）。
 */
@Component
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);
    private static final Pattern EXPR = Pattern.compile(
            "^(.+?)\\s*(>=|<=|!=|==|=|>|<)\\s*(\\S+?)(?:\\s+持续\\s+(\\d+)\\s*(s|sec|秒|min|分钟|m|h|小时))?\\s*$");

    private final Db db;
    private final AlertNotifier notifier;
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();

    public AlertEngine(Db db, AlertNotifier notifier) {
        this.db = db;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void evaluate() {
        for (Map<String, Object> rule : db.alertRules()) {
            if (!Boolean.TRUE.equals(rule.get("enabled"))) {
                continue;
            }
            try {
                evaluateRule(rule);
            } catch (Exception e) {
                log.warn("规则 {} 评估失败：{}", rule.get("name"), e.getMessage());
            }
        }
    }

    private record Cond(String metric, String op, String threshold, long durationSec) {
    }

    private void evaluateRule(Map<String, Object> rule) {
        Cond c = parse(String.valueOf(rule.get("expr")));
        if (c == null) {
            log.warn("规则「{}」表达式无法解析：{}", rule.get("name"), rule.get("expr"));
            return;
        }
        String target = String.valueOf(rule.getOrDefault("target", ""));
        switch (target) {
            case "gpu" -> {
                for (Map<String, Object> g : db.q("SELECT * FROM gpus")) {
                    checkViolation(rule, c, g, "gpu");
                }
            }
            case "node" -> {
                for (Map<String, Object> n : db.q("SELECT * FROM nodes")) {
                    checkViolation(rule, c, n, "node");
                }
            }
            case "tenant" -> {
                for (Map<String, Object> t : db.q("SELECT * FROM tenants")) {
                    checkViolation(rule, c, t, "tenant");
                }
            }
            case "platform" -> checkViolation(rule, c, null, "platform");
            default -> log.warn("规则「{}」对象类型未知：{}", rule.get("name"), target);
        }
    }

    private void checkViolation(Map<String, Object> rule, Cond c, Map<String, Object> row, String source) {
        String targetId = row == null ? "platform" : String.valueOf(row.get("id"));
        String key = rule.get("id") + "|" + targetId;
        if (!violating(c, row)) {
            firstSeen.remove(key);
            return;
        }
        long now = System.currentTimeMillis();
        long first = firstSeen.computeIfAbsent(key, k -> now);
        if (c.durationSec() > 0 && now - first < c.durationSec() * 1000L) {
            return;
        }
        fire(rule, c, row, source, targetId);
    }

    private void fire(Map<String, Object> rule, Cond c, Map<String, Object> row, String source, String targetId) {
        String title = String.valueOf(rule.get("name"));
        if (db.activeAlert(title, targetId) != null) {
            return;
        }
        if (db.silenced(targetId, title)) {
            log.info("告警「{}」命中静默期，跳过：{}", title, targetId);
            return;
        }
        String detail = detailOf(c, row);
        String id = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.insertAlert(id, String.valueOf(rule.get("level")), title, source, targetId, detail);
        log.info("告警触发：{} {} → {}（{}）", rule.get("level"), title, targetId, c.metric());
        deliver(id, rule, title, targetId, detail);
    }

    private String detailOf(Cond c, Map<String, Object> row) {
        if (row == null) {
            return "命中规则：" + c.metric() + " " + c.op() + " " + c.threshold();
        }
        Object cur = rawValue(c, row);
        return "命中规则：" + c.metric() + " " + c.op() + " " + c.threshold()
                + "，当前 " + c.metric() + "=" + cur;
    }

    private boolean violating(Cond c, Map<String, Object> row) {
        Object raw = row == null ? null : rawValue(c, row);
        if (raw instanceof String s) {
            return stringOp(s, c);
        }
        Double v = metricValue(c, row);
        if (v == null) {
            return false;
        }
        // status = releasing：阈值是状态名而非数字，语义是「处于该状态的实例数 > 0」
        if ("status".equals(c.metric())) {
            return switch (c.op()) {
                case "=", "==", ">" -> v > 0;
                case "!=" -> v == 0;
                default -> false;
            };
        }
        double th;
        try {
            th = Double.parseDouble(c.threshold().replace("%", ""));
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (c.op()) {
            case ">" -> v > th;
            case ">=" -> v >= th;
            case "<" -> v < th;
            case "<=" -> v <= th;
            case "==", "=" -> v == th;
            case "!=" -> v != th;
            default -> false;
        };
    }

    private boolean stringOp(String value, Cond c) {
        return switch (c.op()) {
            case "==", "=" -> value.equals(c.threshold());
            case "!=" -> !value.equals(c.threshold());
            default -> false;
        };
    }

    /** 规则书写的 node_status 对应台账的 nodes.status 列 */
    private Object rawValue(Cond c, Map<String, Object> row) {
        Object v = row.get(c.metric());
        if (v == null && "node_status".equals(c.metric())) {
            v = row.get("status");
        }
        return v;
    }

    private Double metricValue(Cond c, Map<String, Object> row) {
        if (row != null) {
            Object v = rawValue(c, row);
            if (v instanceof Number n) {
                return n.doubleValue();
            }
        }
        return switch (c.metric()) {
            case "free" -> (double) db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='free'")
                    .stream().mapToInt(r -> ((Number) r.get("c")).intValue()).sum();
            case "status" -> (double) db.q("SELECT COUNT(*) AS c FROM instances WHERE status=?",
                    c.threshold()).stream().mapToInt(r -> ((Number) r.get("c")).intValue()).sum();
            // 未采集的 DCGM 指标：诚实跳过
            case "DCGM_FI_DEV_XID_ERRORS", "mem_used", "mem_total" -> null;
            default -> null;
        };
    }

    /** 通知投递：webhook 渠道发真实 HTTP（3 秒超时），其余渠道演示环境记日志；全部留审计 */
    private void deliver(String alertId, Map<String, Object> rule, String title, String targetId, String detail) {
        String notify = String.valueOf(rule.get("notify"));
        String channel = notify.toLowerCase();
        boolean webhook = channel.contains("webhook");
        if (webhook) {
            String url = db.setting("alert.webhook_url");
            if (url == null || url.isBlank()) {
                log.warn("告警 {} 配置了 webhook 通知，但未设置参数 alert.webhook_url，跳过投递", alertId);
                webhook = false;
            } else {
                try {
                    String body = "{\"id\":\"" + alertId + "\",\"level\":\"" + esc(rule.get("level"))
                            + "\",\"title\":\"" + esc(title) + "\",\"target\":\"" + esc(targetId)
                            + "\",\"detail\":\"" + esc(detail) + "\"}";
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = HttpClient.newHttpClient()
                            .send(req, HttpResponse.BodyHandlers.ofString());
                    log.info("webhook 投递 {} → HTTP {}", alertId, resp.statusCode());
                } catch (Exception e) {
                    log.warn("webhook 投递 {} 失败：{}", alertId, e.getMessage());
                }
            }
        }
        if (!webhook) {
            log.info("模拟投递（{}）：{} {} → {}", notify, rule.get("level"), title, targetId);
        }
        // 配置了通知渠道就按渠道类型真发（钉钉/飞书/企微/自定义 webhook），一条告警不重复投同一渠道
        int sent = notifier.broadcast("[" + rule.get("level") + "] " + title,
                "对象：" + targetId + "\n" + (detail == null ? "" : detail), String.valueOf(rule.get("level")));
        db.insertAudit("alert-engine", "ALERT_DELIVER", alertId,
                notify + " · " + title + (sent > 0 ? " · 渠道投递 " + sent + " 条" : ""));
    }

    private static String esc(Object o) {
        return String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static Cond parse(String expr) {
        if (expr == null || expr.isBlank()) {
            return null;
        }
        Matcher m = EXPR.matcher(expr.trim());
        if (!m.matches()) {
            return null;
        }
        long seconds = 0;
        if (m.group(4) != null) {
            long n = Long.parseLong(m.group(4));
            seconds = switch (m.group(5)) {
                case "min", "分钟", "m" -> n * 60;
                case "h", "小时" -> n * 3600;
                default -> n;
            };
        }
        return new Cond(m.group(1).trim(), m.group(2), m.group(3), seconds);
    }
}
