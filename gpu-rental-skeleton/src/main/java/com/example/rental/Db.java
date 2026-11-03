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

    /** 单卡：优先放到“已经占用最多”的节点（bin-packing，尽量留整台空机给整机订单） */
    public List<String> pickSingleGpuIds(int count) {
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

    /** 整机：必须凑齐同一节点的 N 张 free 卡 */
    public Map<String, Object> pickWholeNode(int count) {
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
                               String gpuIds, int gpuCount, String podName, Timestamp expireAt) {
        update("""
                INSERT INTO instances(id, order_id, tenant_id, node_id, gpu_ids, gpu_count, pod_name,
                                      status, created_at, expire_at, used_seconds)
                VALUES (?,?,?,?,?,?,?,'provisioning',?,?,0)
                """, id, orderId, tenantId, nodeId, gpuIds, gpuCount, podName,
                new Timestamp(System.currentTimeMillis()), expireAt);
    }

    public Map<String, Object> instance(String id) {
        return one("SELECT * FROM instances WHERE id=?", id);
    }

    public List<Map<String, Object>> instances(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return q("SELECT * FROM instances ORDER BY created_at DESC LIMIT 50");
        }
        return q("SELECT * FROM instances WHERE tenant_id=? ORDER BY created_at DESC LIMIT 50", tenantId);
    }

    public List<Map<String, Object>> instancesByStatus(String status) {
        return q("SELECT * FROM instances WHERE status=? ORDER BY created_at", status);
    }

    public void setStatus(String id, String status) {
        update("UPDATE instances SET status=? WHERE id=?", status, id);
    }

    public void markRunning(String id) {
        update("UPDATE instances SET status='running', started_at=? WHERE id=? AND status='provisioning'",
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

    public int updateSetting(String k, String v) {
        return update("UPDATE settings SET v=? WHERE k=?", v, k);
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
                INSERT INTO users(id, username, display_name, role, tenant_id, status)
                VALUES (?,?,?,?,?,'active')
                """, id, username, displayName, role, tenantId);
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
}
