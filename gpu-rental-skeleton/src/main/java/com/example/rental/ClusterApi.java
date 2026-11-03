package com.example.rental;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 管理端接口：集群总览 / 节点 / 工作负载 / 资源池 / 租户 / 告警 / 巡检 / 时序 */
@RestController
@RequestMapping("/api/admin")
public class ClusterApi {

    private final Db db;

    public ClusterApi(Db db) {
        this.db = db;
    }

    // ------------------------------------------------ 总览

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        int nodes = intOf(db.q("SELECT COUNT(*) AS c FROM nodes").get(0).get("c"));
        int nodesReady = intOf(db.q("SELECT COUNT(*) AS c FROM nodes WHERE status IN ('Ready','Cordoned','Draining')")
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

        double util = total == 0 ? 0 : st.get("occupied") * 100.0 / total;

        out.put("nodeTotal", nodes);
        out.put("nodeReady", nodesReady);
        out.put("gpuTotal", total);
        out.put("gpuStatus", st);
        out.put("gpuUtilization", Math.round(util * 10) / 10.0);
        out.put("tenantActive", tenants);
        out.put("runningInstances", running);
        out.put("activeAlerts", activeAlerts);
        out.put("todayRevenue", todayRevenue);
        out.put("healthScore", Math.max(60, 100 - activeAlerts * 4 - st.get("fault") * 5));
        out.put("k8sVersion", "v1.34.1+k3s1");
        return out;
    }

    // ------------------------------------------------ 节点

    @GetMapping("/nodes")
    public List<Map<String, Object>> nodes() {
        List<Map<String, Object>> rows = db.nodesFull();
        for (Map<String, Object> n : rows) {
            n.put("gpus", db.q("SELECT * FROM gpus WHERE node_id=? ORDER BY idx", n.get("id")));
        }
        return rows;
    }

    @PostMapping("/nodes/{id}/cordon")
    public Map<String, Object> cordon(@PathVariable String id) {
        int n = db.setNodeStatus(id, "Cordoned");
        db.insertAlert("a" + UUID.randomUUID().toString().substring(0, 8), "P2", "节点已封禁调度",
                "node", id, "人工 Cordon，新的 Pod 不会再调度到该节点");
        return Map.of("changed", n, "message", n > 0 ? "已 Cordon，节点不再接收新任务" : "节点不存在");
    }

    @PostMapping("/nodes/{id}/uncordon")
    public Map<String, Object> uncordon(@PathVariable String id) {
        int n = db.setNodeStatus(id, "Ready");
        return Map.of("changed", n, "message", n > 0 ? "已恢复调度" : "节点不存在");
    }

    @PostMapping("/nodes/{id}/drain")
    public Map<String, Object> drain(@PathVariable String id) {
        Map<String, Object> node = db.node(id);
        if (node == null) {
            throw new RentalService.BizException("节点不存在");
        }
        long running = db.q("SELECT COUNT(*) AS c FROM instances WHERE node_id=? AND status IN ('running','provisioning')", id)
                .stream().mapToLong(r -> intOf(r.get("c"))).sum();
        db.setNodeStatus(id, "Draining");
        db.insertAlert("a" + UUID.randomUUID().toString().substring(0, 8), "P1", "节点开始驱逐",
                "node", id, "正在驱逐 " + running + " 个实例，请勿在该节点新建实例");
        return Map.of("node", id, "drained", running,
                "message", "已进入 Draining：该节点上的 " + running + " 个运行中实例需要迁移或等待释放");
    }

    // ------------------------------------------------ 工作负载

    @GetMapping("/workloads")
    public List<Map<String, Object>> workloads() {
        return db.allInstances();
    }

    // ------------------------------------------------ 资源池与队列

    @GetMapping("/pools")
    public Map<String, Object> pools() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pools", db.pools());
        out.put("queues", db.queues());
        out.put("nodes", db.nodesFull());
        out.put("gpus", db.gpus());
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
        return Map.of("changed", n, "message", n > 0 ? "配额已调整为 " + quota + " 卡" : "租户不存在");
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

        int score = Math.max(50, 100 - issues * 5);
        String summary = issues == 0 ? "未发现问题，集群健康" : "发现 " + issues + " 项待处理问题";
        db.insertInspection(id, score, issues, summary);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("healthScore", score);
        out.put("issues", issues);
        out.put("summary", summary);
        out.put("items", db.inspectionItems(id));
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
        out.put("message", "已提交：" + kind + " " + name + " → " + ns + "，调度中（约 3 秒转 Running）");
        return out;
    }

    @DeleteMapping("/deployments/{id}")
    public Map<String, Object> undeploy(@PathVariable String id) {
        int n = db.deleteWorkload(id);
        return Map.of("deleted", n, "message", n > 0 ? "已删除工作负载，资源回收" : "工作负载不存在");
    }

    // ------------------------------------------------ 控制面组件健康

    @GetMapping("/components")
    public List<Map<String, Object>> components() {
        int fault = db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='fault'")
                .stream().mapToInt(r -> intOf(r.get("c"))).sum();
        int notReady = db.q("SELECT COUNT(*) AS c FROM nodes WHERE status='NotReady'")
                .stream().mapToInt(r -> intOf(r.get("c"))).sum();

        List<Map<String, Object>> list = new ArrayList<>();
        list.add(comp("kube-apiserver", "control-plane", notReady > 0 ? "Degraded" : "Healthy", "6443"));
        list.add(comp("etcd", "control-plane", "Healthy", "2379"));
        list.add(comp("kube-scheduler", "control-plane", "Healthy", "10259"));
        list.add(comp("controller-manager", "control-plane", "Healthy", "10257"));
        list.add(comp("coredns", "addon", "Healthy", "53"));
        list.add(comp("metrics-server", "addon", "Healthy", "4443"));
        list.add(comp("nvidia-gpu-operator", "gpu", fault > 0 ? "Degraded" : "Healthy", "—"));
        list.add(comp("dcgm-exporter", "gpu", "Healthy", "9400"));
        list.add(comp("volcano-scheduler", "scheduler", "Healthy", "—"));
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
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(ns("kube-system", "system", 0, 0, "控制面与核心插件"));
        list.add(ns("gpu-operator", "system", 0, 0, "GPU 设备纳管"));
        list.add(ns("monitoring", "system", 0, 0, "Prometheus / Grafana / Alertmanager"));
        list.add(ns("volcano-system", "system", 0, 0, "队列与调度"));
        list.add(ns("rental", "system", 0, 0, "平台业务服务"));
        for (Map<String, Object> t : db.tenants()) {
            int quota = intOf(t.get("quota_gpus"));
            int used = db.occupiedGpusOfTenant((String) t.get("id"));
            list.add(ns("tenant-" + t.get("id"), "tenant", quota, used, String.valueOf(t.get("name"))));
        }
        return list;
    }

    private static Map<String, Object> ns(String name, String kind, int quota, int used, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("kind", kind);
        m.put("quota", quota);
        m.put("used", used);
        m.put("note", note);
        return m;
    }

    // ------------------------------------------------ 节点接入命令

    @GetMapping("/nodes/{id}/join")
    public Map<String, Object> joinCommand(@PathVariable String id) {
        Map<String, Object> node = db.node(id);
        String ip = node == null ? "10.0.0.11" : String.valueOf(node.get("ip"));
        String cmd = "kubeadm join 10.0.0.11:6443 \\\n"
                + "  --token <TOKEN> \\\n"
                + "  --discovery-token-ca-cert-hash sha256:<CA-CERT-HASH>";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", id);
        out.put("command", cmd);
        out.put("note", "演示值。真实 token 用 kubeadm token create --print-join-command 生成，默认 24 小时过期；ca-cert-hash 由 openssl 计算。");
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

        List<String> ts = new ArrayList<>();
        List<Double> util = new ArrayList<>();
        List<Double> mem = new ArrayList<>();
        List<Double> power = new ArrayList<>();
        long now = System.currentTimeMillis();
        Random rnd = new Random(42); // 固定种子，刷新不会跳变

        for (int i = minutes - 1; i >= 0; i--) {
            long t = now - i * 60_000L;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            ts.add(String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));

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
        out.put("baseUtil", Math.round(base * 10) / 10.0);
        return out;
    }

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
