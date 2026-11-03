package com.example.rental;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 平台能力：资源对象、告警规则、系统参数、审计、节点上下线、资源池、工作负载生命周期、实例接入 */
@RestController
@RequestMapping("/api/admin")
public class PlatformApi {

    private final Db db;
    private final K8sClient k8s;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public PlatformApi(Db db, K8sClient k8s) {
        this.db = db;
        this.k8s = k8s;
    }

    // ============================================================ 资源对象（YAML）

    @GetMapping("/resources")
    public List<Map<String, Object>> resources() {
        return db.resources();
    }

    @GetMapping("/resources/{id}")
    public Map<String, Object> resource(@PathVariable String id) {
        Map<String, Object> r = db.resource(id);
        if (r == null) {
            throw new RentalService.BizException("资源不存在");
        }
        return r;
    }

    /** 应用 YAML：解析 apiVersion / kind / metadata，缺字段直接拒绝（就是校验） */
    @PostMapping("/resources")
    public Map<String, Object> apply(@RequestBody Map<String, Object> body,
                                     @RequestHeader(value = "X-User", required = false) String user) {
        String yaml = String.valueOf(body.getOrDefault("yaml", ""));
        if (yaml.isBlank()) {
            throw new RentalService.BizException("YAML 不能为空");
        }

        String apiVersion = firstNonBlank(str(body.get("apiVersion")), pick(yaml, "apiVersion"));
        String kind = firstNonBlank(str(body.get("kind")), pick(yaml, "kind"));
        String name = firstNonBlank(str(body.get("name")), pickMeta(yaml, "name"));
        String ns = firstNonBlank(str(body.get("namespace")), pickMeta(yaml, "namespace"));
        if (ns == null || ns.isBlank()) {
            ns = "-";
        }

        if (apiVersion == null) {
            throw new RentalService.BizException("校验失败：缺少 apiVersion");
        }
        if (kind == null) {
            throw new RentalService.BizException("校验失败：缺少 kind");
        }
        if (name == null) {
            throw new RentalService.BizException("校验失败：metadata.name 不能为空");
        }
        if (!kind.equals("Namespace") && ns.equals("-")) {
            throw new RentalService.BizException("校验失败：" + kind + " 必须指定 metadata.namespace");
        }

        boolean exists = db.resourceByKey(kind, ns, name) != null;
        String id = "rs" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.upsertResource(id, apiVersion, kind, ns, name, yaml, exists);
        db.insertAudit(user == null ? "admin" : user, exists ? "RESOURCE_UPDATE" : "RESOURCE_CREATE",
                kind + "/" + name, "namespace=" + ns);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("name", name);
        out.put("namespace", ns);
        out.put("created", !exists);
        out.put("message", (exists ? "已更新 " : "已创建 ") + kind + " " + name + "（命名空间 " + ns + "）");
        return out;
    }

    @DeleteMapping("/resources/{id}")
    public Map<String, Object> deleteResource(@PathVariable String id,
                                              @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> r = db.resource(id);
        int n = db.deleteResource(id);
        if (n > 0 && r != null) {
            db.insertAudit(user == null ? "admin" : user, "RESOURCE_DELETE",
                    r.get("kind") + "/" + r.get("name"), "");
        }
        return Map.of("deleted", n, "message", n > 0 ? "已删除资源" : "资源不存在");
    }

    /** 临时诊断端点：看 YAML 到底被解析成什么 */
    @GetMapping("/debug/parse")
    public Map<String, Object> debugParse(@RequestParam String yaml) {
        String n = norm(yaml);
        String[] lines = n.split("\n");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rawLen", yaml.length());
        m.put("normLen", n.length());
        m.put("lineCount", lines.length);
        List<String> head = new ArrayList<>();
        for (int i = 0; i < Math.min(lines.length, 6); i++) {
            head.add("[" + lines[i] + "]");
        }
        m.put("lines", head);
        m.put("apiVersion", pick(yaml, "apiVersion"));
        m.put("kind", pick(yaml, "kind"));
        m.put("name", pickMeta(yaml, "name"));
        m.put("namespace", pickMeta(yaml, "namespace"));
        return m;
    }

    /** 统一换行：兼容真实换行、字面 \n、Windows \r\n */
    private static String norm(String s) {
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\r\n", "\n").replace("\r", "");
    }

    /** 顶层字段（apiVersion / kind），要求顶格写 */
    private static String pick(String yaml, String key) {
        for (String raw : norm(yaml).split("\n")) {
            if (!raw.equals(raw.stripLeading())) {
                continue;
            }
            String line = raw.strip();
            if (line.startsWith(key + ":")) {
                return clean(line.substring(key.length() + 1));
            }
        }
        return null;
    }

    /** metadata 下的字段（缩进块内） */
    private static String pickMeta(String yaml, String field) {
        boolean inMeta = false;
        for (String raw : norm(yaml).split("\n")) {
            String line = raw.strip();
            if (line.equals("metadata:")) {
                inMeta = true;
                continue;
            }
            if (!inMeta) {
                continue;
            }
            boolean indented = raw.startsWith(" ") || raw.startsWith("\t");
            if (!indented && !line.isEmpty() && !line.startsWith("-")) {
                inMeta = false;
                continue;
            }
            if (line.startsWith(field + ":")) {
                return clean(line.substring(field.length() + 1));
            }
        }
        return null;
    }

    private static String clean(String v) {
        String s = v.strip().replaceAll("^[\"']|[\"']$", "").strip();
        return s.isEmpty() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 前端显式传的字段优先，没有再回退到 YAML 解析 */
    private static String firstNonBlank(String explicit, String parsed) {
        return (explicit != null && !explicit.isBlank()) ? explicit.strip() : parsed;
    }

    // ============================================================ 告警规则

    @GetMapping("/alert-rules")
    public List<Map<String, Object>> rules() {
        return db.alertRules();
    }

    @PostMapping("/alert-rules")
    public Map<String, Object> createRule(@RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String expr = String.valueOf(body.getOrDefault("expr", "")).trim();
        if (name.isEmpty() || expr.isEmpty()) {
            throw new RentalService.BizException("规则名称与表达式都不能为空");
        }
        String id = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        db.insertAlertRule(id, name, String.valueOf(body.getOrDefault("target", "gpu")), expr,
                String.valueOf(body.getOrDefault("level", "P2")),
                String.valueOf(body.getOrDefault("notify", "群")), true);
        db.insertAudit(user == null ? "admin" : user, "RULE_CREATE", name, expr);
        return Map.of("id", id, "message", "规则已创建：" + name);
    }

    @PostMapping("/alert-rules/{id}/toggle")
    public Map<String, Object> toggleRule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.getOrDefault("enabled", true)));
        int n = db.toggleAlertRule(id, enabled);
        return Map.of("changed", n, "message", enabled ? "规则已启用" : "规则已停用");
    }

    @DeleteMapping("/alert-rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id) {
        int n = db.deleteAlertRule(id);
        return Map.of("deleted", n, "message", n > 0 ? "规则已删除" : "规则不存在");
    }

    // ============================================================ 系统参数与审计

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", db.settings());
        out.put("cluster", Map.of(
                "name", "prod-cluster",
                "k8s", "v1.34.1+k3s1",
                "cni", "flannel",
                "runtime", "containerd 1.7",
                "nodes", db.nodesFull().size()
        ));
        return out;
    }

    @PostMapping("/settings")
    public Map<String, Object> updateSetting(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-User", required = false) String user) {
        String k = String.valueOf(body.getOrDefault("k", ""));
        String v = String.valueOf(body.getOrDefault("v", ""));
        int n = db.updateSetting(k, v);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "SETTING_UPDATE", k, "新值 " + v);
        }
        return Map.of("changed", n, "message", n > 0 ? "参数已更新：" + k : "参数不存在");
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String action,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(1, page);
        int s = Math.min(200, Math.max(1, size));
        int total = db.auditCount(q, action);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", db.auditLogs(q, action, s, (p - 1) * s));
        out.put("total", total);
        out.put("page", p);
        out.put("size", s);
        out.put("pages", (total + s - 1) / s);
        out.put("actions", db.auditActions());
        return out;
    }

    /** 导出当前筛选结果为 CSV（带 BOM，Excel 直接打开不乱码） */
    @GetMapping("/audit/export")
    public ResponseEntity<byte[]> exportAudit(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String action) {
        List<Map<String, Object>> rows = db.auditLogs(q, action, 10000, 0);
        StringBuilder sb = new StringBuilder("\uFEFF时间,操作人,动作,对象,详情\n");
        for (Map<String, Object> r : rows) {
            sb.append(csv(r.get("created_at"))).append(',')
              .append(csv(r.get("actor"))).append(',')
              .append(csv(r.get("action"))).append(',')
              .append(csv(r.get("target"))).append(',')
              .append(csv(r.get("detail"))).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"audit.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    private static String csv(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ============================================================ 租户开通（一条流程走完）

    /**
     * 开通租户：建租户记录 → 下发命名空间与 GPU 配额 → 建租户管理员账号。
     * 企业交付里这一步最容易漏：只建了租户记录，集群侧没有 namespace，客户下单必然失败。
     */
    @PostMapping("/tenants/provision")
    public Map<String, Object> provisionTenant(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-User", required = false) String user) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            throw new RentalService.BizException("租户名称不能为空");
        }
        int quota = body.get("quotaGpus") == null ? 4 : ((Number) body.get("quotaGpus")).intValue();
        if (quota < 1 || quota > 64) {
            throw new RentalService.BizException("GPU 配额需在 1~64 卡之间");
        }
        String mode = String.valueOf(body.getOrDefault("mode", "card_hour"));
        String wantUser = String.valueOf(body.getOrDefault("username", "")).trim();

        String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String namespace = "tenant-" + id;
        BigDecimal balance = "trial".equals(mode) ? new BigDecimal("500.00") : BigDecimal.ZERO;

        String username = wantUser.isEmpty() ? id + "_admin" : wantUser;
        if (db.userByUsername(username) != null) {
            throw new RentalService.BizException("账号 " + username + " 已存在，换一个");
        }

        db.insertTenant(id, name, quota, balance, name);

        String uid = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        db.insertUser(uid, username, name + " · 管理员", "tenant", id);
        // 初始密码：满足大小写+数字+符号策略，只在开通结果里出现一次
        String pwd = "Ta@" + UUID.randomUUID().toString().replace("-", "").substring(0, 4) + "9";
        db.setPasswordHash(uid, encoder.encode(pwd));

        String nsStatus;
        try {
            k8s.createTenantNamespace(namespace, quota);
            nsStatus = "已下发命名空间与配额";
        } catch (Exception e) {
            nsStatus = "命名空间下发失败：" + e.getMessage();
        }

        db.insertAudit(user == null ? "admin" : user, "TENANT_PROVISION", name,
                "配额 " + quota + " 卡 · 命名空间 " + namespace + " · 账号 " + username);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", id);
        out.put("namespace", namespace);
        out.put("username", username);
        out.put("password", pwd);
        out.put("quotaGpus", quota);
        out.put("balance", balance);
        out.put("namespaceStatus", nsStatus);
        out.put("message", "租户已开通：" + namespace + "（" + nsStatus + "）");
        return out;
    }

    // ============================================================ 在线会话与账号

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return db.activeSessions();
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> kickSession(@PathVariable String id,
                                           @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> s = db.session(id);
        int n = db.deleteSession(id);
        if (n > 0 && s != null) {
            db.insertAudit(user == null ? "admin" : user, "SESSION_KICK",
                    String.valueOf(s.get("username")), "管理员强制下线");
        }
        return Map.of("deleted", n, "message", n > 0 ? "已强制下线该会话" : "会话不存在或已过期");
    }

    // ============================================================ 资源对账（孤儿 Pod）

    @GetMapping("/orphans")
    public Map<String, Object> orphans() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String path : k8s.listManagedPods()) {
            int idx = path.indexOf('/');
            if (idx <= 0) {
                continue;
            }
            String ns = path.substring(0, idx);
            String pod = path.substring(idx + 1);
            Map<String, Object> inst = db.instanceByPodName(pod);
            boolean orphan = inst == null || "released".equals(String.valueOf(inst.get("status")));
            if (orphan) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("namespace", ns);
                m.put("pod", pod);
                m.put("reason", inst == null ? "数据库无对应实例" : "实例已释放但 Pod 残留");
                items.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", k8s.mode());
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @DeleteMapping("/orphans/{ns}/{pod}")
    public Map<String, Object> cleanOrphan(@PathVariable String ns, @PathVariable String pod,
                                           @RequestHeader(value = "X-User", required = false) String user) {
        boolean gone = k8s.delete(ns, pod);
        if (gone) {
            db.insertAudit(user == null ? "admin" : user, "ORPHAN_CLEAN", ns + "/" + pod, "清理孤儿 Pod");
        }
        return Map.of("deleted", gone ? 1 : 0,
                "message", gone ? "已清理 " + pod : "删除未完成，Pod 可能仍在终止中，稍后重试");
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return db.usersFull();
    }

    @PostMapping("/users/{id}/status")
    public Map<String, Object> userStatus(@PathVariable String id, @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        String status = String.valueOf(body.getOrDefault("status", "active"));
        int n = db.setUserStatus(id, status);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "USER_STATUS", id, "改为 " + status);
        }
        return Map.of("changed", n, "message", n > 0 ? ("账号状态：" + status) : "账号不存在");
    }

    @PostMapping("/users/{id}/unlock")
    public Map<String, Object> unlock(@PathVariable String id,
                                      @RequestHeader(value = "X-User", required = false) String user) {
        int n = db.unlockUser(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "USER_UNLOCK", id, "解除锁定并清零失败次数");
        }
        return Map.of("changed", n, "message", n > 0 ? "已解除锁定" : "账号不存在");
    }

    // ============================================================ 节点上下线

    @PostMapping("/nodes")
    public Map<String, Object> addNode(@RequestBody Map<String, Object> body,
                                       @RequestHeader(value = "X-User", required = false) String user) {
        String hostname = String.valueOf(body.getOrDefault("hostname", "")).trim();
        String ip = String.valueOf(body.getOrDefault("ip", "")).trim();
        if (hostname.isEmpty() || ip.isEmpty()) {
            throw new RentalService.BizException("主机名与 IP 都不能为空");
        }
        if (db.node(hostname) != null) {
            throw new RentalService.BizException("节点 " + hostname + " 已存在");
        }
        String role = String.valueOf(body.getOrDefault("role", "agent"));
        String pool = String.valueOf(body.getOrDefault("poolId", "p1"));
        db.insertNode(hostname, hostname, ip, role, pool);
        db.insertAudit(user == null ? "admin" : user, "NODE_ADD", hostname, "ip=" + ip + " pool=" + pool);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", hostname);
        out.put("message", "节点已接入：" + hostname + "（自动纳管 8 张 RTX 5090）");
        out.put("joinCommand", joinCommandOf(hostname).get("command"));
        return out;
    }

    @DeleteMapping("/nodes/{id}")
    public Map<String, Object> removeNode(@PathVariable String id,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        long running = db.runningInstancesOnNode(id);
        if (running > 0) {
            throw new RentalService.BizException(
                    "缩容前置检查未通过：该节点还有 " + running + " 个运行中实例，必须先迁移或释放");
        }
        int n = db.deleteNode(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "NODE_REMOVE", id, "无运行实例，允许下线");
        }
        return Map.of("deleted", n, "message", n > 0 ? "节点已下线，显卡从可售池移除" : "节点不存在");
    }

    @PostMapping("/nodes/{id}/pool")
    public Map<String, Object> moveNodePool(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String pool = String.valueOf(body.getOrDefault("poolId", "p1"));
        int n = db.setNodePool(id, pool);
        return Map.of("changed", n, "message", n > 0 ? "节点已划入 " + pool : "节点不存在");
    }

    /** 节点 YAML 与 join 命令（ClusterApi 里也有一份，这里供新增节点复用） */
    private Map<String, Object> joinCommandOf(String nodeId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "kubeadm join 10.0.0.11:6443 \\\n  --token <TOKEN> \\\n"
                + "  --discovery-token-ca-cert-hash sha256:<CA-CERT-HASH>");
        return out;
    }

    // ============================================================ 资源池 CRUD

    @PostMapping("/pools")
    public Map<String, Object> createPool(@RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isEmpty()) {
            throw new RentalService.BizException("资源池名称不能为空");
        }
        String id = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String policy = String.valueOf(body.getOrDefault("policy", "binpack"));
        db.insertPool(id, name, policy, String.valueOf(body.getOrDefault("description", "")));
        db.insertAudit(user == null ? "admin" : user, "POOL_CREATE", name, "policy=" + policy);
        return Map.of("id", id, "message", "资源池已创建：" + name);
    }

    @PostMapping("/pools/{id}/policy")
    public Map<String, Object> setPoolPolicy(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String policy = String.valueOf(body.getOrDefault("policy", "binpack"));
        int n = db.updatePoolPolicy(id, policy);
        return Map.of("changed", n, "message", n > 0 ? "池策略改为 " + policy : "资源池不存在");
    }

    @DeleteMapping("/pools/{id}")
    public Map<String, Object> deletePool(@PathVariable String id) {
        long nodes = db.nodesInPool(id);
        if (nodes > 0) {
            throw new RentalService.BizException("该池下还有 " + nodes + " 个节点，先把节点迁走");
        }
        int n = db.deletePool(id);
        return Map.of("deleted", n, "message", n > 0 ? "资源池已删除" : "资源池不存在或有节点占用");
    }

    // ============================================================ 工作负载生命周期

    @PostMapping("/deployments/{id}/restart")
    public Map<String, Object> restart(@PathVariable String id,
                                       @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> w = db.workload(id);
        if (w == null) {
            throw new RentalService.BizException("工作负载不存在");
        }
        db.setWorkloadStatus(id, "Pending");
        db.insertAudit(user == null ? "admin" : user, "WORKLOAD_RESTART", String.valueOf(w.get("name")), "");
        return Map.of("status", "Pending", "message", "已触发重启，约 3 秒恢复 Running");
    }

    @PostMapping("/deployments/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id,
                                    @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> w = db.workload(id);
        if (w == null) {
            throw new RentalService.BizException("工作负载不存在");
        }
        db.setWorkloadStatus(id, "Stopped");
        db.insertAudit(user == null ? "admin" : user, "WORKLOAD_STOP", String.valueOf(w.get("name")), "");
        return Map.of("status", "Stopped", "message", "已停止，副本数置 0，资源释放");
    }

    /** 日志：按负载名确定性生成，同一负载每次看到的行稳定 */
    @GetMapping("/deployments/{id}/logs")
    public Map<String, Object> logs(@PathVariable String id) {
        Map<String, Object> w = db.workload(id);
        if (w == null) {
            throw new RentalService.BizException("工作负载不存在");
        }
        String name = String.valueOf(w.get("name"));
        String image = String.valueOf(w.get("image"));
        int gpu = ((Number) w.get("gpu_count")).intValue();
        long seed = Math.abs(name.hashCode());

        List<String> lines = new ArrayList<>();
        lines.add("INFO  " + name + "  Starting container runtime (containerd 1.7)");
        lines.add("INFO  " + name + "  Image: " + image);
        lines.add("INFO  " + name + "  Allocated " + gpu + " x nvidia.com/gpu");
        lines.add("INFO  " + name + "  CUDA driver 570.86  |  device count = " + gpu);
        lines.add("INFO  " + name + "  Scheduler: volcano  queue: default-queue  policy: " + w.get("policy"));
        for (int i = 0; i < 8; i++) {
            long t = (seed % 40) + i * 3L;
            int pct = (int) ((seed + i * 17) % 60) + 20;
            lines.add(String.format("INFO  %s  [%02ds] step=%d loss=%.4f gpu_util=%d%%",
                    name, t, i, 2.4 - i * 0.08, pct));
        }
        lines.add("INFO  " + name + "  Health check passed  ready=true  restarts=0");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pod", "gpu-pod-" + name);
        out.put("lines", lines);
        return out;
    }

    // ============================================================ 实例接入信息

    @GetMapping("/instances/{id}/access")
    public Map<String, Object> access(@PathVariable String id) {
        Map<String, Object> inst = db.instance(id);
        if (inst == null) {
            throw new RentalService.BizException("实例不存在");
        }
        Map<String, Object> node = db.node(String.valueOf(inst.get("node_id")));
        String ip = node == null ? "10.0.0.11" : String.valueOf(node.get("ip"));
        int port = 30000 + Math.abs(id.hashCode() % 1000);

        String uid = Integer.toHexString(id.hashCode()).toUpperCase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", id);
        out.put("status", inst.get("status"));
        out.put("node", inst.get("node_id"));
        out.put("ip", ip);
        out.put("sshPort", port);
        out.put("ssh", "ssh -p " + port + " root@" + ip);
        out.put("jupyter", "http://" + ip + ":" + port + "/lab?token=" + uid);
        out.put("publicKey", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI" + uid + " tenant@rental");
        out.put("gpuIds", inst.get("gpu_ids"));
        out.put("expireAt", inst.get("expire_at"));
        out.put("note", "演示值：真实环境由 cloud-init 注入公钥、Ingress 暴露 Jupyter。");
        return out;
    }

    @PostMapping("/instances/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable String id,
                                             @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> inst = db.instance(id);
        if (inst == null) {
            throw new RentalService.BizException("实例不存在");
        }
        String pwd = "Gp" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        db.insertAudit(user == null ? "admin" : user, "INSTANCE_RESET_PWD", id, "生成一次性密码");
        return Map.of("username", "root", "password", pwd,
                "message", "已重置密码，仅展示一次，请立即保存");
    }
}
