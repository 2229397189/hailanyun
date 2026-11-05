package com.example.rental;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机 GPU 采集 agent 接口。
 * 上报接口不走会话鉴权（agent 没有用户会话），用 X-Agent-Token / Bearer 校验 settings.agent.token；
 * 查询接口走 /api/admin/** 的既有 RBAC（仅 admin/ops）。
 */
@RestController
@RequestMapping("/api")
public class AgentApi {

    private final Db db;

    public AgentApi(Db db) {
        this.db = db;
    }

    @PostMapping("/agent/report")
    public Map<String, Object> report(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        String token = req.getHeader("X-Agent-Token");
        if (token == null) {
            String h = req.getHeader("Authorization");
            if (h != null && h.startsWith("Bearer ")) {
                token = h.substring(7).trim();
            }
        }
        String expected = db.setting("agent.token");
        if (expected == null || expected.isBlank() || !expected.equals(token)) {
            throw new AuthException(401, "agent 令牌无效");
        }

        String hostname = String.valueOf(body.getOrDefault("hostname", "unknown"));
        String os = String.valueOf(body.getOrDefault("os", ""));
        List<Map<String, Object>> gpus = new ArrayList<>();
        Object g = body.get("gpus");
        if (g instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("idx", m.get("idx"));
                row.put("name", m.get("name"));
                row.put("uuid", m.get("uuid"));
                row.put("util", num(m.get("util")));
                row.put("memTotalMb", num(m.get("memTotalMb")));
                row.put("memUsedMb", num(m.get("memUsedMb")));
                row.put("tempC", num(m.get("tempC")));
                row.put("powerW", num(m.get("powerW")));
                gpus.add(row);
            }
        }
        if (gpus.isEmpty()) {
            throw new RentalService.BizException("gpus 不能为空");
        }

        String id = "ags" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        db.insertAgentSnapshot(id, hostname, os,
                str(body.get("osVersion")), str(body.get("cpuModel")), intOf(body.get("cpuCores")),
                num(body.get("cpuPercent")), num(body.get("memTotalGb")), num(body.get("memUsedGb")),
                num(body.get("diskTotalGb")), num(body.get("diskUsedGb")),
                longOf(body.get("uptimeSeconds")), gpus);
        return Map.of("ok", true, "snapshotId", id, "message",
                "已接收 " + hostname + " 的 " + gpus.size() + " 张卡快照");
    }

    /** 管理端查看：各主机最新快照 + 每张卡实时读数 */
    @GetMapping("/admin/agent/status")
    public Map<String, Object> status() {
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (Map<String, Object> h : db.agentHosts()) {
            String hostname = String.valueOf(h.get("hostname"));
            List<Map<String, Object>> snap = db.agentLatest(hostname);
            if (snap.isEmpty()) {
                continue;
            }
            Map<String, Object> s = snap.get(0);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hostname", hostname);
            out.put("os", s.get("os"));
            out.put("osVersion", s.get("os_version"));
            out.put("cpuModel", s.get("cpu_model"));
            out.put("cpuCores", s.get("cpu_cores"));
            out.put("lastSeen", s.get("collected_at"));
            out.put("cpuPercent", s.get("cpu_percent"));
            out.put("memTotalGb", s.get("mem_total_gb"));
            out.put("memUsedGb", s.get("mem_used_gb"));
            out.put("diskTotalGb", s.get("disk_total_gb"));
            out.put("diskUsedGb", s.get("disk_used_gb"));
            out.put("uptimeSeconds", s.get("uptime_seconds"));
            out.put("snapshots", h.get("snapshots"));
            out.put("gpus", db.agentGpus(String.valueOf(s.get("id"))));
            hosts.add(out);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hosts", hosts);
        resp.put("dataSource", "host-agent");
        resp.put("note", "本机 Windows 主机 nvidia-smi agent 实采（非集群 DCGM）");
        return resp;
    }

    private static Double num(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(Object v) {
        Double d = num(v);
        return d == null ? null : d.intValue();
    }

    private static Long longOf(Object v) {
        Double d = num(v);
        return d == null ? null : d.longValue();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
