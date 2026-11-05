package com.example.rental;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 所有 SQL 集中在这里。刻意不用 JPA —— 这个项目的核心是状态流转与幂等，
 * 写清楚每一条 SQL 比省几行代码重要。
 */
@Repository
public class Db {

    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 基础查询 ----------

    public List<Map<String, Object>> q(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    // ---------- 卡池 ----------

    public List<Map<String, Object>> nodes() {
        return q("SELECT * FROM nodes ORDER BY id");
    }

    public List<Map<String, Object>> gpus() {
        return q("SELECT * FROM gpus ORDER BY node_id, idx");
    }

    /**
     * 这个节点是不是真实硬件节点（labels 带 gpu.real=true）。
     * 台账里的模拟节点没有物理卡，下单时不能让它申请 nvidia.com/gpu，否则 Pod 永远 Pending。
     */
    public boolean isRealGpuNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return false;
        Map<String, Object> n = one("SELECT labels FROM nodes WHERE id=?", nodeId);
        if (n == null) return false;
        Object l = n.get("labels");
        return l != null && String.valueOf(l).contains("gpu.real=true");
    }

    /** 单卡：优先放到“已经占用最多”的节点（bin-packing，尽量留整台空机给整机订单） */
    public List<String> pickSingleGpuIds(int count) {
        return pickSingleGpuIds(count, null);
    }

    /**
     * 单卡（限定型号）：只从指定型号的空闲卡里挑。
     * 不传 model 时退化为全池挑卡，保持旧行为。
     */
    public List<String> pickSingleGpuIds(int count, String model) {
        if (model == null || model.isBlank()) {
            return q("""
                    SELECT g.id
                      FROM gpus g
                     WHERE g.status = 'free'
                     ORDER BY (SELECT COUNT(*) FROM gpus x
                                WHERE x.node_id = g.node_id AND x.status = 'occupied') DESC,
                              g.node_id, g.idx
                     LIMIT ?
                    """, count).stream().map(r -> (String) r.get("id")).toList();
        }
        return q("""
                SELECT g.id
                  FROM gpus g
                 WHERE g.status = 'free' AND g.model = ?
                 ORDER BY (SELECT COUNT(*) FROM gpus x
                            WHERE x.node_id = g.node_id AND x.status = 'occupied') DESC,
                          g.node_id, g.idx
                 LIMIT ?
                """, model, count).stream().map(r -> (String) r.get("id")).toList();
    }

    /** 整机：必须凑齐同一节点的 N 张 free 卡 */
    public Map<String, Object> pickWholeNode(int count) {
        return pickWholeNode(count, null);
    }

    /** 整机（限定型号）：同一节点上凑齐 N 张指定型号的空闲卡 */
    public Map<String, Object> pickWholeNode(int count, String model) {
        if (model == null || model.isBlank()) {
            return one("""
                    SELECT node_id, COUNT(*) AS free_cnt
                      FROM gpus
                     WHERE status = 'free'
                     GROUP BY node_id
                    HAVING COUNT(*) >= ?
                     ORDER BY node_id
                     LIMIT 1
                    """, count);
        }
        return one("""
                SELECT node_id, COUNT(*) AS free_cnt
                  FROM gpus
                 WHERE status = 'free' AND model = ?
                 GROUP BY node_id
                HAVING COUNT(*) >= ?
                 ORDER BY node_id
                 LIMIT 1
                """, model, count);
    }

    public List<String> freeGpuIdsOfNode(String nodeId, int count) {
        return q("SELECT id FROM gpus WHERE status='free' AND node_id=? ORDER BY idx LIMIT ?",
                nodeId, count).stream().map(r -> (String) r.get("id")).toList();
    }

    /** 占用：只有 status='free' 的卡才能被占，防止并发抢到同一张 */
    public int occupy(List<String> gpuIds, String instanceId) {
        int n = 0;
        for (String id : gpuIds) {
            n += update("UPDATE gpus SET status='occupied', instance_id=? WHERE id=? AND status='free'",
                    instanceId, id);
        }
        return n;
    }

    public int releaseByInstance(String instanceId) {
        return update("UPDATE gpus SET status='free', instance_id=NULL WHERE instance_id=? AND status='occupied'",
                instanceId);
    }

    public int markFault(String gpuId) {
        return update("UPDATE gpus SET status='fault' WHERE id=? AND status IN ('free','fault')", gpuId);
    }

    public int repair(String gpuId) {
        return update("UPDATE gpus SET status='free' WHERE id=? AND status='fault'", gpuId);
    }

    // ---------- 租户 ----------

    public Map<String, Object> tenant(String id) {
        return one("SELECT * FROM tenants WHERE id=?", id);
    }

    public List<Map<String, Object>> tenants() {
        return q("SELECT * FROM tenants ORDER BY id");
    }

    /** 该租户当前占用的卡数（只算未释放的实例） */
    public int occupiedGpusOfTenant(String tenantId) {
        Map<String, Object> row = one("""
                SELECT COUNT(*) AS c FROM gpus
                 WHERE status='occupied'
                   AND instance_id IN (SELECT id FROM instances WHERE tenant_id=? AND status <> 'released')
                """, tenantId);
        return row == null ? 0 : ((Number) row.get("c")).intValue();
    }

    public void deductBalance(String tenantId, BigDecimal amount) {
        update("UPDATE tenants SET balance_yuan = balance_yuan - ? WHERE id=?", amount, tenantId);
    }

    public void addBalance(String tenantId, BigDecimal amount) {
        update("UPDATE tenants SET balance_yuan = balance_yuan + ? WHERE id=?", amount, tenantId);
    }

    // ---------- 订单 ----------

    public Map<String, Object> orderByNo(String orderNo) {
        return one("SELECT * FROM orders WHERE order_no=?", orderNo);
    }

    public void insertOrder(String id, String orderNo, String tenantId, String sku,
                            int gpuCount, int hours, BigDecimal unitPrice, BigDecimal amount) {
        update("""
                INSERT INTO orders(id, order_no, tenant_id, sku, gpu_count, hours, unit_price, amount, status, created_at)
                VALUES (?,?,?,?,?,?,?,?,'created',?)
                """, id, orderNo, tenantId, sku, gpuCount, hours, unitPrice, amount,
                new Timestamp(System.currentTimeMillis()));
    }

    public Map<String, Object> orderById(String id) {
        return one("SELECT * FROM orders WHERE id=?", id);
    }

    // ---------- 实例 ----------

    public void insertInstance(String id, String orderId, String tenantId, String nodeId,
                               String gpuIds, int gpuCount, String podName, Timestamp expireAt,
                               String imageRef, int dataDiskGb) {
        update("""
                INSERT INTO instances(id, order_id, tenant_id, node_id, gpu_ids, gpu_count, pod_name,
                                      status, created_at, expire_at, used_seconds, image_ref, data_disk_gb)
                VALUES (?,?,?,?,?,?,?,'provisioning',?,?,0,?,?)
                """, id, orderId, tenantId, nodeId, gpuIds, gpuCount, podName,
                new Timestamp(System.currentTimeMillis()), expireAt, imageRef, dataDiskGb);
    }

    public Map<String, Object> instance(String id) {
        return one("SELECT * FROM instances WHERE id=?", id);
    }

    /** 带上 sku：续费估价要按实例自己的规格算，不能只看默认单卡 */
    public List<Map<String, Object>> instances(String tenantId) {
        String from = " FROM instances i LEFT JOIN orders o ON o.id = i.order_id ";
        if (tenantId == null || tenantId.isBlank()) {
            return q("SELECT i.*, o.sku AS sku" + from + "ORDER BY i.created_at DESC LIMIT 50");
        }
        return q("SELECT i.*, o.sku AS sku" + from + "WHERE i.tenant_id=? ORDER BY i.created_at DESC LIMIT 50", tenantId);
    }

    public List<Map<String, Object>> instancesByStatus(String status) {
        return q("SELECT * FROM instances WHERE status=? ORDER BY created_at", status);
    }

    public void setStatus(String id, String status) {
        update("UPDATE instances SET status=? WHERE id=?", status, id);
    }

    public void markRunning(String id) {
        update("UPDATE instances SET status='running', started_at=? WHERE id=? AND status IN ('provisioning','resetting')",
                new Timestamp(System.currentTimeMillis()), id);
    }

    public void markReleased(String id) {
        update("UPDATE instances SET status='released', released_at=? WHERE id=?", 
                new Timestamp(System.currentTimeMillis()), id);
    }

    public void addUsedSeconds(String id, long sec) {
        update("UPDATE instances SET used_seconds = used_seconds + ? WHERE id=? AND status='running'", sec, id);
    }

    // ---------- 计费 ----------

    public void insertBilling(String instanceId, String tenantId, int gpuCount, long seconds,
                              BigDecimal unitPrice, BigDecimal amount) {
        update("""
                INSERT INTO billing_records(instance_id, tenant_id, gpu_count, seconds, unit_price, amount, settled_at)
                VALUES (?,?,?,?,?,?,?)
                """, instanceId, tenantId, gpuCount, seconds, unitPrice, amount,
                new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> billing(String tenantId) {
        return q("SELECT * FROM billing_records WHERE tenant_id=? ORDER BY settled_at DESC LIMIT 100", tenantId);
    }

    public BigDecimal settledTotal(String tenantId) {
        Map<String, Object> row = one(
                "SELECT COALESCE(SUM(amount),0) AS s FROM billing_records WHERE tenant_id=?", tenantId);
        return row == null ? BigDecimal.ZERO : (BigDecimal) row.get("s");
    }

    // ---------- 节点（管理端） ----------

    public List<Map<String, Object>> nodesFull() {
        return q("SELECT * FROM nodes ORDER BY id");
    }

    public Map<String, Object> node(String id) {
        return one("SELECT * FROM nodes WHERE id=?", id);
    }

    public int setNodeStatus(String id, String status) {
        return update("UPDATE nodes SET status=? WHERE id=?", status, id);
    }

    // ---------- 资源池与队列 ----------

    public List<Map<String, Object>> pools() {
        return q("""
                SELECT p.*,
                       (SELECT COUNT(*) FROM nodes n WHERE n.pool_id = p.id) AS node_count,
                       (SELECT COUNT(*) FROM gpus g JOIN nodes n ON n.id = g.node_id
                         WHERE n.pool_id = p.id) AS gpu_total,
                       (SELECT COUNT(*) FROM gpus g JOIN nodes n ON n.id = g.node_id
                         WHERE n.pool_id = p.id AND g.status = 'occupied') AS gpu_used
                  FROM pools p ORDER BY p.id
                """);
    }

    public List<Map<String, Object>> queues() {
        return q("""
                SELECT q.*,
                       (SELECT COUNT(*) FROM gpus g JOIN nodes n ON n.id = g.node_id
                         WHERE n.pool_id = q.pool_id AND g.status = 'occupied') AS gpu_used
                  FROM queues q ORDER BY q.priority DESC, q.id
                """);
    }

    // ---------- 告警 ----------

    public List<Map<String, Object>> alerts() {
        return q("SELECT * FROM alerts ORDER BY created_at DESC");
    }

    public int ackAlert(String id) {
        return update("UPDATE alerts SET status='acked' WHERE id=? AND status='active'", id);
    }

    public int resolveAlert(String id) {
        return update("UPDATE alerts SET status='resolved' WHERE id=? AND status <> 'resolved'", id);
    }

    public void insertAlert(String id, String level, String title, String source, String target, String detail) {
        update("""
                INSERT INTO alerts(id, level, title, source, target, status, detail, created_at)
                VALUES (?,?,?,?,?,'active',?,?)
                """, id, level, title, source, target, detail, new Timestamp(System.currentTimeMillis()));
    }

    public Map<String, Object> alertById(String id) {
        return one("SELECT * FROM alerts WHERE id=?", id);
    }

    /** 同一规则命中同一对象时，已有一条活跃告警就不再重复产生 */
    public Map<String, Object> activeAlert(String title, String target) {
        return one("SELECT id FROM alerts WHERE title=? AND target=? AND status='active' LIMIT 1", title, target);
    }

    public int setAlertDiagnosis(String id, String diagnosis) {
        return update("UPDATE alerts SET diagnosis=? WHERE id=?", diagnosis, id);
    }

    /** 静默期内命中不告警：静默可针对对象 ID 或规则名 */
    public boolean silenced(String target, String ruleName) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return one("""
                SELECT id FROM alert_silences
                 WHERE (target=? OR target=?) AND starts_at<=? AND ends_at>=?
                 LIMIT 1
                """, target, ruleName, now, now) != null;
    }

    // ---------- 巡检 ----------

    public List<Map<String, Object>> inspections() {
        return q("SELECT * FROM inspections ORDER BY run_at DESC LIMIT 20");
    }

    public List<Map<String, Object>> inspectionItems(String inspectionId) {
        return q("SELECT * FROM inspection_items WHERE inspection_id=? ORDER BY id", inspectionId);
    }

    public void insertInspection(String id, int score, int issues, String summary) {
        update("INSERT INTO inspections(id, run_at, health_score, total_issues, summary) VALUES (?,?,?,?,?)",
                id, new Timestamp(System.currentTimeMillis()), score, issues, summary);
    }

    public void insertInspectionItem(String inspectionId, String level, String item,
                                     String target, String suggestion, boolean fixed) {
        update("""
                INSERT INTO inspection_items(inspection_id, level, item, target, suggestion, fixed)
                VALUES (?,?,?,?,?,?)
                """, inspectionId, level, item, target, suggestion, fixed);
    }

    public List<Map<String, Object>> inspectionItemsUnfixed(String inspectionId) {
        return q("SELECT * FROM inspection_items WHERE inspection_id=? AND fixed=FALSE ORDER BY id", inspectionId);
    }

    public int setInspectionItemDiagnosis(long id, String diagnosis) {
        return update("UPDATE inspection_items SET diagnosis=? WHERE id=?", diagnosis, id);
    }

    public int setInspectionItemTicket(long id, String ticketId) {
        return update("UPDATE inspection_items SET ticket_id=? WHERE id=?", ticketId, id);
    }

    // ---------- 租户（管理端） ----------

    public void insertTenant(String id, String name, int quota, BigDecimal balance, String owner) {
        update("""
                INSERT INTO tenants(id, name, quota_gpus, balance_yuan, status, owner, created_at)
                VALUES (?,?,?,?,'active',?,?)
                """, id, name, quota, balance, owner, new Timestamp(System.currentTimeMillis()));
    }

    public int updateTenantQuota(String id, int quota) {
        return update("UPDATE tenants SET quota_gpus=? WHERE id=?", quota, id);
    }

    public int updateTenantStatus(String id, String status) {
        return update("UPDATE tenants SET status=? WHERE id=?", status, id);
    }

    // ---------- 实例（管理端，带租户名） ----------

    public List<Map<String, Object>> allInstances() {
        return q("""
                SELECT i.*, t.name AS tenant_name
                  FROM instances i LEFT JOIN tenants t ON t.id = i.tenant_id
                 ORDER BY i.created_at DESC LIMIT 100
                """);
    }

    public List<Map<String, Object>> users() {
        return q("SELECT * FROM users ORDER BY id");
    }

    // ---------- 工作负载（部署向导） ----------

    public List<Map<String, Object>> workloads() {
        return q("""
                SELECT w.*, t.name AS tenant_name
                  FROM workloads w LEFT JOIN tenants t ON t.id = w.tenant_id
                 ORDER BY w.created_at DESC
                """);
    }

    public void insertWorkload(String id, String name, String kind, String namespace,
                              String image, int gpuCount, String memLimit, String policy,
                              String tenantId) {
        update("""
                INSERT INTO workloads(id, name, kind, namespace, image, gpu_count, mem_limit, policy, status, tenant_id, created_at)
                VALUES (?,?,?,?,?,?,?,?,'Pending',?,?)
                """, id, name, kind, namespace, image, gpuCount, memLimit, policy,
                tenantId, new Timestamp(System.currentTimeMillis()));
    }

    /** 模拟调度器：创建 3 秒后 Pending → Running */
    public int promoteWorkloads() {
        return update("UPDATE workloads SET status='Running' WHERE status='Pending' AND created_at < ?",
                new Timestamp(System.currentTimeMillis() - 3000));
    }

    public int deleteWorkload(String id) {
        return update("DELETE FROM workloads WHERE id=?", id);
    }

    public Map<String, Object> workload(String id) {
        return one("SELECT * FROM workloads WHERE id=?", id);
    }

    public int setWorkloadStatus(String id, String status) {
        return update("UPDATE workloads SET status=? WHERE id=?", status, id);
    }

    // ---------- 资源对象（YAML） ----------

    public List<Map<String, Object>> resources() {
        return q("SELECT id, api_version, kind, namespace, name, created_at FROM resources ORDER BY created_at DESC");
    }

    public Map<String, Object> resource(String id) {
        return one("SELECT * FROM resources WHERE id=?", id);
    }

    public Map<String, Object> resourceByKey(String kind, String namespace, String name) {
        return one("SELECT * FROM resources WHERE kind=? AND namespace=? AND name=?", kind, namespace, name);
    }

    public void upsertResource(String id, String apiVersion, String kind, String namespace,
                               String name, String yaml, boolean updateExisting) {
        if (updateExisting) {
            update("UPDATE resources SET api_version=?, yaml_text=?, created_at=? WHERE kind=? AND namespace=? AND name=?",
                    apiVersion, yaml, new Timestamp(System.currentTimeMillis()), kind, namespace, name);
        } else {
            update("""
                    INSERT INTO resources(id, api_version, kind, namespace, name, yaml_text, created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, id, apiVersion, kind, namespace, name, yaml, new Timestamp(System.currentTimeMillis()));
        }
    }

    public int deleteResource(String id) {
        return update("DELETE FROM resources WHERE id=?", id);
    }

    // ---------- 告警规则 ----------

    public List<Map<String, Object>> alertRules() {
        return q("SELECT * FROM alert_rules ORDER BY level, id");
    }

    public void insertAlertRule(String id, String name, String target, String expr, String level, String notify, boolean enabled) {
        update("INSERT INTO alert_rules(id, name, target, expr, level, notify, enabled) VALUES (?,?,?,?,?,?,?)",
                id, name, target, expr, level, notify, enabled);
    }

    public int toggleAlertRule(String id, boolean enabled) {
        return update("UPDATE alert_rules SET enabled=? WHERE id=?", enabled, id);
    }

    public int deleteAlertRule(String id) {
        return update("DELETE FROM alert_rules WHERE id=?", id);
    }

    // ---------- 系统参数 ----------

    public List<Map<String, Object>> settings() {
        return q("SELECT * FROM settings ORDER BY k");
    }

    public String setting(String k) {
        Map<String, Object> row = one("SELECT v FROM settings WHERE k=?", k);
        return row == null ? null : String.valueOf(row.get("v"));
    }

    public int updateSetting(String k, String v) {
        return update("UPDATE settings SET v=? WHERE k=?", v, k);
    }

    // ---------- 数据字典 ----------

    public List<Map<String, Object>> dicts() {
        return q("SELECT * FROM dict_items ORDER BY category, sort, code");
    }

    public void insertDict(String id, String category, String code, String label, int sort, boolean enabled) {
        update("INSERT INTO dict_items(id, category, code, label, sort, enabled) VALUES (?,?,?,?,?,?)",
                id, category, code, label, sort, enabled);
    }

    public int updateDict(String id, String category, String code, String label, int sort, boolean enabled) {
        return update("UPDATE dict_items SET category=?, code=?, label=?, sort=?, enabled=? WHERE id=?",
                category, code, label, sort, enabled, id);
    }

    public int deleteDict(String id) {
        return update("DELETE FROM dict_items WHERE id=?", id);
    }

    // ---------- 审计日志 ----------

    public List<Map<String, Object>> auditLogs() {
        return q("SELECT * FROM audit_logs ORDER BY created_at DESC LIMIT 100");
    }

    /** 审计查询：关键字 + 动作筛选 + 分页 */
    public List<Map<String, Object>> auditLogs(String keyword, String action, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = auditWhere(keyword, action, args);
        args.add(limit);
        args.add(offset);
        return q("SELECT * FROM audit_logs" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?", args.toArray());
    }

    public int auditCount(String keyword, String action) {
        List<Object> args = new ArrayList<>();
        String where = auditWhere(keyword, action, args);
        return ((Number) q("SELECT COUNT(*) AS c FROM audit_logs" + where, args.toArray()).get(0).get("c")).intValue();
    }

    /** 拼 WHERE 并同步收集参数，避免 count 与 list 两处逻辑漂移 */
    private String auditWhere(String keyword, String action, List<Object> args) {
        StringBuilder w = new StringBuilder(" WHERE 1=1");
        if (keyword != null && !keyword.isBlank()) {
            w.append(" AND (LOWER(actor) LIKE ? OR LOWER(action) LIKE ? OR LOWER(target) LIKE ? OR LOWER(detail) LIKE ?)");
            String like = "%" + keyword.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (action != null && !action.isBlank()) {
            w.append(" AND action=?");
            args.add(action);
        }
        return w.toString();
    }

    public List<String> auditActions() {
        return q("SELECT DISTINCT action FROM audit_logs ORDER BY action")
                .stream().map(r -> String.valueOf(r.get("action"))).toList();
    }

    public void insertAudit(String actor, String action, String target, String detail) {
        update("INSERT INTO audit_logs(actor, action, target, detail, created_at) VALUES (?,?,?,?,?)",
                actor, action, target, detail, new Timestamp(System.currentTimeMillis()));
    }

    // ---------- 节点上下线 ----------

    public void insertNode(String id, String hostname, String ip, String role, String poolId) {
        update("""
                INSERT INTO nodes(id, hostname, ip, status, role, cpu_cores, mem_gb, disk_gb,
                                  k8s_version, pod_count, gpu_count, pool_id, labels, taints)
                VALUES (?,?,?,'Ready',?,64,512,3800,'v1.34.1+k3s1',0,8,?,?, '')
                """, id, hostname, ip, role, poolId, "gpu.model=rtx5090,node.role=" + role + ",zone=rack-b");
        for (int i = 1; i <= 8; i++) {
            update("""
                    INSERT INTO gpus(id, node_id, idx, model, mem_gb, status, util_pct, temp_c, power_w)
                    VALUES (?,?,?,'RTX 5090',32,'free',0,44,36)
                    """, id + "-" + i, id, i);
        }
    }

    public int deleteNode(String id) {
        update("DELETE FROM gpus WHERE node_id=?", id);
        return update("DELETE FROM nodes WHERE id=?", id);
    }

    public int setNodePool(String id, String poolId) {
        return update("UPDATE nodes SET pool_id=? WHERE id=?", poolId, id);
    }

    public long runningInstancesOnNode(String nodeId) {
        return q("SELECT COUNT(*) AS c FROM instances WHERE node_id=? AND status IN ('running','provisioning')", nodeId)
                .stream().mapToLong(r -> ((Number) r.get("c")).longValue()).sum();
    }

    // ---------- 资源池 CRUD ----------

    public void insertPool(String id, String name, String policy, String desc) {
        update("INSERT INTO pools(id, name, policy, description) VALUES (?,?,?,?)", id, name, policy, desc);
    }

    public int updatePoolPolicy(String id, String policy) {
        return update("UPDATE pools SET policy=? WHERE id=?", policy, id);
    }

    public int deletePool(String id) {
        return update("DELETE FROM pools WHERE id=? AND NOT EXISTS (SELECT 1 FROM nodes n WHERE n.pool_id=?)", id, id);
    }

    public long nodesInPool(String poolId) {
        return q("SELECT COUNT(*) AS c FROM nodes WHERE pool_id=?", poolId)
                .stream().mapToLong(r -> ((Number) r.get("c")).longValue()).sum();
    }

    // ---------- 认证：用户 ----------

    public Map<String, Object> userByUsername(String username) {
        return one("SELECT * FROM users WHERE username=?", username);
    }

    public Map<String, Object> userById(String id) {
        return one("SELECT * FROM users WHERE id=?", id);
    }

    public void insertUser(String id, String username, String displayName, String role, String tenantId) {
        update("""
                INSERT INTO users(id, username, display_name, role, tenant_id, status, created_at)
                VALUES (?,?,?,?,?,'active',?)
                """, id, username, displayName, role, tenantId,
                new Timestamp(System.currentTimeMillis()));
    }

    public void setPasswordHash(String id, String hash) {
        update("UPDATE users SET password_hash=? WHERE id=?", hash, id);
    }

    public int onLoginSuccess(String id) {
        return update("""
                UPDATE users SET fail_count=0, locked_until=NULL, last_login_at=?
                 WHERE id=?
                """, new Timestamp(System.currentTimeMillis()), id);
    }

    public int onLoginFailure(String id) {
        return update("UPDATE users SET fail_count = COALESCE(fail_count,0) + 1 WHERE id=?", id);
    }

    public int lockUser(String id, Timestamp until) {
        return update("UPDATE users SET locked_until=? WHERE id=?", until, id);
    }

    public int setUserStatus(String id, String status) {
        return update("UPDATE users SET status=? WHERE id=?", status, id);
    }

    public int unlockUser(String id) {
        return update("UPDATE users SET fail_count=0, locked_until=NULL WHERE id=?", id);
    }

    public int updatePassword(String id, String hash) {
        return update("UPDATE users SET password_hash=? WHERE id=?", hash, id);
    }

    public List<Map<String, Object>> usersFull() {
        return q("""
                SELECT id, username, display_name, role, tenant_id, status,
                       fail_count, locked_until, last_login_at
                  FROM users ORDER BY id
                """);
    }

    // ---------- 认证：会话 ----------

    public void insertSession(String token, String userId, String username, String role,
                              String tenantId, String ip, String ua, Timestamp expireAt) {
        long now = System.currentTimeMillis();
        update("""
                INSERT INTO sessions(id, user_id, username, role, tenant_id, ip, user_agent,
                                     created_at, last_seen, expire_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, token, userId, username, role, tenantId, ip, ua,
                new Timestamp(now), new Timestamp(now), expireAt);
    }

    public Map<String, Object> session(String token) {
        return one("SELECT * FROM sessions WHERE id=?", token);
    }

    public int touchSession(String token) {
        return update("UPDATE sessions SET last_seen=? WHERE id=?",
                new Timestamp(System.currentTimeMillis()), token);
    }

    public int deleteSession(String token) {
        return update("DELETE FROM sessions WHERE id=?", token);
    }

    public int purgeExpiredSessions() {
        return update("DELETE FROM sessions WHERE expire_at < ?",
                new Timestamp(System.currentTimeMillis()));
    }

    /** 同一账号最多保留 keep 个活跃会话，超出的按最后活动时间踢掉最旧的 */
    public int trimSessions(String userId, int keep) {
        return update("""
                DELETE FROM sessions
                 WHERE user_id = ?
                   AND id NOT IN (
                     SELECT id FROM sessions WHERE user_id = ? ORDER BY last_seen DESC LIMIT ?
                   )
                """, userId, userId, keep);
    }

    public Map<String, Object> instanceByPodName(String podName) {
        return one("SELECT * FROM instances WHERE pod_name=? ORDER BY created_at DESC LIMIT 1", podName);
    }

    /** 近 N 天按天汇总的消费额 */
    public List<Map<String, Object>> billingDaily(String tenantId, int days) {
        return q("""
                SELECT CAST(settled_at AS DATE) AS d, SUM(amount) AS amount
                  FROM billing_records
                 WHERE tenant_id = ? AND settled_at >= ?
                 GROUP BY CAST(settled_at AS DATE)
                 ORDER BY d
                """, tenantId, new Timestamp(System.currentTimeMillis() - days * 86400_000L));
    }

    public List<Map<String, Object>> ordersOf(String tenantId) {        if (tenantId == null || tenantId.isBlank()) {
            return q("SELECT * FROM orders ORDER BY created_at DESC LIMIT 20");
        }
        return q("SELECT * FROM orders WHERE tenant_id=? ORDER BY created_at DESC LIMIT 20", tenantId);
    }

    public List<Map<String, Object>> activeSessions() {
        return q("""
                SELECT s.*, u.display_name
                  FROM sessions s LEFT JOIN users u ON u.id = s.user_id
                 WHERE s.expire_at > ?
                 ORDER BY s.last_seen DESC
                """, new Timestamp(System.currentTimeMillis()));
    }

    // ---------- API 密钥 ----------

    public List<Map<String, Object>> apiKeys(String tenantId) {
        return q("SELECT id, tenant_id, name, prefix, status, created_at, last_used_at FROM api_keys WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public void insertApiKey(String id, String tenantId, String name, String prefix, String keyHash) {
        update("INSERT INTO api_keys(id, tenant_id, name, prefix, key_hash, status, created_at) VALUES (?,?,?,?,?,'on',?)",
                id, tenantId, name, prefix, keyHash, new Timestamp(System.currentTimeMillis()));
    }

    public int setApiKeyStatus(String id, String tenantId, String status) {
        return update("UPDATE api_keys SET status=? WHERE id=? AND tenant_id=?", status, id, tenantId);
    }

    public int deleteApiKey(String id, String tenantId) {
        return update("DELETE FROM api_keys WHERE id=? AND tenant_id=?", id, tenantId);
    }

    /** MaaS 网关调用时按前缀 + 哈希校验并记录最近使用（Batch H 使用） */
    public Map<String, Object> apiKeyByPrefix(String prefix) {
        return one("SELECT * FROM api_keys WHERE prefix=? AND status='on'", prefix);
    }

    public void touchApiKey(String id) {
        update("UPDATE api_keys SET last_used_at=? WHERE id=?", new Timestamp(System.currentTimeMillis()), id);
    }

    /** MaaS 网关按完整密钥的 SHA-256 哈希校验（只认启用状态） */
    public Map<String, Object> apiKeyByHash(String keyHash) {
        return one("SELECT * FROM api_keys WHERE key_hash=? AND status='on'", keyHash);
    }

    public void insertMaaSUsage(String id, String tenantId, String apiKeyId, String model,
                                int promptTokens, int completionTokens, BigDecimal amountYuan) {
        update("""
                INSERT INTO maas_usage(id, tenant_id, api_key_id, model, prompt_tokens, completion_tokens, amount_yuan, created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, tenantId, apiKeyId, model, promptTokens, completionTokens, amountYuan,
                new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> maasUsage(String tenantId) {
        return q("""
                SELECT id, api_key_id, model, prompt_tokens, completion_tokens, amount_yuan, created_at
                  FROM maas_usage WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100
                """, tenantId);
    }

    // ---------- 本机 GPU 采集 agent ----------

    /** 写一条 agent 快照 + 每张卡一行；只保留最近 48 小时 */
    public void insertAgentSnapshot(String id, String hostname, String os, String osVersion,
                                    String cpuModel, Integer cpuCores, Double cpuPercent,
                                    Double memTotalGb, Double memUsedGb,
                                    Double diskTotalGb, Double diskUsedGb, Long uptimeSeconds,
                                    List<Map<String, Object>> gpus) {
        update("""
                INSERT INTO agent_snapshots(id, hostname, os, os_version, cpu_model, cpu_cores, cpu_percent,
                                            mem_total_gb, mem_used_gb, disk_total_gb, disk_used_gb,
                                            uptime_seconds, collected_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, hostname, os, osVersion, cpuModel, cpuCores, cpuPercent,
                memTotalGb, memUsedGb, diskTotalGb, diskUsedGb, uptimeSeconds,
                new Timestamp(System.currentTimeMillis()));
        for (Map<String, Object> g : gpus) {
            update("""
                    INSERT INTO agent_gpus(snapshot_id, idx, name, uuid, util, mem_total_mb, mem_used_mb, temp_c, power_w)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, id, g.get("idx"), g.get("name"), g.get("uuid"), g.get("util"),
                    g.get("memTotalMb"), g.get("memUsedMb"), g.get("tempC"), g.get("powerW"));
        }
        update("DELETE FROM agent_snapshots WHERE collected_at < ?",
                new Timestamp(System.currentTimeMillis() - 48L * 3600_000L));
    }

    public List<Map<String, Object>> agentHosts() {
        return q("""
                SELECT hostname, MAX(collected_at) AS last_seen, COUNT(*) AS snapshots
                  FROM agent_snapshots GROUP BY hostname ORDER BY last_seen DESC
                """);
    }

    public List<Map<String, Object>> agentLatest(String hostname) {
        return q("SELECT * FROM agent_snapshots WHERE hostname=? ORDER BY collected_at DESC LIMIT 1", hostname);
    }

    public List<Map<String, Object>> agentGpus(String snapshotId) {
        return q("SELECT * FROM agent_gpus WHERE snapshot_id=? ORDER BY idx", snapshotId);
    }

    /** 近 N 分钟每分钟一条：各卡平均利用率 / 显存占用率 / 功耗 */
    public List<Map<String, Object>> agentSeries(int minutes) {
        return q("""
                SELECT s.collected_at AS ts,
                       (SELECT AVG(g.util) FROM agent_gpus g WHERE g.snapshot_id = s.id) AS util,
                       (SELECT AVG(g.mem_used_mb * 100.0 / NULLIF(g.mem_total_mb, 0)) FROM agent_gpus g WHERE g.snapshot_id = s.id) AS mem,
                       (SELECT AVG(g.power_w) FROM agent_gpus g WHERE g.snapshot_id = s.id) AS power
                  FROM agent_snapshots s
                 WHERE s.collected_at > ?
                 ORDER BY s.collected_at
                """, new Timestamp(System.currentTimeMillis() - minutes * 60_000L));
    }

    // ---------- 消息通知 ----------

    public List<Map<String, Object>> notifications(String tenantId) {
        return q("SELECT * FROM notifications WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100", tenantId);
    }

    public void insertNotification(String id, String tenantId, String type, String title, String body) {
        update("INSERT INTO notifications(id, tenant_id, type, title, body, is_read, created_at) VALUES (?,?,?,?,?,FALSE,?)",
                id, tenantId, type, title, body, new Timestamp(System.currentTimeMillis()));
    }

    public int markNotificationRead(String id, String tenantId) {
        return update("UPDATE notifications SET is_read=TRUE WHERE id=? AND tenant_id=?", id, tenantId);
    }

    public int markAllNotificationsRead(String tenantId) {
        return update("UPDATE notifications SET is_read=TRUE WHERE tenant_id=? AND is_read=FALSE", tenantId);
    }

    // ---------- 子账号（复用 users 表，role='sub'） ----------

    public List<Map<String, Object>> subaccounts(String tenantId) {
        return q("SELECT id, username, display_name, status, created_at FROM users WHERE tenant_id=? AND role='sub' ORDER BY created_at DESC", tenantId);
    }

    public int deleteSubaccount(String id, String tenantId) {
        return update("DELETE FROM users WHERE id=? AND tenant_id=? AND role='sub'", id, tenantId);
    }

    // ---------- 镜像 ----------

    public List<Map<String, Object>> images(String tenantId) {
        return q("SELECT * FROM images WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public void insertImage(String id, String tenantId, String name, String base,
                            BigDecimal sizeGb, String zone, String uuid) {
        update("INSERT INTO images(id, tenant_id, name, base, size_gb, zone, uuid, status, created_at) VALUES (?,?,?,?,?,?,?,'available',?)",
                id, tenantId, name, base, sizeGb, zone, uuid, new Timestamp(System.currentTimeMillis()));
    }

    public int deleteImage(String id, String tenantId) {
        return update("DELETE FROM images WHERE id=? AND tenant_id=?", id, tenantId);
    }

    // ---------- 文件存储 ----------

    public List<Map<String, Object>> storageItems(String tenantId) {
        return q("SELECT * FROM storage_items WHERE tenant_id=? ORDER BY is_dir DESC, name", tenantId);
    }

    public void insertStorageItem(String id, String tenantId, String zone, String name, boolean isDir, BigDecimal sizeGb) {
        update("INSERT INTO storage_items(id, tenant_id, zone, name, is_dir, size_gb, updated_at) VALUES (?,?,?,?,?,?,?)",
                id, tenantId, zone, name, isDir, sizeGb, new Timestamp(System.currentTimeMillis()));
    }

    public int deleteStorageItem(String id, String tenantId) {
        return update("DELETE FROM storage_items WHERE id=? AND tenant_id=?", id, tenantId);
    }

    public BigDecimal storageUsedGb(String tenantId) {
        Map<String, Object> row = one("SELECT COALESCE(SUM(size_gb),0) AS s FROM storage_items WHERE tenant_id=?", tenantId);
        return row == null ? BigDecimal.ZERO : (BigDecimal) row.get("s");
    }

    // ---------- 充值 ----------

    public List<Map<String, Object>> recharges(String tenantId) {
        return q("SELECT * FROM recharges WHERE tenant_id=? ORDER BY created_at DESC LIMIT 100", tenantId);
    }

    public void insertRecharge(String id, String tenantId, String orderNo, BigDecimal amount, String channel) {
        update("INSERT INTO recharges(id, tenant_id, order_no, amount, channel, status, created_at) VALUES (?,?,?,?,?,'paid',?)",
                id, tenantId, orderNo, amount, channel, new Timestamp(System.currentTimeMillis()));
    }

    // ---------- 发票 ----------

    public List<Map<String, Object>> invoices(String tenantId) {
        return q("SELECT * FROM invoices WHERE tenant_id=? ORDER BY period DESC", tenantId);
    }

    public Map<String, Object> invoice(String tenantId, String period) {
        return one("SELECT * FROM invoices WHERE tenant_id=? AND period=?", tenantId, period);
    }

    public void insertInvoice(String id, String tenantId, String period, BigDecimal amount) {
        update("INSERT INTO invoices(id, tenant_id, period, amount, status, created_at) VALUES (?,?,?,?,'draft',?)",
                id, tenantId, period, amount, new Timestamp(System.currentTimeMillis()));
    }

    public int issueInvoice(String id, String tenantId) {
        return update("UPDATE invoices SET status='issued', issued_at=? WHERE id=? AND tenant_id=? AND status='draft'",
                new Timestamp(System.currentTimeMillis()), id, tenantId);
    }

    // ---------- 工单 ----------

    public List<Map<String, Object>> tickets(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return q("""
                    SELECT t.*, tn.name AS tenant_name
                      FROM tickets t LEFT JOIN tenants tn ON tn.id = t.tenant_id
                     ORDER BY t.updated_at DESC
                    """);
        }
        return q("SELECT * FROM tickets WHERE tenant_id=? ORDER BY updated_at DESC", tenantId);
    }

    public void insertTicket(String id, String tenantId, String subject, String content) {
        update("INSERT INTO tickets(id, tenant_id, subject, content, status, created_at, updated_at) VALUES (?,?,?,?,'open',?,?)",
                id, tenantId, subject, content,
                new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis()));
    }

    public Map<String, Object> ticket(String id) {
        return one("SELECT * FROM tickets WHERE id=?", id);
    }

    public int replyTicket(String id, String reply) {
        return update("UPDATE tickets SET reply=?, status='closed', updated_at=? WHERE id=?",
                reply, new Timestamp(System.currentTimeMillis()), id);
    }

    public int closeTicket(String id) {
        return update("UPDATE tickets SET status='closed', updated_at=? WHERE id=?", new Timestamp(System.currentTimeMillis()), id);
    }

    // ---------- 告警静默 ----------

    public List<Map<String, Object>> silences() {
        return q("SELECT * FROM alert_silences ORDER BY ends_at DESC");
    }

    public void insertSilence(String id, String target, String reason, Timestamp startsAt, Timestamp endsAt, String createdBy) {
        update("INSERT INTO alert_silences(id, target, reason, starts_at, ends_at, created_by, created_at) VALUES (?,?,?,?,?,?,?)",
                id, target, reason, startsAt, endsAt, createdBy, new Timestamp(System.currentTimeMillis()));
    }

    public int deleteSilence(String id) {
        return update("DELETE FROM alert_silences WHERE id=?", id);
    }

    // ---------- 到货提醒 ----------

    public Map<String, Object> stockAlertByTenantModel(String tenantId, String model) {
        return one("SELECT * FROM stock_alerts WHERE tenant_id=? AND model=? AND status='open'", tenantId, model);
    }

    /** 同一机型重复登记时，以最新一次填的地区与阈值为准 */
    public void updateStockAlertConfig(String id, String zone, int qty) {
        update("UPDATE stock_alerts SET zone=?, qty=? WHERE id=?", zone, qty, id);
    }

    public void insertStockAlert(String id, String tenantId, String model, String zone, int qty) {
        update("INSERT INTO stock_alerts(id, tenant_id, model, zone, qty, status, created_at) VALUES (?,?,?,?,?,'open',?)",
                id, tenantId, model, zone, qty, new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> openStockAlerts(String model) {
        return q("SELECT * FROM stock_alerts WHERE model=? AND status='open'", model);
    }

    public int fulfillStockAlert(String id) {
        return update("UPDATE stock_alerts SET status='fulfilled', fulfilled_at=? WHERE id=? AND status='open'",
                new Timestamp(System.currentTimeMillis()), id);
    }

    public List<Map<String, Object>> stockAlerts(String status) {
        if (status == null || status.isBlank()) {
            return q("SELECT * FROM stock_alerts ORDER BY created_at DESC");
        }
        return q("SELECT * FROM stock_alerts WHERE status=? ORDER BY created_at DESC", status);
    }

    // ============ 全链路批次：拼团 / 商务线索 / 订阅推送 ============

    // ---------- 拼团 ----------

    public void insertGroupJoin(String id, String cardKey, String model, int vram, String zone,
                                String tenantId, int qty, BigDecimal price) {
        update("INSERT INTO group_joins(id, card_key, model, vram, zone, tenant_id, qty, price_yuan, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,'forming',?)",
                id, cardKey, model, vram, zone, tenantId, qty, price, new Timestamp(System.currentTimeMillis()));
    }

    public Map<String, Object> groupJoin(String id) {
        return one("SELECT * FROM group_joins WHERE id=?", id);
    }

    /** 成团进度口径：forming + formed 都算已占坑（cancelled / failed 不算） */
    public List<Map<String, Object>> activeJoinsOfCard(String cardKey) {
        return q("SELECT * FROM group_joins WHERE card_key=? AND status IN ('forming','formed')", cardKey);
    }

    public List<Map<String, Object>> formingJoinsOfCard(String cardKey) {
        return q("SELECT * FROM group_joins WHERE card_key=? AND status='forming' ORDER BY created_at", cardKey);
    }

    public Map<String, Object> formingJoinByTenantCard(String tenantId, String cardKey) {
        return one("SELECT * FROM group_joins WHERE tenant_id=? AND card_key=? AND status='forming'", tenantId, cardKey);
    }

    public List<Map<String, Object>> myGroupJoins(String tenantId) {
        return q("SELECT * FROM group_joins WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public List<Map<String, Object>> groupJoins(String status) {
        if (status == null || status.isBlank()) {
            return q("SELECT * FROM group_joins ORDER BY created_at DESC");
        }
        return q("SELECT * FROM group_joins WHERE status=? ORDER BY created_at DESC", status);
    }

    public int setGroupJoinStatus(String id, String status, String instanceId, String failReason) {
        return update("UPDATE group_joins SET status=?, instance_id=?, fail_reason=?, updated_at=? WHERE id=?",
                status, instanceId, failReason, new Timestamp(System.currentTimeMillis()), id);
    }

    // ---------- 商务线索 ----------

    public void insertLead(String id, String tenantId, String source, String model,
                           String contactName, String contactPhone, String note) {
        insertLead(id, tenantId, source, model, contactName, contactPhone, note, null);
    }

    public void insertLead(String id, String tenantId, String source, String model, String contactName,
                           String contactPhone, String note, String org) {
        update("INSERT INTO biz_leads(id, tenant_id, source, model, contact_name, contact_phone, org, note, status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,'new',?)",
                id, tenantId, source, model, contactName, contactPhone, org, note, new Timestamp(System.currentTimeMillis()));
    }

    public Map<String, Object> lead(String id) {
        return one("SELECT * FROM biz_leads WHERE id=?", id);
    }

    public List<Map<String, Object>> myLeads(String tenantId) {
        return q("SELECT * FROM biz_leads WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public List<Map<String, Object>> leads(String status) {
        if (status == null || status.isBlank()) {
            return q("SELECT * FROM biz_leads ORDER BY created_at DESC");
        }
        return q("SELECT * FROM biz_leads WHERE status=? ORDER BY created_at DESC", status);
    }

    public int followLead(String id, String status, String reply) {
        return update("UPDATE biz_leads SET status=?, reply=?, updated_at=? WHERE id=?",
                status, reply, new Timestamp(System.currentTimeMillis()), id);
    }

    // ---------- SSH 公钥 ----------

    public void insertSshKey(String id, String tenantId, String name, String pubkey) {
        update("INSERT INTO ssh_keys(id, tenant_id, name, pubkey, created_at) VALUES (?,?,?,?,?)",
                id, tenantId, name, pubkey, new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> mySshKeys(String tenantId) {
        return q("SELECT * FROM ssh_keys WHERE tenant_id=? ORDER BY created_at DESC", tenantId);
    }

    public Map<String, Object> sshKey(String id) {
        return one("SELECT * FROM ssh_keys WHERE id=?", id);
    }

    public int deleteSshKey(String id) {
        return update("DELETE FROM ssh_keys WHERE id=?", id);
    }

    // ---------- 实例订阅推送 ----------

    public boolean instanceNotifyOn(String instanceId) {
        Map<String, Object> r = one("SELECT notify_push FROM instances WHERE id=?", instanceId);
        return r == null || Boolean.parseBoolean(String.valueOf(r.get("notify_push")));
    }

    public int setInstanceNotify(String instanceId, boolean on) {
        return update("UPDATE instances SET notify_push=? WHERE id=?", on, instanceId);
    }

    /** 实例生命周期通知：尊重租户对该实例的订阅开关 */
    public void notifyInstance(String tenantId, String instanceId, String category, String title, String body) {
        if (!instanceNotifyOn(instanceId)) {
            return;
        }
        String id = "n" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        insertNotification(id, tenantId, category, title, body);
    }

    /** 续费：orders.amount 是预扣池，续费累加进去，实结 vs 累计预扣的退款口径保持不变 */
    public void addOrderAmount(String orderId, BigDecimal amount) {
        update("UPDATE orders SET amount = amount + ? WHERE id=?", amount, orderId);
    }

    public void setExpire(String instanceId, Timestamp expireAt) {
        update("UPDATE instances SET expire_at=? WHERE id=?", expireAt, instanceId);
    }

    // ============ 需求补齐批次：定时关机 / 券 / 实名 / 学生 / 数据集 / 驱动矩阵 / 镜像共享扫描 ============

    // ---------- 定时关机 ----------

    public void insertSchedule(String id, String instanceId, String tenantId, String action, Timestamp at) {
        update("INSERT INTO instance_schedules(id, instance_id, tenant_id, action, at, status) VALUES (?,?,?,?,?,'pending')",
                id, instanceId, tenantId, action, at);
    }

    public List<Map<String, Object>> pendingSchedules(String action) {
        return q("SELECT * FROM instance_schedules WHERE action=? AND status='pending' AND at <= ?",
                action, new Timestamp(System.currentTimeMillis()));
    }

    public int finishSchedule(String id) {
        return update("UPDATE instance_schedules SET status='done' WHERE id=? AND status='pending'", id);
    }

    public List<Map<String, Object>> schedulesOfInstance(String instanceId) {
        return q("SELECT * FROM instance_schedules WHERE instance_id=? ORDER BY at DESC", instanceId);
    }

    public int cancelSchedulesOfInstance(String instanceId) {
        return update("UPDATE instance_schedules SET status='cancelled' WHERE instance_id=? AND status='pending'", instanceId);
    }

    // ---------- 邀请返券 ----------

    public void setInviteCode(String tenantId, String code) {
        update("UPDATE tenants SET invite_code=? WHERE id=?", code, tenantId);
    }

    /** 新租户的邀请码：8 位、去掉易混淆字符、库内唯一 */
    public String newInviteCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            String code = sb.toString();
            if (tenantByInviteCode(code) == null) {
                return code;
            }
        }
        return "INV" + System.currentTimeMillis() % 100000;
    }

    public Map<String, Object> tenantByInviteCode(String code) {
        return one("SELECT * FROM tenants WHERE UPPER(invite_code)=UPPER(?)", code);
    }

    public void setInvitedBy(String userId, String inviterTenantId) {
        update("UPDATE users SET invited_by=? WHERE id=?", inviterTenantId, userId);
    }

    /** 我邀请来的人：账号、注册时间、首充返券是否已发放 */
    public List<Map<String, Object>> usersInvitedBy(String tenantId) {
        return q("SELECT id, username, display_name, created_at, invite_rewarded FROM users"
                + " WHERE invited_by=? ORDER BY created_at DESC", tenantId);
    }

    /** 首充返券只发一次：条件更新，返回 1 表示这次由我发 */
    public int markInviteRewarded(String userId) {
        return update("UPDATE users SET invite_rewarded=TRUE WHERE id=? AND invite_rewarded=FALSE", userId);
    }

    public int countRecharges(String tenantId) {
        Map<String, Object> r = one("SELECT COUNT(*) AS c FROM recharges WHERE tenant_id=?", tenantId);
        return r == null ? 0 : ((Number) r.get("c")).intValue();
    }

    // ---------- 优惠券 ----------
    public List<Map<String, Object>> couponsOfTenant(String tenantId) {
        return q("SELECT * FROM coupons WHERE tenant_id=? AND status<>'expired' ORDER BY status, expire_at", tenantId);
    }

    public Map<String, Object> coupon(String id) {
        return one("SELECT * FROM coupons WHERE id=?", id);
    }

    public void insertCoupon(String id, String tenantId, String title, BigDecimal amount, Timestamp expireAt) {
        update("INSERT INTO coupons(id, tenant_id, title, amount_yuan, status, expire_at, created_at) VALUES (?,?,?,?,'unused',?,?)",
                id, tenantId, title, amount, expireAt, new Timestamp(System.currentTimeMillis()));
    }

    /** 兑换券：条件更新防止重复核销，返回受影响行数（1=兑换成功） */
    public int redeemCoupon(String id, String tenantId) {
        return update("UPDATE coupons SET status='redeemed' WHERE id=? AND tenant_id=? AND status='unused'", id, tenantId);
    }

    // ---------- 实名认证 ----------

    public Map<String, Object> realnameOfTenant(String tenantId) {
        return one("SELECT * FROM realnames WHERE tenant_id=? ORDER BY submitted_at DESC", tenantId);
    }

    public void insertRealname(String id, String tenantId, String realName, String idNoMasked, String phone) {
        update("INSERT INTO realnames(id, tenant_id, real_name, id_no_masked, phone, status, submitted_at) VALUES (?,?,?,?,?,'pending',?)",
                id, tenantId, realName, idNoMasked, phone, new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> realnamesByStatus(String status) {
        return q("SELECT * FROM realnames WHERE status=? ORDER BY submitted_at", status);
    }

    public int reviewRealname(String id, String status) {
        return update("UPDATE realnames SET status=?, reviewed_at=? WHERE id=?", status, new Timestamp(System.currentTimeMillis()), id);
    }

    // ---------- 学生认证 ----------

    public Map<String, Object> studentCertOfTenant(String tenantId) {
        return one("SELECT * FROM student_certs WHERE tenant_id=? ORDER BY submitted_at DESC", tenantId);
    }

    public void insertStudentCert(String id, String tenantId, String school, String studentNo) {
        update("INSERT INTO student_certs(id, tenant_id, school, student_no, status, submitted_at) VALUES (?,?,?,?,'pending',?)",
                id, tenantId, school, studentNo, new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> studentCertsByStatus(String status) {
        return q("SELECT * FROM student_certs WHERE status=? ORDER BY submitted_at", status);
    }

    public int reviewStudentCert(String id, String status) {
        return update("UPDATE student_certs SET status=?, reviewed_at=? WHERE id=?", status, new Timestamp(System.currentTimeMillis()), id);
    }

    public void setStudentVerified(String tenantId, boolean verified) {
        update("UPDATE tenants SET student_verified=? WHERE id=?", verified, tenantId);
    }

    // ---------- 数据集 ----------

    public List<Map<String, Object>> datasets() {
        return q("SELECT * FROM datasets ORDER BY category, name");
    }

    public Map<String, Object> dataset(String id) {
        return one("SELECT * FROM datasets WHERE id=?", id);
    }

    public int mountDataset(String instanceId, String datasetId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM instance_mounts WHERE instance_id=? AND dataset_id=?",
                Integer.class, instanceId, datasetId);
        if (n == 0) {
            update("INSERT INTO instance_mounts(instance_id, dataset_id, mounted_at) VALUES (?,?,?)",
                    instanceId, datasetId, new Timestamp(System.currentTimeMillis()));
            return 1;
        }
        return 0;
    }

    public List<Map<String, Object>> mountsOfInstance(String instanceId) {
        return q("SELECT d.*, m.mounted_at FROM instance_mounts m JOIN datasets d ON d.id=m.dataset_id WHERE m.instance_id=?", instanceId);
    }

    // ---------- 驱动矩阵 ----------

    public List<Map<String, Object>> driverMatrix() {
        return q("SELECT * FROM driver_matrix ORDER BY id");
    }

    // ---------- 镜像共享 / 扫描 / 保存 ----------

    public int shareImage(String imageId, String tenantId) {
        return update("UPDATE images SET shared=TRUE WHERE id=? AND tenant_id=?", imageId, tenantId);
    }

    public int setScanStatus(String imageId, String status) {
        return update("UPDATE images SET scan_status=? WHERE id=?", status, imageId);
    }

    public void insertSavedImage(String id, String tenantId, String name, String base, BigDecimal sizeGb,
                                 String sourceInstanceId) {
        update("INSERT INTO images(id, tenant_id, name, base, size_gb, zone, uuid, status, scan_status, source_instance, created_at) "
                        + "VALUES (?,?,?,?,?,'zone-a',?,'available','未扫描',?,?)",
                id, tenantId, name, base, sizeGb, "img" + id.substring(1, 7),
                sourceInstanceId, new Timestamp(System.currentTimeMillis()));
    }

    public List<Map<String, Object>> communityImages() {
        return q("SELECT * FROM images WHERE shared=TRUE ORDER BY created_at DESC");
    }

    // ---------- 经营报表 ----------

    public List<Map<String, Object>> revenueByDay(int days) {
        return q("SELECT CAST(settled_at AS DATE) AS d, COUNT(*) AS bills, SUM(amount) AS amount "
                        + "FROM billing_records WHERE settled_at > ? "
                        + "GROUP BY CAST(settled_at AS DATE) ORDER BY d",
                new Timestamp(System.currentTimeMillis() - days * 86_400_000L));
    }

    public List<Map<String, Object>> couponBalanceSummary() {
        return q("SELECT status, COUNT(*) AS n, SUM(amount_yuan) AS total FROM coupons GROUP BY status");
    }

    // ---------- 欠费巡检 ----------

    public List<Map<String, Object>> tenantsWithNegativeBalance() {
        return q("SELECT id FROM tenants WHERE balance_yuan < 0");
    }

    public List<Map<String, Object>> runningInstancesOfTenant(String tenantId) {
        return q("SELECT * FROM instances WHERE tenant_id=? AND status='running'", tenantId);
    }
}
