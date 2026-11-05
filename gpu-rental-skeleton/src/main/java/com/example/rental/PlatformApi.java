package com.example.rental;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

/** 平台能力：资源对象、告警规则、系统参数、审计、节点上下线、资源池、工作负载生命周期、实例接入 */
@RestController
@RequestMapping("/api/admin")
public class PlatformApi {

    private final Db db;
    private final K8sClient k8s;
    private final org.springframework.beans.factory.ObjectProvider<ClusterOps> clusterOps;
    private final AlertNotifier notifier;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public PlatformApi(Db db, K8sClient k8s,
                       org.springframework.beans.factory.ObjectProvider<ClusterOps> clusterOps,
                       AlertNotifier notifier) {
        this.db = db;
        this.k8s = k8s;
        this.clusterOps = clusterOps;
        this.notifier = notifier;
    }

    /** fake 模式下 Bean 不存在，返回 null，上层回退台账 */
    private ClusterOps ops() {
        try {
            return clusterOps.getIfAvailable();
        } catch (Exception e) {
            return null;
        }
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

        // real 模式：先真下发到集群，集群接受才落台账（台账是集群的镜像，不是另一份真相）
        ClusterOps ops = ops();
        Map<String, Object> clusterResult = null;
        if (ops != null) {
            try {
                clusterResult = ops.applyYaml(yaml);
            } catch (Exception e) {
                throw new RentalService.BizException("集群拒绝该对象：" + e.getMessage());
            }
        }

        String id = "rs" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.upsertResource(id, apiVersion, kind, ns, name, yaml, exists);
        db.insertAudit(user == null ? "admin" : user, exists ? "RESOURCE_UPDATE" : "RESOURCE_CREATE",
                kind + "/" + name, "namespace=" + ns + (clusterResult != null ? " appliedToCluster=true" : ""));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("name", name);
        out.put("namespace", ns);
        out.put("created", !exists);
        out.put("dataSource", ops == null ? "ledger" : "cluster");
        if (clusterResult != null) {
            out.putAll(clusterResult);
            out.put("message", "已下发集群并记录台账：" + kind + " " + name + "（命名空间 " + ns + "）");
        } else {
            out.put("appliedToCluster", false);
            out.put("message", (exists ? "已更新 " : "已创建 ") + kind + " " + name
                    + "（命名空间 " + ns + "；fake 模式仅台账，未下发集群）");
        }
        return out;
    }

    @DeleteMapping("/resources/{id}")
    public Map<String, Object> deleteResource(@PathVariable String id,
                                              @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> r = db.resource(id);
        if (r == null) {
            throw new RentalService.BizException("资源不存在");
        }
        ClusterOps ops = ops();
        boolean clusterDeleted = false;
        String clusterError = null;
        if (ops != null) {
            try {
                clusterDeleted = ops.deleteObject(String.valueOf(r.get("kind")),
                        String.valueOf(r.get("namespace")), String.valueOf(r.get("name")));
            } catch (Exception e) {
                clusterError = e.getMessage();
            }
        }
        int n = db.deleteResource(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "RESOURCE_DELETE",
                    r.get("kind") + "/" + r.get("name"),
                    clusterDeleted ? "集群与台账均已删除" : (clusterError == null ? "仅台账删除" : "集群删除失败：" + clusterError));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", n);
        out.put("dataSource", ops == null ? "ledger" : "cluster");
        out.put("clusterDeleted", clusterDeleted);
        out.put("message", (clusterError != null ? "集群删除异常：" + clusterError + "；" : "")
                + (n > 0 ? "已删除资源" : "资源不存在"));
        return out;
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
        Map<String, Object> cluster = new LinkedHashMap<>();
        ClusterOps ops = ops();
        if (ops != null) {
            try {
                Map<String, Object> ci = ops.clusterInfo();
                cluster.put("name", "prod-cluster");
                cluster.put("k8s", ci.get("k8sVersion"));
                cluster.put("masterUrl", ci.get("masterUrl"));
                cluster.put("cni", "集群实测（未探测 CNI）");
                cluster.put("runtime", "集群实测（未探测运行时）");
                cluster.put("nodes", intOf(ci.get("nodeCount")));
                cluster.put("pods", intOf(ci.get("podCount")));
                cluster.put("gpuCapacity", intOf(ci.get("gpuTotal")));
                cluster.put("scheduler", ci.get("schedulerName"));
                cluster.put("dataSource", "cluster");
            } catch (Exception e) {
                cluster.put("dataSource", "cluster");
                cluster.put("error", e.getMessage());
            }
        } else {
            cluster.put("name", "prod-cluster");
            cluster.put("k8s", "v1.34.1+k3s1（台账占位）");
            cluster.put("cni", "flannel（台账占位）");
            cluster.put("runtime", "containerd 1.7（台账占位）");
            cluster.put("nodes", db.nodesFull().size());
            cluster.put("dataSource", "ledger");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", db.settings());
        out.put("cluster", cluster);
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

    // ============================================================ 数据字典

    @GetMapping("/dicts")
    public Map<String, Object> dicts() {
        return Map.of("items", db.dicts());
    }

    @PostMapping("/dicts")
    public Map<String, Object> createDict(@RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        String category = String.valueOf(body.getOrDefault("category", "")).trim();
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        if (category.isEmpty() || code.isEmpty() || label.isEmpty()) {
            throw new RentalService.BizException("分类、编码、显示名都不能为空");
        }
        int sort = body.get("sort") == null ? 0 : ((Number) body.get("sort")).intValue();
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        String id = "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.insertDict(id, category, code, label, sort, enabled);
        db.insertAudit(user == null ? "admin" : user, "DICT_CREATE", category + "/" + code, label);
        return Map.of("id", id, "message", "字典项已创建：" + code);
    }

    @PutMapping("/dicts/{id}")
    public Map<String, Object> updateDict(@PathVariable String id, @RequestBody Map<String, Object> body,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        String category = String.valueOf(body.getOrDefault("category", "")).trim();
        String code = String.valueOf(body.getOrDefault("code", "")).trim();
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        if (category.isEmpty() || code.isEmpty() || label.isEmpty()) {
            throw new RentalService.BizException("分类、编码、显示名都不能为空");
        }
        int sort = body.get("sort") == null ? 0 : ((Number) body.get("sort")).intValue();
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        int n = db.updateDict(id, category, code, label, sort, enabled);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "DICT_UPDATE", category + "/" + code, label);
        }
        return Map.of("changed", n, "message", n > 0 ? "字典项已更新" : "字典项不存在");
    }

    @DeleteMapping("/dicts/{id}")
    public Map<String, Object> deleteDict(@PathVariable String id,
                                          @RequestHeader(value = "X-User", required = false) String user) {
        int n = db.deleteDict(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "DICT_DELETE", id, "");
        }
        return Map.of("deleted", n, "message", n > 0 ? "字典项已删除" : "字典项不存在");
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

    // ============================================================ 工单处理（管理侧）

    @GetMapping("/tickets")
    public Map<String, Object> tickets(@RequestParam(required = false) String status) {
        List<Map<String, Object>> all = db.tickets(null);
        if (status != null && !status.isBlank()) {
            all = all.stream().filter(t -> status.equals(String.valueOf(t.get("status")))).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", all);
        out.put("total", all.size());
        out.put("open", all.stream().filter(t -> "open".equals(String.valueOf(t.get("status")))).count());
        return out;
    }

    @PostMapping("/tickets/{id}/reply")
    public Map<String, Object> replyTicket(@PathVariable String id, @RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "X-User", required = false) String user) {
        String reply = String.valueOf(body.getOrDefault("reply", "")).trim();
        if (reply.isBlank()) {
            throw new RentalService.BizException("回复内容不能为空");
        }
        int n = db.replyTicket(id, reply);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "TICKET_REPLY", id, reply);
        }
        return Map.of("changed", n, "message", n > 0 ? "已回复并结单" : "工单不存在");
    }

    @PostMapping("/tickets/{id}/close")
    public Map<String, Object> closeTicket(@PathVariable String id,
                                           @RequestHeader(value = "X-User", required = false) String user) {
        int n = db.closeTicket(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "TICKET_CLOSE", id, "");
        }
        return Map.of("changed", n, "message", n > 0 ? "工单已关闭" : "工单不存在");
    }

    // ============================================================ 告警静默

    @GetMapping("/silences")
    public List<Map<String, Object>> silences() {
        return db.silences();
    }

    @PostMapping("/silences")
    public Map<String, Object> createSilence(@RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-User", required = false) String user) {
        String target = String.valueOf(body.getOrDefault("target", "")).trim();
        String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
        if (target.isBlank()) {
            throw new RentalService.BizException("静默对象不能为空");
        }
        String startsAt = String.valueOf(body.getOrDefault("startsAt", ""));
        String endsAt = String.valueOf(body.getOrDefault("endsAt", ""));
        if (startsAt.isBlank() || endsAt.isBlank()) {
            throw new RentalService.BizException("开始与结束时间都不能为空");
        }
        Timestamp start = parseTs(startsAt);
        Timestamp end = parseTs(endsAt);
        if (end.before(start)) {
            throw new RentalService.BizException("结束时间不能早于开始时间");
        }
        String id = "sl" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        db.insertSilence(id, target, reason, start, end, user == null ? "admin" : user);
        db.insertAudit(user == null ? "admin" : user, "SILENCE_CREATE", target, reason);
        return Map.of("id", id, "message", "静默已创建：" + target);
    }

    @DeleteMapping("/silences/{id}")
    public Map<String, Object> deleteSilence(@PathVariable String id,
                                             @RequestHeader(value = "X-User", required = false) String user) {
        int n = db.deleteSilence(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "SILENCE_DELETE", id, "");
        }
        return Map.of("deleted", n, "message", n > 0 ? "静默已解除" : "静默不存在");
    }

    private static Timestamp parseTs(String s) {
        String t = s.trim().replace("T", " ");
        if (t.length() == 16) {
            t += ":00";
        }
        try {
            return Timestamp.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new RentalService.BizException("时间格式应为 yyyy-MM-dd HH:mm");
        }
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

    /** 节点 YAML 与 join 命令（real 模式返回集群真实 token，fake 模式返回演示模板） */
    private Map<String, Object> joinCommandOf(String nodeId) {
        ClusterOps ops = ops();
        if (ops != null) {
            try {
                Map<String, Object> real = ops.joinCommand();
                real.put("node", nodeId);
                real.put("dataSource", "cluster");
                return real;
            } catch (Exception e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("node", nodeId);
                out.put("command", "生成失败：" + e.getMessage());
                out.put("dataSource", "cluster");
                return out;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("node", nodeId);
        out.put("command", "kubeadm join 10.0.0.11:6443 \\\n  --token <TOKEN> \\\n"
                + "  --discovery-token-ca-cert-hash sha256:<CA-CERT-HASH>");
        out.put("dataSource", "ledger");
        out.put("note", "演示值（fake 模式）");
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
        ClusterOps ops = ops();
        Map<String, Object> out = new LinkedHashMap<>();
        if (ops != null) {
            try {
                int n = ops.restartWorkload(String.valueOf(w.get("kind")),
                        String.valueOf(w.get("namespace")), String.valueOf(w.get("name")));
                out.put("deletedPods", n);
                out.put("dataSource", "cluster");
                out.put("message", "已删除 " + n + " 个 Pod，控制器将自动重建");
            } catch (Exception e) {
                throw new RentalService.BizException("集群重启失败：" + e.getMessage());
            }
        } else {
            db.setWorkloadStatus(id, "Pending");
            out.put("status", "Pending");
            out.put("dataSource", "ledger");
            out.put("message", "已触发重启（fake 模式仅台账），约 3 秒恢复 Running");
        }
        db.insertAudit(user == null ? "admin" : user, "WORKLOAD_RESTART", String.valueOf(w.get("name")), "");
        return out;
    }

    @PostMapping("/deployments/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id,
                                    @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> w = db.workload(id);
        if (w == null) {
            throw new RentalService.BizException("工作负载不存在");
        }
        ClusterOps ops = ops();
        Map<String, Object> out = new LinkedHashMap<>();
        if (ops != null) {
            try {
                int replicas = ops.scaleWorkload(String.valueOf(w.get("kind")),
                        String.valueOf(w.get("namespace")), String.valueOf(w.get("name")), 0);
                out.put("replicas", replicas);
                out.put("dataSource", "cluster");
                out.put("message", "已停止：副本数置 0，集群资源释放");
            } catch (Exception e) {
                throw new RentalService.BizException("集群停止失败：" + e.getMessage());
            }
        } else {
            out.put("status", "Stopped");
            out.put("dataSource", "ledger");
            out.put("message", "已停止（fake 模式仅台账），副本数置 0，资源释放");
        }
        db.setWorkloadStatus(id, "Stopped");
        db.insertAudit(user == null ? "admin" : user, "WORKLOAD_STOP", String.valueOf(w.get("name")), "");
        return out;
    }

    /** 日志：real 模式读 Pod 真实日志；fake 模式按负载名确定性生成，并明确标注 */
    @GetMapping("/deployments/{id}/logs")
    public Map<String, Object> logs(@PathVariable String id) {
        Map<String, Object> w = db.workload(id);
        if (w == null) {
            throw new RentalService.BizException("工作负载不存在");
        }
        ClusterOps ops = ops();
        if (ops != null) {
            List<String> pods = ops.podsOf(String.valueOf(w.get("namespace")), String.valueOf(w.get("name")));
            if (pods.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pod", null);
                out.put("dataSource", "cluster");
                out.put("lines", List.of("集群里没有该负载的 Pod（可能已被停止或未调度成功）"));
                return out;
            }
            String pod = pods.get(0);
            try {
                String text = ops.podLogs(String.valueOf(w.get("namespace")), pod, 200);
                List<String> lines = text == null || text.isBlank()
                        ? List.of("（暂无日志：Pod 可能 Pending 或刚创建）")
                        : Arrays.asList(text.split("\n"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pod", pod);
                out.put("dataSource", "cluster");
                out.put("lines", lines);
                return out;
            } catch (Exception e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pod", pod);
                out.put("dataSource", "cluster");
                out.put("lines", List.of("读取真实日志失败：" + e.getMessage()));
                return out;
            }
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
        out.put("dataSource", "fake");
        out.put("note", "fake 模式：日志为确定性生成的演示文本，非真实 Pod 输出");
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

    private static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    // ============================================================ 实名认证审核（A-28）

    @GetMapping("/realnames")
    public List<Map<String, Object>> realnames(@RequestParam(defaultValue = "pending") String status) {
        return db.realnamesByStatus(status);
    }

    @PostMapping("/realnames/{id}/review")
    public Map<String, Object> reviewRealname(@PathVariable String id,
                                              @RequestBody Map<String, Object> body,
                                              @RequestHeader(value = "X-User", required = false) String user) {
        String status = String.valueOf(body.getOrDefault("status", "approved"));
        if (!"approved".equals(status) && !"rejected".equals(status)) {
            throw new RentalService.BizException("状态只能是 approved / rejected");
        }
        int n = db.reviewRealname(id, status);
        if (n == 0) {
            throw new RentalService.BizException("认证申请不存在或已审核");
        }
        db.insertAudit(user == null ? "admin" : user, "REALNAME_REVIEW", id, status);
        return Map.of("message", "approved".equals(status) ? "已通过实名认证" : "已驳回实名认证");
    }

    // ============================================================ 学生认证审核（A-30）

    @GetMapping("/student-certs")
    public List<Map<String, Object>> studentCerts(@RequestParam(defaultValue = "pending") String status) {
        return db.studentCertsByStatus(status);
    }

    @PostMapping("/student-certs/{id}/review")
    public Map<String, Object> reviewStudentCert(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestHeader(value = "X-User", required = false) String user) {
        String status = String.valueOf(body.getOrDefault("status", "approved"));
        if (!"approved".equals(status) && !"rejected".equals(status)) {
            throw new RentalService.BizException("状态只能是 approved / rejected");
        }
        int n = db.reviewStudentCert(id, status);
        if (n == 0) {
            throw new RentalService.BizException("认证申请不存在或已审核");
        }
        if ("approved".equals(status)) {
            Map<String, Object> cert = db.one("SELECT * FROM student_certs WHERE id=?", id);
            if (cert != null) {
                db.setStudentVerified(String.valueOf(cert.get("tenant_id")), true);
            }
        }
        db.insertAudit(user == null ? "admin" : user, "STUDENT_REVIEW", id, status);
        return Map.of("message", "approved".equals(status) ? "已通过学生认证（租户享 9 折优惠）" : "已驳回学生认证");
    }

    // ============================================================ 代金券发放（A-26）

    @PostMapping("/coupons")
    public Map<String, Object> issueCoupon(@RequestBody Map<String, Object> body,
                                           @RequestHeader(value = "X-User", required = false) String user) {
        String tenantId = String.valueOf(body.getOrDefault("tenantId", ""));
        String title = String.valueOf(body.getOrDefault("title", "平台赠送券"));
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.getOrDefault("amountYuan", "0")));
        } catch (NumberFormatException e) {
            throw new RentalService.BizException("券面额格式不正确");
        }
        if (tenantId.isBlank() || amount.signum() <= 0) {
            throw new RentalService.BizException("请填写租户 ID 与券面额");
        }
        if (db.one("SELECT id FROM tenants WHERE id=?", tenantId) == null) {
            throw new RentalService.BizException("租户不存在：" + tenantId);
        }
        int days = intOf(body.get("expireDays"));
        if (days <= 0) {
            days = 30;
        }
        String id = "cp" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.insertCoupon(id, tenantId, title, amount, new Timestamp(System.currentTimeMillis() + days * 86_400_000L));
        db.insertAudit(user == null ? "admin" : user, "COUPON_ISSUE", tenantId,
                "发放 ¥" + amount.toPlainString() + " 券「" + title + "」");
        return Map.of("message", "已发放代金券 ¥" + amount.toPlainString() + " 给租户 " + tenantId, "id", id);
    }

    // ============================================================ 驱动矩阵（A-50）

    @GetMapping("/driver-matrix")
    public List<Map<String, Object>> driverMatrix() {
        return db.driverMatrix();
    }

    // ============================================================ 经营报表（A-44）

    @GetMapping("/report")
    public Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revenueByDay", db.revenueByDay(30));
        out.put("couponSummary", db.couponBalanceSummary());
        Map<String, Object> tenants = db.one("SELECT COUNT(*) AS n, SUM(balance_yuan) AS balance FROM tenants");
        out.put("tenantCount", tenants == null ? 0 : tenants.get("n"));
        out.put("tenantBalance", tenants == null ? 0 : tenants.get("balance"));
        out.put("runningInstances",
                db.one("SELECT COUNT(*) AS n FROM instances WHERE status IN ('running','provisioning','resetting')"));
        out.put("totalOrders",
                db.one("SELECT COUNT(*) AS n, SUM(amount) AS amount FROM orders"));
        return out;
    }

    // ============================================================ 租户运营：余额调整 / 标签

    /** 调整租户余额（正数充值、负数扣减）。必须写原因，落审计并通知租户。 */
    @PostMapping("/tenants/{id}/balance")
    public Map<String, Object> adjustTenantBalance(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body,
                                                   @RequestHeader(value = "X-User", required = false) String user) {
        Map<String, Object> t = db.tenant(id);
        if (t == null) {
            throw new RentalService.BizException("租户不存在: " + id);
        }
        Object amtObj = body.get("amount");
        if (amtObj == null) {
            throw new RentalService.BizException("amount 不能为空");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(amtObj).trim());
        } catch (NumberFormatException e) {
            throw new RentalService.BizException("amount 必须是数字");
        }
        if (amount.signum() == 0) {
            throw new RentalService.BizException("调整金额不能为 0");
        }
        if (amount.abs().compareTo(new BigDecimal("100000")) > 0) {
            throw new RentalService.BizException("单次调整上限 10 万元");
        }
        String reason = String.valueOf(body.getOrDefault("reason", "")).trim();
        if (reason.isEmpty()) {
            throw new RentalService.BizException("必须填写调整原因（审计要求）");
        }
        BigDecimal before = (BigDecimal) t.get("balance_yuan");
        BigDecimal after = before.add(amount);
        if (after.signum() < 0) {
            throw new RentalService.BizException("调整后余额为负，已拒绝。当前余额 " + before + " 元");
        }
        db.adjustBalance(id, amount);
        String actor = user == null ? "admin" : user;
        db.insertAudit(actor, "TENANT_BALANCE_ADJUST", id,
                (amount.signum() > 0 ? "+" : "") + amount + " 元 · " + reason + " · " + before + " → " + after);
        db.insertNotification("nb" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), id,
                "账户", "余额已调整",
                "账户余额调整 " + (amount.signum() > 0 ? "+" : "") + amount + " 元，原因：" + reason
                        + "，当前余额 " + after + " 元。");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", id);
        out.put("before", before);
        out.put("delta", amount);
        out.put("balance", after);
        out.put("message", "余额已调整：" + before + " → " + after + " 元");
        return out;
    }

    /** 设置租户标签（逗号分隔，如「重点客户,科研」），用于报表归集与筛选 */
    @PostMapping("/tenants/{id}/tags")
    public Map<String, Object> setTenantTags(@PathVariable String id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestHeader(value = "X-User", required = false) String user) {
        if (db.tenant(id) == null) {
            throw new RentalService.BizException("租户不存在: " + id);
        }
        String tags = String.valueOf(body.getOrDefault("tags", "")).trim();
        if (tags.length() > 200) {
            throw new RentalService.BizException("标签过长（上限 200 字符）");
        }
        db.updateTenantTags(id, tags);
        db.insertAudit(user == null ? "admin" : user, "TENANT_TAGS", id, tags);
        return Map.of("tenantId", id, "tags", tags, "message", "标签已更新");
    }

    // ============================================================ 告警通知渠道

    private static final java.util.Set<String> CHANNEL_TYPES =
            java.util.Set.of("dingtalk", "feishu", "wecom", "webhook");

    @GetMapping("/alert-channels")
    public List<Map<String, Object>> alertChannels() {
        return db.alertChannels();
    }

    @PostMapping("/alert-channels")
    public Map<String, Object> addAlertChannel(@RequestBody Map<String, Object> body,
                                               @RequestHeader(value = "X-User", required = false) String user) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String type = String.valueOf(body.getOrDefault("type", "")).trim().toLowerCase();
        String url = String.valueOf(body.getOrDefault("url", "")).trim();
        if (name.isEmpty()) {
            throw new RentalService.BizException("渠道名称不能为空");
        }
        if (!CHANNEL_TYPES.contains(type)) {
            throw new RentalService.BizException("渠道类型只支持 dingtalk / feishu / wecom / webhook");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new RentalService.BizException("Webhook 地址必须是 http(s) 开头");
        }
        String id = "ach" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.insertAlertChannel(id, name, type, url);
        db.insertAudit(user == null ? "admin" : user, "ALERT_CHANNEL_ADD", id, type + " · " + name);
        return Map.of("id", id, "message", "渠道已添加：" + name);
    }

    @PostMapping("/alert-channels/{id}/toggle")
    public Map<String, Object> toggleAlertChannel(@PathVariable String id, @RequestBody Map<String, Object> body,
                                                  @RequestHeader(value = "X-User", required = false) String user) {
        boolean enabled = !"false".equalsIgnoreCase(String.valueOf(body.getOrDefault("enabled", true)));
        int n = db.toggleAlertChannel(id, enabled);
        if (n == 0) {
            throw new RentalService.BizException("渠道不存在");
        }
        db.insertAudit(user == null ? "admin" : user, enabled ? "ALERT_CHANNEL_ON" : "ALERT_CHANNEL_OFF", id, "");
        return Map.of("id", id, "enabled", enabled, "message", enabled ? "已启用" : "已停用");
    }

    @DeleteMapping("/alert-channels/{id}")
    public Map<String, Object> deleteAlertChannel(@PathVariable String id,
                                                  @RequestHeader(value = "X-User", required = false) String user) {
        int n = db.deleteAlertChannel(id);
        if (n > 0) {
            db.insertAudit(user == null ? "admin" : user, "ALERT_CHANNEL_DEL", id, "");
        }
        return Map.of("deleted", n, "message", n > 0 ? "渠道已删除" : "渠道不存在");
    }

    /** 向单个渠道发一条测试消息，验证 webhook 地址与机器人配置是否可用 */
    @PostMapping("/alert-channels/{id}/test")
    public Map<String, Object> testAlertChannel(@PathVariable String id) {
        Map<String, Object> ch = db.alertChannel(id);
        if (ch == null) {
            throw new RentalService.BizException("渠道不存在");
        }
        boolean ok = notifier.send(ch, "[测试] TokenArk 告警通道连通性验证",
                "这是一条测试消息，收到即表示渠道配置可用。", "P2");
        return Map.of("ok", ok, "message", ok ? "测试消息已发送，请到群里确认" : "发送失败，检查 webhook 地址或机器人配置");
    }

    // ============================================================ MaaS：模型上下架 / 分组

    @GetMapping("/maas/models")
    public List<Map<String, Object>> maasModels() {
        Map<String, Map<String, Object>> states = db.maasModelStates();
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaasCatalog.Model m : MaasCatalog.MODELS) {
            Map<String, Object> st = states.get(m.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("display", m.display());
            row.put("inPerM", m.inPerM());
            row.put("outPerM", m.outPerM());
            row.put("status", st == null ? "online" : st.get("status"));
            row.put("group", st == null ? "通用" : st.get("grp"));
            row.put("note", st == null ? null : st.get("note"));
            out.add(row);
        }
        return out;
    }

    @PostMapping("/maas/models/{id}")
    public Map<String, Object> setMaasModel(@PathVariable String id, @RequestBody Map<String, Object> body,
                                            @RequestHeader(value = "X-User", required = false) String user) {
        if (MaasCatalog.byId(id) == null) {
            throw new RentalService.BizException("模型不存在: " + id);
        }
        Map<String, Map<String, Object>> states = db.maasModelStates();
        Map<String, Object> cur = states.get(id);
        String status = body.get("status") == null
                ? (cur == null ? "online" : String.valueOf(cur.get("status")))
                : String.valueOf(body.get("status"));
        if (!"online".equals(status) && !"offline".equals(status)) {
            throw new RentalService.BizException("status 只支持 online / offline");
        }
        String group = body.get("group") == null
                ? (cur == null ? "通用" : String.valueOf(cur.get("grp")))
                : String.valueOf(body.get("group"));
        String note = body.get("note") == null ? (cur == null ? null : (String) cur.get("note"))
                : String.valueOf(body.get("note"));
        db.setMaasModelState(id, status, group, note);
        db.insertAudit(user == null ? "admin" : user, "MAAS_MODEL_UPDATE", id, status + " · " + group);
        return Map.of("id", id, "status", status, "group", group,
                "message", "offline".equals(status) ? "已下架 " + id : "已上架 " + id);
    }

    // ============================================================ MaaS：请求日志

    @GetMapping("/maas/logs")
    public Map<String, Object> maasLogs(@RequestParam(required = false) String tenantId,
                                        @RequestParam(defaultValue = "100") int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logs", db.maasLogs(tenantId, n));
        out.put("summary", db.maasLogSummary(tenantId));
        out.put("tenantId", tenantId);
        return out;
    }
}
