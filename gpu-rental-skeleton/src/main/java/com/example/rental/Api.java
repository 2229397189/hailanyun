package com.example.rental;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class Api {

    private final Db db;
    private final RentalService rental;
    private final K8sClient k8s;
    private final org.springframework.beans.factory.ObjectProvider<FakeK8s> fakeProvider;

    public Api(Db db, RentalService rental, K8sClient k8s,
               org.springframework.beans.factory.ObjectProvider<FakeK8s> fakeProvider) {
        this.db = db;
        this.rental = rental;
        this.k8s = k8s;
        this.fakeProvider = fakeProvider;
    }

    /** 当前运行时模式，前端用来显示“模拟 / 真集群”徽章 */
    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("k8sMode", k8s.mode());
        out.put("real", "real".equals(k8s.mode()));
        return out;
    }

    // ---------------- 卡池与总览 ----------------

    @GetMapping("/pool")
    public Map<String, Object> pool() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", db.nodes());
        out.put("gpus", db.gpus());
        return out;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("free", 0);
        counts.put("occupied", 0);
        counts.put("fault", 0);
        counts.put("reserved", 0);
        for (Map<String, Object> row : db.q("SELECT status, COUNT(*) AS c FROM gpus GROUP BY status")) {
            counts.put((String) row.get("status"), ((Number) row.get("c")).intValue());
        }
        int total = counts.values().stream().mapToInt(v -> (Integer) v).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalGpus", total);
        out.put("gpuStatus", counts);
        out.put("nodes", db.q("SELECT COUNT(*) AS c FROM nodes").get(0).get("c"));
        out.put("runningInstances", db.q("SELECT COUNT(*) AS c FROM instances WHERE status='running'")
                .get(0).get("c"));
        out.put("settledRevenue", db.q("SELECT COALESCE(SUM(amount),0) AS s FROM billing_records")
                .get(0).get("s"));
        return out;
    }

    @GetMapping("/tenants")
    public List<Map<String, Object>> tenants(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        List<Map<String, Object>> all = db.tenants();
        // 租户用户只能看到自己一家，否则就是横向越权
        if ("tenant".equals(role)) {
            if (myTenant == null) {
                throw new AuthException(403, "会话缺少租户信息，请重新登录");
            }
            return all.stream().filter(t -> myTenant.equals(t.get("id"))).toList();
        }
        return all;
    }

    /** 租户用户只能查自己租户的数据；平台侧可以查任意租户 */
    private String scope(String role, String myTenant, String requested) {
        if (!"tenant".equals(role)) {
            return requested;
        }
        if (myTenant == null) {
            throw new AuthException(403, "会话缺少租户信息，请重新登录");
        }
        if (requested != null && !requested.isBlank() && !requested.equals(myTenant)) {
            throw new AuthException(403, "无权查看其它租户的数据");
        }
        return myTenant;
    }

    /** 售卖的规格：库存按当前卡池实时算 */
    @GetMapping("/skus")
    public List<Map<String, Object>> skus() {
        int freeSingle = db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='free'")
                .stream().mapToInt(r -> ((Number) r.get("c")).intValue()).sum();
        int wholeStock = db.q("""
                SELECT COALESCE(MAX(c),0) AS m FROM (
                  SELECT COUNT(*) AS c FROM gpus WHERE status='free' GROUP BY node_id
                ) x
                """).stream().mapToInt(r -> ((Number) r.get("m")).intValue()).sum();

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(Map.of(
                "code", "single", "name", "单卡 · RTX 5090", "gpuCount", 1,
                "memGb", 32, "unitPrice", RentalService.UNIT_PRICE,
                "stock", freeSingle,
                "desc", "单张 5090，独占整卡，适合推理、微调、实验"));
        out.add(Map.of(
                "code", "whole", "name", "整机 · 8 × RTX 5090", "gpuCount", 8,
                "memGb", 256, "unitPrice", RentalService.UNIT_PRICE,
                "stock", wholeStock,
                "desc", "同一节点 8 张卡，适合分布式训练、多卡并行"));
        return out;
    }

    // ---------------- 下单 ----------------

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body) {
        String tenantId = str(body.getOrDefault("tenantId", "t1"));
        String sku = str(body.getOrDefault("sku", "single"));
        int hours = body.get("hours") == null ? 1 : ((Number) body.get("hours")).intValue();
        String idemKey = str(body.getOrDefault("orderNo", ""));
        return rental.createOrder(tenantId, sku, hours, idemKey);
    }

    @GetMapping("/billing/daily")
    public List<Map<String, Object>> billingDaily(@RequestParam String tenantId,
                                                  @RequestParam(defaultValue = "7") int days,
                                                  @RequestAttribute(value = "authRole", required = false) String role,
                                                  @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.billingDaily(scope(role, myTenant, tenantId), Math.min(30, Math.max(1, days)));
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(required = false) String tenantId,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.ordersOf(scope(role, myTenant, tenantId));
    }

    @GetMapping("/instances")
    public List<Map<String, Object>> instances(@RequestParam(required = false) String tenantId,
                                               @RequestAttribute(value = "authRole", required = false) String role,
                                               @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.instances(scope(role, myTenant, tenantId));
    }

    @PostMapping("/instances/{id}/release")
    public Map<String, Object> release(@PathVariable String id) {
        return rental.release(id);
    }

    // ---------------- 账单 ----------------

    @GetMapping("/billing")
    public Map<String, Object> billing(@RequestParam(defaultValue = "t1") String tenantId,
                                       @RequestAttribute(value = "authRole", required = false) String role,
                                       @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        tenantId = scope(role, myTenant, tenantId);
        BigDecimal settled = db.settledTotal(tenantId);
        BigDecimal runningEstimate = BigDecimal.ZERO;
        for (Map<String, Object> row : db.q(
                "SELECT gpu_count, used_seconds FROM instances WHERE tenant_id=? AND status='running'",
                tenantId)) {
            runningEstimate = runningEstimate.add(RentalService.amountOf(
                    ((Number) row.get("gpu_count")).intValue(),
                    ((Number) row.get("used_seconds")).longValue()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settled", settled);
        out.put("runningEstimate", runningEstimate);
        out.put("records", db.billing(tenantId));
        return out;
    }

    // ---------------- 演示用运维动作 ----------------

    @PostMapping("/admin/gpus/{id}/fault")
    public Map<String, Object> fault(@PathVariable String id) {
        int n = db.markFault(id);
        return Map.of("changed", n, "message", n > 0 ? "已标记故障，不再参与售卖" : "该卡不是空闲状态，无法标记");
    }

    @PostMapping("/admin/gpus/{id}/repair")
    public Map<String, Object> repair(@PathVariable String id) {
        int n = db.repair(id);
        return Map.of("changed", n, "message", n > 0 ? "已修复，回到可售池" : "该卡不是故障状态");
    }

    /** 让某个实例的 Pod 卡在 Terminating，验证回收兜底 */
    @PostMapping("/admin/instances/{id}/stuck")
    public Map<String, Object> stuck(@PathVariable String id) {
        Map<String, Object> inst = db.instance(id);
        if (inst == null) {
            throw new RentalService.BizException("实例不存在");
        }
        FakeK8s fake = fakeProvider.getIfAvailable();
        if (fake == null) {
            throw new RentalService.BizException("当前是真实 K8s 模式，卡住演练不可用；"
                    + "可直接对 Pod 执行 kubectl delete --grace-period=0 验证回收兜底");
        }
        fake.makeStuck((String) inst.get("pod_name"));
        return Map.of("message", "已模拟 Pod 卡在 Terminating，现在点释放会失败并保持 releasing");
    }

    @PostMapping("/admin/instances/{id}/unstick")
    public Map<String, Object> unstick(@PathVariable String id) {
        Map<String, Object> inst = db.instance(id);
        if (inst == null) {
            throw new RentalService.BizException("实例不存在");
        }
        FakeK8s fake = fakeProvider.getIfAvailable();
        if (fake == null) {
            throw new RentalService.BizException("当前是真实 K8s 模式，无需解除");
        }
        fake.unstick((String) inst.get("pod_name"));
        return Map.of("message", "已解除卡住，巡检会在 5 秒内自动补删并结算");
    }

    // ---------------- 异常处理 ----------------

    @ExceptionHandler(RentalService.BizException.class)
    public ResponseEntity<Map<String, Object>> biz(RentalService.BizException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
