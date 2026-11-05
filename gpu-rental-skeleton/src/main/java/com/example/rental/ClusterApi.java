package com.example.rental;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/** 管理端接口：集群总览 / 节点 / 工作负载 / 资源池 / 租户 / 告警 / 巡检 / 时序。
 *
 * 数据来源规则（贯穿本类）：
 * - real 模式：能读集群的一律读集群（ClusterOps），台账只作业务口径补充，返回里标 dataSource；
 * - fake 模式：没有 ClusterOps Bean，全部走台账，dataSource=ledger，绝不冒充集群实测。
 */
@RestController
@RequestMapping("/api/admin")
public class ClusterApi {

    private final Db db;
    private final ObjectProvider<ClusterOps> clusterOps;
    private final ObjectProvider<PrometheusClient> prometheus;
    private final LlmClient llm;

    public ClusterApi(Db db, ObjectProvider<ClusterOps> clusterOps,
                      ObjectProvider<PrometheusClient> prometheus, LlmClient llm) {
        this.db = db;
        this.clusterOps = clusterOps;
        this.prometheus = prometheus;
        this.llm = llm;
    }

    /** fake 模式下 Bean 不存在，返回 null，上层回退台账 */
    private ClusterOps ops() {
        try {
            return clusterOps.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    private PrometheusClient prom() {
        try {
            return prometheus.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------ 总览

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        ClusterOps ops = ops();
        Map<String, Object> ci = null;
        if (ops != null) {
            try {
                ci = ops.clusterInfo();
            } catch (Exception e) {
                ci = null;
            }
        }
        boolean cluster = ci != null;

        // ---- 台账口径（计费与可售池的业务真相） ----
        int nodesLedger = intOf(db.q("SELECT COUNT(*) AS c FROM nodes").get(0).get("c"));
        int nodesReadyLedger = intOf(db.q("SELECT COUNT(*) AS c FROM nodes WHERE status IN ('Ready','Cordoned','Draining')")
                .get(0).get("c"));

        Map<String, Integer> st = new LinkedHashMap<>();
        st.put("free", 0);
        st.put("occupied", 0);
        st.put("fault", 0);
        st.put("reserved", 0);
        for (Map<String, Object> r : db.q("SELECT status, COUNT(*) AS c FROM gpus GROUP BY status")) {
            st.put((String) r.get("status"), intOf(r.get("c")));
        }
        int total = st.values().stream().mapToInt(Integer::intValue).sum();

        int tenants = intOf(db.q("SELECT COUNT(*) AS c FROM tenants WHERE status='active'").get(0).get("c"));
        int activeAlerts = intOf(db.q("SELECT COUNT(*) AS c FROM alerts WHERE status='active'").get(0).get("c"));
        int running = intOf(db.q("SELECT COUNT(*) AS c FROM instances WHERE status='running'").get(0).get("c"));
        BigDecimal todayRevenue = (BigDecimal) db.q("""
                SELECT COALESCE(SUM(amount),0) AS s FROM billing_records
                 WHERE CAST(settled_at AS DATE) = ?
                """, java.sql.Date.valueOf(LocalDate.now())).get(0).get("s");
        BigDecimal monthRevenue = (BigDecimal) db.q("""
                SELECT COALESCE(SUM(amount),0) AS s FROM billing_records
                 WHERE CAST(settled_at AS DATE) >= ?
                """, java.sql.Date.valueOf(LocalDate.now().withDayOfMonth(1))).get(0).get("s");

        double util = total == 0 ? 0 : st.get("occupied") * 100.0 / total;

        // 有 DCGM 就用真实利用率，没有就老实标“出租率（分配率）”
        PrometheusClient prom = prom();
        Double realUtil = null;
        if (prom != null) {
            try {
                String v = prom.instantValue("avg(DCGM_FI_DEV_GPU_UTIL)");
                if (v != null && !v.isBlank()) {
                    realUtil = Double.parseDouble(v);
                }
            } catch (Exception ignored) {
                // 查不到就当没接 DCGM
            }
        }

        // ---- 台账基础字段 ----
        out.put("nodeTotal", cluster ? intOf(ci.get("nodeCount")) : nodesLedger);
        out.put("nodeReady", cluster ? countReadyNodes(ops) : nodesReadyLedger);
        out.put("nodeLedger", nodesLedger);
        out.put("gpuTotal", total);
        out.put("gpuStatus", st);
        out.put("gpuUtilization", realUtil != null
                ? Math.round(realUtil * 10) / 10.0 : Math.round(util * 10) / 10.0);
        out.put("gpuUtilizationSource", realUtil != null ? "dcgm" : "rental-rate");
        out.put("gpuRentalRate", Math.round(util * 10) / 10.0);
        out.put("tenantActive", tenants);
        out.put("runningInstances", running);
        out.put("activeAlerts", activeAlerts);
        out.put("todayRevenue", todayRevenue);
        out.put("monthRevenue", monthRevenue);
        out.put("healthScore", Math.max(60, 100 - activeAlerts * 4 - st.get("fault") * 5));
        out.put("dataSource", cluster ? "cluster+ledger" : "ledger");

        // ---- 集群实测（real 模式才有，而且探不到就报 0，不兜底） ----
        if (cluster) {
            out.put("k8sVersion", ci.get("k8sVersion"));
            out.put("masterUrl", ci.get("masterUrl"));
            out.put("podTotal", intOf(ci.get("podCount")));
            out.put("cpuTotal", intOf(ci.get("cpuTotal")));
            out.put("memTotalGb", intOf(ci.get("memGiTotal")));
            out.put("gpuCapacity", intOf(ci.get("gpuTotal")));
            out.put("gpuAllocatable", intOf(ci.get("gpuAllocatable")));
            out.put("vramTotalGb", intOf(ci.get("vramGiTotal")));
            out.put("scheduler", ci.get("schedulerName"));
            out.put("sharedResource", ci.get("sharedResource"));
            out.put("gpuNote", "gpuTotal 为台账可售池；gpuCapacity/gpuAllocatable 为集群实测（无 device plugin 时为 0）");
        } else {
            out.put("k8sVersion", "v1.34.1+k3s1（台账占位，fake 模式无集群）");
        }
        return out;
    }

    private int countReadyNodes(ClusterOps ops) {
        try {
            return (int) ops.clusterNodes().stream()
                    .filter(n -> "Ready".equals(String.valueOf(n.get("status"))))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------ 节点

    @GetMapping("/nodes")
    public List<Map<String, Object>> nodes() {
        ClusterOps ops = ops();
        List<Map<String, Object>> out = new ArrayList<>();

        if (ops == null) {
            for (Map<String, Object> n : db.nodesFull()) {
                ledgerAliases(n);
                n.put("gpus", db.q("SELECT * FROM gpus WHERE node_id=? ORDER BY idx", n.get("id")));
                n.put("dataSource", "ledger");
                out.add(n);
            }
            appendAgentNode(out);
            return out;
        }

        // real：集群实测节点 + 台账卡明细合并；台账里有而集群没有的（比如演示加的节点）照样列出并标注
        List<Map<String, Object>> discovered = List.of();
        try {
            discovered = ops.discoverGpus();
        } catch (Exception e) {
            // 保持空，下面按台账兜底
        }
        Map<String, List<Map<String, Object>>> gpusByNode = new HashMap<>();
        for (Map<String, Object> g : discovered) {
            gpusByNode.computeIfAbsent(String.valueOf(g.get("nodeId")), k -> new ArrayList<>()).add(g);
        }

        List<Map<String, Object>> clusterNodes;
        try {
            clusterNodes = ops.clusterNodes();
        } catch (Exception e) {
            clusterNodes = List.of();
        }
        Set<String> inCluster = new HashSet<>();
        for (Map<String, Object> cn : clusterNodes) {
            String name = String.valueOf(cn.get("name"));
            inCluster.add(name);
            cn.put("id", name);
            // 台账口径字段（前端沿用）：卡明细优先用集群实测发现，没有就取台账
            Map<String, Object> ledger = db.node(name);
            cn.put("role", ledger == null ? "agent" : ledger.get("role"));
            cn.put("poolId", ledger == null ? "p1" : ledger.get("pool_id"));
            List<Map<String, Object>> cards = gpusByNode.getOrDefault(name, List.of());
            if (cards.isEmpty() && ledger != null) {
                cards = db.q("SELECT * FROM gpus WHERE node_id=? ORDER BY idx", name);
            }
            cn.put("gpus", cards);
            cn.put("gpuLedger", ledger == null ? 0 : intOf(ledger.get("gpu_count")));
            cn.put("dataSource", "cluster");
            out.add(cn);
        }
        for (Map<String, Object> ln : db.nodesFull()) {
            if (inCluster.contains(String.valueOf(ln.get("id")))) {
                continue;
            }
            ledgerAliases(ln);
            ln.put("gpus", db.q("SELECT * FROM gpus WHERE node_id=? ORDER BY idx", ln.get("id")));
            ln.put("dataSource", "ledger");
            out.add(ln);
        }
        appendAgentNode(out);
        return out;
    }

    /** 本机 host agent 上报的真实 GPU 作为节点列出（dataSource=host-agent，前端单独标注） */
    private void appendAgentNode(List<Map<String, Object>> out) {
        for (Map<String, Object> h : db.agentHosts()) {
            String hostname = String.valueOf(h.get("hostname"));
            List<Map<String, Object>> snap = db.agentLatest(hostname);
            if (snap.isEmpty()) {
                continue;
            }
            Map<String, Object> s = snap.get(0);
            List<Map<String, Object>> cards = new ArrayList<>();
            for (Map<String, Object> g : db.agentGpus(String.valueOf(s.get("id")))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", "ag-" + g.get("idx"));
                c.put("idx", g.get("idx"));
                c.put("model", g.get("name"));
                c.put("mem_gb", Math.round(numOf(g.get("mem_total_mb")) / 1024.0 * 10) / 10.0);
                c.put("temp_c", g.get("temp_c"));
                c.put("status", "free");
                cards.add(c);
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", "agent-" + hostname);
            n.put("name", hostname);
            n.put("hostname", hostname);
            n.put("ip", "本机实采");
            n.put("status", "Ready");
            n.put("role", "host-agent");
            n.put("poolId", "p1");
            n.put("dataSource", "host-agent");
            n.put("memGb", s.get("mem_total_gb"));
            n.put("podCount", 0);
            n.put("gpuCount", cards.size());
            n.put("gpuLedger", cards.size());
            n.put("gpus", cards);
            n.put("lastSeen", s.get("collected_at"));
            out.add(n);
        }
    }

    /** 台账行补上集群行同名的键，前端两套数据一套渲染 */
    private void ledgerAliases(Map<String, Object> n) {
        n.putIfAbsent("name", n.get("id"));
        n.putIfAbsent("hostname", n.get("id"));
        n.putIfAbsent("k8sVersion", n.get("k8s_version"));
        n.putIfAbsent("cpuCores", n.get("cpu_cores"));
        n.putIfAbsent("memGb", n.get("mem_gb"));
        n.putIfAbsent("podCount", n.get("pod_count"));
        n.putIfAbsent("gpuCount", n.get("gpu_count"));
        n.putIfAbsent("gpuProduct", null);
    }

    @PostMapping("/nodes/{id}/cordon")
    public Map<String, Object> cordon(@PathVariable String id) {
        Map<String, Object> node = db.node(id);
        ClusterOps ops = ops();
        ensureNodeExists(id, node, ops);
        boolean clusterDone = false;
        if (ops != null) {
            try {
                clusterDone = ops.setCordon(id, true);
                if (!clusterDone) {
                    throw new RentalService.BizException("集群执行 cordon 失败（节点不存在或无权限）");
                }
            } catch (Exception e) {
                throw new RentalService.BizException("集群 cordon 失败：" + e.getMessage());
            }
        }
        int n = node == null ? 0 : db.setNodeStatus(id, "Cordoned");
        if (node != null) {
            db.insertAlert("a" + UUID.randomUUID().toString().substring(0, 8), "P2", "节点已封禁调度",
                    "node", id, "人工 Cordon，新的 Pod 不会再调度到该节点");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", n);
        out.put("dataSource", ops == null ? "ledger" : "cluster+ledger");
        out.put("clusterCordoned", clusterDone);
        out.put("message", ops == null
                ? "台账已置 Cordoned（fake 模式未接集群）"
                : "已 Cordon，节点不再接收新任务（集群已生效" + (node == null ? "，台账无此节点记录" : "") + "）");
        return out;
    }

    @PostMapping("/nodes/{id}/uncordon")
    public Map<String, Object> uncordon(@PathVariable String id) {
        Map<String, Object> node = db.node(id);
        ClusterOps ops = ops();
        ensureNodeExists(id, node, ops);
        boolean clusterDone = false;
        if (ops != null) {
            try {
                clusterDone = ops.setCordon(id, false);
                if (!clusterDone) {
                    throw new RentalService.BizException("集群执行 uncordon 失败（节点不存在或无权限）");
                }
            } catch (Exception e) {
                throw new RentalService.BizException("集群 uncordon 失败：" + e.getMessage());
            }
        }
        int n = node == null ? 0 : db.setNodeStatus(id, "Ready");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", n);
        out.put("dataSource", ops == null ? "ledger" : "cluster+ledger");
        out.put("clusterUncordoned", clusterDone);
        out.put("message", ops == null
                ? "台账已恢复 Ready（fake 模式未接集群）"
                : "已恢复调度（集群已生效" + (node == null ? "，台账无此节点记录" : "") + "）");
        return out;
    }

    @PostMapping("/nodes/{id}/drain")
    public Map<String, Object> drain(@PathVariable String id) {
        Map<String, Object> node = db.node(id);
        ClusterOps ops = ops();
        ensureNodeExists(id, node, ops);
        long running = db.q("SELECT COUNT(*) AS c FROM instances WHERE node_id=? AND status IN ('running','provisioning')", id)
                .stream().mapToLong(r -> intOf(r.get("c"))).sum();

        int evicted = 0;
        if (ops != null) {
            try {
                if (!ops.setCordon(id, true)) {
                    throw new RentalService.BizException("集群执行 cordon 失败（节点不存在或无权限）");
                }
                evicted = ops.evictManagedPods(id);
            } catch (Exception e) {
                throw new RentalService.BizException("集群驱逐失败：" + e.getMessage());
            }
        }
        if (node != null) {
            db.setNodeStatus(id, "Draining");
            db.insertAlert("a" + UUID.randomUUID().toString().substring(0, 8), "P1", "节点开始驱逐",
                    "node", id, "正在驱逐 " + running + " 个实例，请勿在该节点新建实例");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", id);
        out.put("drained", running);
        out.put("evictedPods", evicted);
        out.put("dataSource", ops == null ? "ledger" : "cluster+ledger");
        out.put("message", ops == null
                ? "已进入 Draining：该节点上的 " + running + " 个运行中实例需要迁移或等待释放（fake 模式仅台账）"
                : "已进入 Draining：cordon + 驱逐 " + evicted + " 个托管 Pod（集群已生效）");
        return out;
    }

    /** real 模式允许操作台账里没有的集群节点（自动发现的），但集群里也必须真的有 */
    private void ensureNodeExists(String id, Map<String, Object> ledgerNode, ClusterOps ops) {
        if (ledgerNode != null) {
            return;
        }
        if (ops == null) {
            throw new RentalService.BizException("节点不存在");
        }
        boolean inCluster;
        try {
            inCluster = ops.clusterNodes().stream()
                    .anyMatch(n -> id.equals(String.valueOf(n.get("name"))));
        } catch (Exception e) {
            throw new RentalService.BizException("无法查询集群节点：" + e.getMessage());
        }
        if (!inCluster) {
            throw new RentalService.BizException("节点不存在（台账与集群都没有）");
        }
    }

    // ------------------------------------------------ 工作负载

    @GetMapping("/workloads")
    public List<Map<String, Object>> workloads() {
        List<Map<String, Object>> rows = db.allInstances();
        rows.forEach(r -> r.put("dataSource", "ledger"));
        return rows;
    }

    // ------------------------------------------------ 资源池与队列

    @GetMapping("/pools")
    public Map<String, Object> pools() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pools", db.pools());
        out.put("queues", db.queues());
        out.put("nodes", db.nodesFull());
        out.put("gpus", db.gpus());
        out.put("dataSource", "ledger");
        return out;
    }

    // ------------------------------------------------ 租户

    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants() {
        List<Map<String, Object>> list = db.tenants();
        for (Map<String, Object> t : list) {
            int used = db.occupiedGpusOfTenant((String) t.get("id"));
            t.put("used_gpus", used);
            Map<String, Object> spend = db.one(
                    "SELECT COALESCE(SUM(amount),0) AS s FROM billing_records WHERE tenant_id=?", t.get("id"));
            t.put("spent", spend == null ? BigDecimal.ZERO : spend.get("s"));
        }
        return list;
    }

    @PostMapping("/tenants")
    public Map<String, Object> createTenant(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            throw new RentalService.BizException("租户名称不能为空");
        }
        int quota = body.get("quota") == null ? 2 : ((Number) body.get("quota")).intValue();
        BigDecimal balance = new BigDecimal(String.valueOf(body.getOrDefault("balance", "0")));
        String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        db.insertTenant(id, name, quota, balance, String.valueOf(body.getOrDefault("owner", "ops")));
        return Map.of("id", id, "message", "租户已创建：" + name);
    }

    @PostMapping("/tenants/{id}/quota")
    public Map<String, Object> setQuota(@PathVariable String id, @RequestBody Map<String, Object> body) {
        int quota = ((Number) body.get("quota")).intValue();
        int n = db.updateTenantQuota(id, quota);
        if (n == 0) {
            throw new RentalService.BizException("租户不存在");
        }
        ClusterOps ops = ops();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", n);
        if (ops != null) {
            try {
                ops.syncResourceQuota("tenant-" + id, quota);
                out.put("dataSource", "cluster+ledger");
                out.put("message", "配额已调整为 " + quota + " 卡（集群 ResourceQuota 已同步）");
            } catch (Exception e) {
                out.put("dataSource", "ledger");
                out.put("message", "配额已调整为 " + quota + " 卡，但集群配额同步失败：" + e.getMessage());
            }
        } else {
            out.put("dataSource", "ledger");
            out.put("message", "配额已调整为 " + quota + " 卡");
        }
        return out;
    }

    @PostMapping("/tenants/{id}/status")
    public Map<String, Object> setTenantStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String status = String.valueOf(body.getOrDefault("status", "active"));
        int n = db.updateTenantStatus(id, status);
        return Map.of("changed", n, "message", n > 0 ? "租户状态：" + status : "租户不存在");
    }

    // ------------------------------------------------ 告警

    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alerts", db.alerts());
        out.put("active", db.q("SELECT level, COUNT(*) AS c FROM alerts WHERE status='active' GROUP BY level"));
        return out;
    }

    @PostMapping("/alerts/{id}/ack")
    public Map<String, Object> ack(@PathVariable String id) {
        return Map.of("changed", db.ackAlert(id), "message", "已确认");
    }

    @PostMapping("/alerts/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable String id) {
        return Map.of("changed", db.resolveAlert(id), "message", "已解决");
    }

    /** AI 诊断：调用配置的大模型分析告警；未配置 / 失败时回退内置规则建议（诚实标注来源） */
    @PostMapping("/alerts/{id}/diagnose")
    public Map<String, Object> diagnoseAlert(@PathVariable String id) {
        Map<String, Object> a = db.alertById(id);
        if (a == null) {
            throw new RentalService.BizException("告警不存在");
        }
        LlmClient.Result r = llm.chat(
                "你是 GPU 算力平台的资深运维专家。根据告警与对象上下文给出：1) 可能原因 2) 处置步骤 3) 是否需要人工介入。用中文，不超过 120 字。",
                "告警：" + a.get("title") + "（" + a.get("level") + "）\n对象：" + a.get("source") + "/" + a.get("target")
                        + "\n详情：" + a.get("detail") + "\n" + contextOf(a));
        String diagnosis = r.ok() ? r.content() : builtinAdvice(a);
        db.setAlertDiagnosis(id, diagnosis);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("diagnosis", diagnosis);
        out.put("source", r.ok() ? "llm" : "builtin");
        out.put("message", r.ok() ? "已生成 AI 诊断" : r.message());
        return out;
    }

    /** 告警对象的台账上下文，供大模型参考 */
    private String contextOf(Map<String, Object> a) {
        String source = String.valueOf(a.get("source"));
        String target = String.valueOf(a.get("target"));
        Map<String, Object> row = switch (source) {
            case "gpu" -> db.one("SELECT * FROM gpus WHERE id=?", target);
            case "node" -> db.one("SELECT * FROM nodes WHERE id=?", target);
            case "tenant" -> db.one("SELECT * FROM tenants WHERE id=?", target);
            default -> null;
        };
        return row == null ? "（无额外上下文）" : "对象当前状态：" + row;
    }

    /** 内置规则建议：不依赖大模型也能给出可执行的处置方向 */
    private String builtinAdvice(Map<String, Object> a) {
        String source = String.valueOf(a.get("source"));
        String title = String.valueOf(a.get("title"));
        if ("gpu".equals(source)) {
            if (title.contains("温度")) {
                return "检查机箱风道与风扇转速，将任务迁移到同节点其它卡；若持续高于 90°C 建议返修。";
            }
            if (title.contains("故障") || title.contains("XID")) {
                return "导出 XID 与 DCGM 记录留档，安排更换或返修，故障卡暂不出售。";
            }
        }
        if ("node".equals(source)) {
            return "检查 kubelet 服务与节点网络，确认节点是否被驱逐或 Cordoned；必要时重启 kubelet。";
        }
        if ("tenant".equals(source)) {
            return "提醒租户充值或下调配额，关注其运行中实例的到期时间，避免到期回收引发投诉。";
        }
        if ("platform".equals(source) && title.contains("回收")) {
            return "检查 Pod 是否卡在 Terminating，必要时强制删除；确认卡已归还可售池。";
        }
        return "确认告警对象当前状态，按级别安排处理；P0 需立即人工介入。";
    }

    // ------------------------------------------------ 巡检

    @GetMapping("/inspections")
    public Map<String, Object> inspections() {
        List<Map<String, Object>> list = db.inspections();
        for (Map<String, Object> i : list) {
            i.put("items", db.inspectionItems((String) i.get("id")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inspections", list);
        return out;
    }

    /** 真跑一次巡检：读当前集群状态算问题，不是写死的假数据 */
    @PostMapping("/inspections/run")
    public Map<String, Object> runInspection() {
        String id = "ins" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        int issues = 0;

        for (Map<String, Object> g : db.q("SELECT * FROM gpus WHERE temp_c >= 80 OR status='fault'")) {
            String level = "fault".equals(g.get("status")) ? "P0" : "P1";
            String item = "fault".equals(g.get("status")) ? "显卡故障，已从可售池摘除" : "GPU 温度超过 80°C";
            db.insertInspectionItem(id, level, item, (String) g.get("id"),
                    "fault".equals(g.get("status")) ? "安排更换或返修，确认 XID 记录" : "检查风道，或将任务迁移到同节点其他卡", false);
            issues++;
        }
        for (Map<String, Object> n : db.q("SELECT * FROM nodes WHERE status='NotReady'")) {
            db.insertInspectionItem(id, "P0", "节点 NotReady", (String) n.get("id"), "检查 kubelet 与网络", false);
            issues++;
        }
        for (Map<String, Object> t : db.q("SELECT * FROM tenants WHERE status='active' AND balance_yuan < 1000")) {
            db.insertInspectionItem(id, "P2", "租户余额低于预警线", (String) t.get("id"), "提醒充值或下调配额", false);
            issues++;
        }
        long stale = db.q("SELECT COUNT(*) AS c FROM instances WHERE status='releasing'")
                .stream().mapToLong(r -> intOf(r.get("c"))).sum();
        if (stale > 0) {
            db.insertInspectionItem(id, "P1", "存在回收卡住的实例", "instances",
                    "检查 Pod 是否停在 Terminating，必要时强制删除", false);
            issues++;
        }
        // 反挖矿巡检（A-46）：占用卡利用率长期满负荷的实例标记为疑似挖矿
        for (Map<String, Object> g : db.q("SELECT * FROM gpus WHERE status='occupied' AND util_pct >= 98")) {
            db.insertInspectionItem(id, "P1", "疑似挖矿：GPU 利用率持续满负荷（" + g.get("util_pct") + "%）",
                    String.valueOf(g.get("instance_id")),
                    "核对租户任务特征与 nvidia-smi 进程名，确认挖矿即冻结实例并按协议追责", false);
            issues++;
        }

        // 集群实测补充：组件缺失 / 节点 NotReady 才是巡检该看的东西
        ClusterOps ops = ops();
        boolean clusterChecked = false;
        if (ops != null) {
            clusterChecked = true;
            try {
                for (Map<String, Object> c : ops.componentHealth()) {
                    String stt = String.valueOf(c.get("status"));
                    if ("NotFound".equals(stt)) {
                        db.insertInspectionItem(id, "P2", "组件未部署：" + c.get("name"),
                                String.valueOf(c.get("name")), "安装 " + c.get("name") + " 或明确标记未接入", false);
                        issues++;
                    } else if ("Degraded".equals(stt) || "Down".equals(stt)) {
                        db.insertInspectionItem(id, "P1", "组件异常：" + c.get("name"),
                                String.valueOf(c.get("name")), String.valueOf(c.get("detail")), false);
                        issues++;
                    }
                }
            } catch (Exception e) {
                db.insertInspectionItem(id, "P2", "集群探测失败", "cluster", "检查 kubeconfig 与网络：" + e.getMessage(), false);
                issues++;
            }
        }

        int score = Math.max(50, 100 - issues * 5);
        String summary = issues == 0 ? "未发现问题，集群健康" : "发现 " + issues + " 项待处理问题";
        db.insertInspection(id, score, issues, summary);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("healthScore", score);
        out.put("issues", issues);
        out.put("summary", summary);
        out.put("clusterChecked", clusterChecked);
        out.put("items", db.inspectionItems(id));
        return out;
    }

    /** 批量 AI 诊断巡检问题：逐项调用大模型，失败项回退到巡检自带建议 */
    @PostMapping("/inspections/{id}/diagnose")
    public Map<String, Object> diagnoseInspection(@PathVariable String id) {
        List<Map<String, Object>> items = db.inspectionItemsUnfixed(id);
        if (items.isEmpty()) {
            throw new RentalService.BizException("该巡检没有未修复的问题项");
        }
        int llmCount = 0;
        for (Map<String, Object> it : items) {
            LlmClient.Result r = llm.chat(
                    "你是 GPU 算力平台的巡检专家。对巡检问题给出：1) 根因 2) 处置步骤。用中文，不超过 80 字。",
                    "巡检问题：" + it.get("item") + "（" + it.get("level") + "）\n对象：" + it.get("target")
                            + "\n现有建议：" + it.get("suggestion"));
            String diag = r.ok() ? r.content() : String.valueOf(it.get("suggestion"));
            if (r.ok()) {
                llmCount++;
            }
            db.setInspectionItemDiagnosis(((Number) it.get("id")).longValue(), diag);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diagnosed", items.size());
        out.put("llm", llmCount);
        out.put("message", "已诊断 " + items.size() + " 项（其中大模型 " + llmCount + " 项，其余为内置建议）");
        return out;
    }

    /** 巡检问题转工单：未修复且未转过的问题项各生成一张平台内部工单 */
    @PostMapping("/inspections/{id}/ticket")
    public Map<String, Object> inspectionToTicket(@PathVariable String id,
                                                  @RequestHeader(value = "X-User", required = false) String user) {
        List<Map<String, Object>> items = db.inspectionItemsUnfixed(id);
        int created = 0;
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (it.get("ticket_id") != null) {
                continue;
            }
            String tid = "tk" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            String content = String.valueOf(it.get("suggestion"))
                    + (it.get("diagnosis") == null ? "" : "\n诊断：" + it.get("diagnosis"));
            db.insertTicket(tid, "platform", String.valueOf(it.get("item")), content);
            db.setInspectionItemTicket(((Number) it.get("id")).longValue(), tid);
            db.insertAudit(user == null ? "admin" : user, "TICKET_FROM_INSPECTION", tid,
                    String.valueOf(it.get("item")));
            ids.add(tid);
            created++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("tickets", ids);
        out.put("message", created == 0 ? "没有可转工单的未修复项" : "已生成 " + created + " 张平台工单");
        return out;
    }

    // ------------------------------------------------ 部署向导 / 工作负载对象

    @GetMapping("/deployments")
    public List<Map<String, Object>> deployments() {
        return db.workloads();
    }

    @PostMapping("/deployments")
    public Map<String, Object> deploy(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            throw new RentalService.BizException("工作负载名称不能为空");
        }
        String image = String.valueOf(body.getOrDefault("image", "")).trim();
        if (image.isEmpty()) {
            throw new RentalService.BizException("镜像地址不能为空");
        }
        String kind = String.valueOf(body.getOrDefault("kind", "Deployment"));
        String tenantId = String.valueOf(body.getOrDefault("tenantId", "t1"));
        String ns = String.valueOf(body.getOrDefault("namespace", "tenant-" + tenantId));
        int gpu = body.get("gpuCount") == null ? 1 : ((Number) body.get("gpuCount")).intValue();
        String memLimit = String.valueOf(body.getOrDefault("memLimit", "whole"));
        String policy = String.valueOf(body.getOrDefault("policy", "binpack"));
        int replicas = body.get("replicas") == null ? 1 : ((Number) body.get("replicas")).intValue();

        if (gpu < 1 || gpu > 8) {
            throw new RentalService.BizException("GPU 数量必须在 1~8 之间");
        }
        int free = db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='free'")
                .stream().mapToInt(r -> intOf(r.get("c"))).sum();
        if (free < gpu) {
            throw new RentalService.BizException("可用显卡不足：需要 " + gpu + " 张，当前空闲 " + free + " 张");
        }

        String id = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.insertWorkload(id, name, kind, ns, image, gpu, memLimit, policy, tenantId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("namespace", ns);

        ClusterOps ops = ops();
        if (ops == null) {
            out.put("dataSource", "ledger");
            out.put("message", "已记录到台账：" + kind + " " + name + " → " + ns + "（fake 模式未下发集群，约 3 秒转 Running）");
            return out;
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("kind", kind);
        spec.put("namespace", ns);
        spec.put("name", name);
        spec.put("image", image);
        spec.put("gpuCount", gpu);
        spec.put("replicas", replicas);
        spec.put("memLimit", memLimit);
        spec.put("policy", policy);
        try {
            Map<String, Object> created = ops.createWorkload(spec);
            db.setWorkloadStatus(id, "Running");
            out.putAll(created);
            out.put("dataSource", "cluster");
            out.put("message", "已下发集群：" + kind + " " + name + " → " + ns);
        } catch (Exception e) {
            db.setWorkloadStatus(id, "Failed");
            throw new RentalService.BizException("集群创建失败：" + e.getMessage());
        }
        return out;
    }

    @DeleteMapping("/deployments/{id}")
    public Map<String, Object> undeploy(@PathVariable String id) {
        Map<String, Object> w = db.workload(id);
        if (w == null) {
            throw new RentalService.BizException("工作负载不存在");
        }
        ClusterOps ops = ops();
        boolean clusterDeleted = false;
        String clusterError = null;
        if (ops != null) {
            try {
                clusterDeleted = ops.deleteWorkload(String.valueOf(w.get("kind")),
                        String.valueOf(w.get("namespace")), String.valueOf(w.get("name")));
                if (!clusterDeleted) {
                    clusterError = "集群对象不存在或删除失败";
                }
            } catch (Exception e) {
                clusterError = e.getMessage();
            }
        }
        int n = db.deleteWorkload(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", n);
        out.put("dataSource", ops == null ? "ledger" : "cluster+ledger");
        out.put("clusterDeleted", clusterDeleted);
        out.put("message", (clusterError != null ? "集群删除异常：" + clusterError + "；" : "")
                + (n > 0 ? "台账已删除，资源回收" : "台账删除失败"));
        return out;
    }

    // ------------------------------------------------ 控制面组件健康

    @GetMapping("/components")
    public List<Map<String, Object>> components() {
        ClusterOps ops = ops();
        if (ops != null) {
            try {
                List<Map<String, Object>> list = ops.componentHealth();
                list.forEach(c -> c.put("dataSource", "cluster"));
                return list;
            } catch (Exception e) {
                // 集群探测整体失败也要如实反映，不落回假数据
                List<Map<String, Object>> fail = new ArrayList<>();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", "cluster-probe");
                m.put("group", "control-plane");
                m.put("status", "Down");
                m.put("detail", "探测集群失败：" + e.getMessage());
                m.put("dataSource", "cluster");
                fail.add(m);
                return fail;
            }
        }
        int fault = db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='fault'")
                .stream().mapToInt(r -> intOf(r.get("c"))).sum();
        int notReady = db.q("SELECT COUNT(*) AS c FROM nodes WHERE status='NotReady'")
                .stream().mapToInt(r -> intOf(r.get("c"))).sum();

        List<Map<String, Object>> list = new ArrayList<>();
        list.add(comp("kube-apiserver", "control-plane", notReady > 0 ? "Degraded" : "Simulated", "6443"));
        list.add(comp("etcd", "control-plane", "Simulated", "2379"));
        list.add(comp("kube-scheduler", "control-plane", "Simulated", "10259"));
        list.add(comp("controller-manager", "control-plane", "Simulated", "10257"));
        list.add(comp("coredns", "addon", "Simulated", "53"));
        list.add(comp("metrics-server", "addon", "Simulated", "4443"));
        list.add(comp("nvidia-gpu-operator", "gpu", fault > 0 ? "Degraded" : "Simulated", "—"));
        list.add(comp("dcgm-exporter", "gpu", "Simulated", "9400"));
        list.add(comp("volcano-scheduler", "scheduler", "Simulated", "—"));
        for (Map<String, Object> c : list) {
            c.put("dataSource", "ledger");
            c.put("detail", "fake 模式：无真实集群，状态为演示值");
        }
        return list;
    }

    private static Map<String, Object> comp(String name, String group, String status, String port) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("group", group);
        m.put("status", status);
        m.put("port", port);
        return m;
    }

    // ------------------------------------------------ 命名空间与配额

    @GetMapping("/namespaces")
    public List<Map<String, Object>> namespaces() {
        ClusterOps ops = ops();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, String> sysNote = Map.of(
                "kube-system", "控制面与核心插件",
                "gpu-operator", "GPU 设备纳管",
                "monitoring", "Prometheus / Grafana / Alertmanager",
                "volcano-system", "队列与调度",
                "rental", "平台业务服务");

        if (ops == null) {
            for (String name : List.of("kube-system", "gpu-operator", "monitoring", "volcano-system", "rental")) {
                list.add(ns(name, "system", 0, 0, sysNote.get(name), "ledger"));
            }
            for (Map<String, Object> t : db.tenants()) {
                int quota = intOf(t.get("quota_gpus"));
                int used = db.occupiedGpusOfTenant((String) t.get("id"));
                list.add(ns("tenant-" + t.get("id"), "tenant", quota, used, String.valueOf(t.get("name")), "ledger"));
            }
            return list;
        }

        List<Map<String, Object>> clusterNs;
        try {
            clusterNs = ops.clusterNamespaces();
        } catch (Exception e) {
            clusterNs = List.of();
        }
        Set<String> observed = new HashSet<>();
        for (Map<String, Object> cn : clusterNs) {
            String name = String.valueOf(cn.get("name"));
            observed.add(name);
            String kind = name.startsWith("tenant-") ? "tenant"
                    : sysNote.containsKey(name) ? "system" : "workload";
            String note = name.startsWith("tenant-") ? "租户命名空间（集群实测）"
                    : sysNote.getOrDefault(name, "集群内命名空间");
            Map<String, Object> m = ns(name, kind, intOf(cn.get("gpuQuota")), intOf(cn.get("gpuUsed")), note, "cluster");
            m.put("phase", cn.get("phase"));
            m.put("pods", cn.get("pods"));
            m.put("podQuota", cn.get("podQuota"));
            list.add(m);
        }
        // 系统命名空间在集群里没有 = 未部署，如实标注
        for (String name : List.of("kube-system", "gpu-operator", "monitoring", "volcano-system", "rental")) {
            if (!observed.contains(name)) {
                list.add(ns(name, "system", 0, 0, sysNote.get(name) + "（集群中未部署）", "cluster"));
            }
        }
        // 台账租户：集群里没有对应命名空间的 = 未下发
        for (Map<String, Object> t : db.tenants()) {
            String name = "tenant-" + t.get("id");
            if (!observed.contains(name)) {
                int quota = intOf(t.get("quota_gpus"));
                int used = db.occupiedGpusOfTenant((String) t.get("id"));
                list.add(ns(name, "tenant", quota, used,
                        t.get("name") + "（台账已建，命名空间未下发到集群）", "ledger"));
            }
        }
        return list;
    }

    private static Map<String, Object> ns(String name, String kind, int quota, int used, String note,
                                          String dataSource) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("quota", quota);
        m.put("used", used);
        m.put("note", note);
        m.put("dataSource", dataSource);
        return m;
    }

    // ------------------------------------------------ 节点接入命令

    @GetMapping("/nodes/{id}/join")
    public Map<String, Object> joinCommand(@PathVariable String id) {
        ClusterOps ops = ops();
        if (ops != null) {
            try {
                Map<String, Object> real = ops.joinCommand();
                real.put("node", id);
                real.put("dataSource", "cluster");
                return real;
            } catch (Exception e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("node", id);
                out.put("command", "生成失败：" + e.getMessage());
                out.put("dataSource", "cluster");
                return out;
            }
        }
        Map<String, Object> node = db.node(id);
        String ip = node == null ? "10.0.0.11" : String.valueOf(node.get("ip"));
        String cmd = "kubeadm join 10.0.0.11:6443 \\\n"
                + "  --token <TOKEN> \\\n"
                + "  --discovery-token-ca-cert-hash sha256:<CA-CERT-HASH>";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", id);
        out.put("command", cmd);
        out.put("dataSource", "ledger");
        out.put("note", "演示值（fake 模式）。真实 token 用 kubeadm token create --print-join-command 生成，默认 24 小时过期；ca-cert-hash 由 openssl 计算。");
        out.put("target", ip);
        return out;
    }

    // ------------------------------------------------ 时序（近 N 分钟）

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestParam(defaultValue = "30") int minutes) {
        long occupied = db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='occupied'")
                .stream().mapToLong(r -> intOf(r.get("c"))).sum();
        long total = db.q("SELECT COUNT(*) AS c FROM gpus").stream().mapToLong(r -> intOf(r.get("c"))).sum();
        double base = total == 0 ? 0 : occupied * 100.0 / total;

        Map<String, Object> agent = fromAgent(minutes);
        if (agent != null) {
            agent.put("baseUtil", Math.round(base * 10) / 10.0);
            return agent;
        }

        PrometheusClient prom = prom();
        if (prom != null) {
            Map<String, Object> real = fromPrometheus(prom, minutes);
            if (real != null) {
                real.put("baseUtil", Math.round(base * 10) / 10.0);
                return real;
            }
            // prometheus 配置了但查不到：继续用合成曲线，但要说清楚
            Map<String, Object> out = syntheticMetrics(minutes, base);
            out.put("dataSource", "synthetic");
            out.put("note", "Prometheus 不可达或查询失败，显示合成曲线");
            return out;
        }
        Map<String, Object> out = syntheticMetrics(minutes, base);
        out.put("dataSource", "synthetic");
        out.put("note", "未配置 rental.prometheus.url，曲线为演示合成数据；接入 DCGM-Exporter 与 Prometheus 后替换为真实查询");
        return out;
    }

    /** 本机 agent 实采曲线（nvidia-smi 每 10 秒上报，按分钟取平均）；无数据返回 null */
    private Map<String, Object> fromAgent(int minutes) {
        List<Map<String, Object>> rows = db.agentSeries(minutes);
        if (rows.isEmpty()) {
            return null;
        }
        List<String> ts = new ArrayList<>();
        List<Double> utilVals = new ArrayList<>();
        List<Double> memVals = new ArrayList<>();
        List<Double> powerVals = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        for (Map<String, Object> r : rows) {
            Object tsv = r.get("ts");
            if (tsv == null) {
                continue;
            }
            c.setTimeInMillis(((Timestamp) tsv).getTime());
            ts.add(String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
            utilVals.add(round1(numOf(r.get("util"))));
            memVals.add(round1(numOf(r.get("mem"))));
            powerVals.add(round1(numOf(r.get("power"))));
        }
        if (ts.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSource", "host-agent");
        out.put("time", ts);
        out.put("gpuUtil", utilVals);
        out.put("memUtil", memVals);
        out.put("powerW", powerVals);
        out.put("note", "本机真实 GPU（Windows host agent · nvidia-smi 实采，非集群 DCGM）");
        return out;
    }

    private static Double numOf(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 真实 Prometheus 数据；DCGM 没上报就返回诚实的空状态，绝不合成 */
    private Map<String, Object> fromPrometheus(PrometheusClient prom, int minutes) {
        try {
            if (!prom.healthy()) {
                return null;
            }
            List<double[]> util = prom.range("avg(DCGM_FI_DEV_GPU_UTIL)", minutes, 1);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dataSource", "prometheus");
            if (util.isEmpty()) {
                out.put("time", timeLabels(minutes));
                out.put("gpuUtil", List.of());
                out.put("memUtil", List.of());
                out.put("powerW", List.of());
                out.put("note", "Prometheus 已接入，但 DCGM_FI_DEV_GPU_UTIL 无数据："
                        + "GPU Operator / DCGM-Exporter 未部署或未上报指标");
                return out;
            }
            List<double[]> mem = prom.range("100 * sum(DCGM_FI_DEV_FB_USED) / sum(DCGM_FI_DEV_FB_TOTAL)", minutes, 1);
            List<double[]> power = prom.range("avg(DCGM_FI_DEV_POWER_USAGE)", minutes, 0.001);

            Map<Long, Double> memByMin = byMinute(mem);
            Map<Long, Double> powerByMin = byMinute(power);

            List<String> ts = new ArrayList<>();
            List<Double> utilVals = new ArrayList<>();
            List<Double> memVals = new ArrayList<>();
            List<Double> powerVals = new ArrayList<>();
            for (double[] p : util) {
                long minute = (long) (p[0] / 60000);
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis((long) p[0]);
                ts.add(String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
                utilVals.add(Math.round(p[1] * 10) / 10.0);
                memVals.add(round1(memByMin.get(minute)));
                powerVals.add(round1(powerByMin.get(minute)));
            }
            out.put("time", ts);
            out.put("gpuUtil", utilVals);
            out.put("memUtil", memVals);
            out.put("powerW", powerVals);
            out.put("note", "真实 Prometheus 查询：DCGM_FI_DEV_GPU_UTIL / DCGM_FI_DEV_FB_* / DCGM_FI_DEV_POWER_USAGE");
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<Long, Double> byMinute(List<double[]> series) {
        Map<Long, Double> m = new HashMap<>();
        for (double[] p : series) {
            m.put((long) (p[0] / 60000), p[1]);
        }
        return m;
    }

    private static Double round1(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }

    private static List<String> timeLabels(int minutes) {
        List<String> ts = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = minutes - 1; i >= 0; i--) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now - i * 60_000L);
            ts.add(String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
        }
        return ts;
    }

    private Map<String, Object> syntheticMetrics(int minutes, double base) {
        List<String> ts = timeLabels(minutes);
        List<Double> util = new ArrayList<>();
        List<Double> mem = new ArrayList<>();
        List<Double> power = new ArrayList<>();
        Random rnd = new Random(42); // 固定种子，刷新不会跳变

        for (int i = minutes - 1; i >= 0; i--) {
            double wave = Math.sin((minutes - i) / 4.0) * 8 + rnd.nextGaussian() * 3;
            double u = Math.max(0, Math.min(100, base + (base == 0 ? 6 : 18) + wave));
            util.add(Math.round(u * 10) / 10.0);
            mem.add(Math.round((u * 0.42 + 18) * 10) / 10.0);
            power.add(Math.round((u * 4.6 + 120) * 10) / 10.0);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", ts);
        out.put("gpuUtil", util);
        out.put("memUtil", mem);
        out.put("powerW", power);
        return out;
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
