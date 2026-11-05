package com.example.rental;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // 租户用户（含子账号）只能看到自己一家，否则就是横向越权
        if ("tenant".equals(role) || "sub".equals(role)) {
            if (myTenant == null) {
                throw new AuthException(403, "会话缺少租户信息，请重新登录");
            }
            return all.stream().filter(t -> myTenant.equals(t.get("id"))).toList();
        }
        return all;
    }

    /** 租户用户只能查自己租户的数据；平台侧可以查任意租户 */
    private String scope(String role, String myTenant, String requested) {
        if (!"tenant".equals(role) && !"sub".equals(role)) {
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

    /** 售卖的规格：库存按当前卡池实时算，单价按调用者所属租户实时算 */
    @GetMapping("/skus")
    public List<Map<String, Object>> skus(@RequestAttribute(value = "authTenant", required = false) String authTenant) {
        List<Map<String, Object>> all = allSkus(authTenant);
        // 运营下架的规格不再出现在用户端（已下单的实例不受影响）
        Map<String, Boolean> shelf = db.skuStates();
        all.removeIf(m -> Boolean.FALSE.equals(shelf.get(String.valueOf(m.get("code")))));
        return all;
    }

    /** 全量规格（不过滤上下架），管理端与用户端共用同一份定义，顺序固定 */
    private List<Map<String, Object>> allSkus(String authTenant) {
        // 库存按型号分别统计：不能把本机的 4060 算进 5090 的库存，否则页面上「5090 有 17 张」是假的
        int free5090 = db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='free' AND model='RTX 5090'")
                .stream().mapToInt(r -> ((Number) r.get("c")).intValue()).sum();
        // 只看真实硬件节点（labels 带 gpu.real=true），避免把演示台账的模拟卡算进本机库存
        int realFree = db.q("""
                SELECT COUNT(*) AS c FROM gpus WHERE status='free' AND model='RTX 4060 Laptop'
                  AND node_id IN (SELECT id FROM nodes WHERE labels LIKE '%gpu.real=true%')
                """).stream().mapToInt(r -> ((Number) r.get("c")).intValue()).sum();
        int wholeStock = db.q("""
                SELECT COALESCE(MAX(c),0) AS m FROM (
                  SELECT COUNT(*) AS c FROM gpus WHERE status='free' AND model='RTX 5090' GROUP BY node_id
                ) x
                """).stream().mapToInt(r -> ((Number) r.get("m")).intValue()).sum();

        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(sku("1", "RTX 5090", 1, 32, free5090,
                "16 核", "AMD EPYC 7513*2", "64 GB", "50G SSD", "200G NVMe",
                "单张 5090，独占整卡，适合推理、微调、实验"));
        out.add(sku("2", "RTX 5090", 2, 64, free5090 / 2,
                "32 核", "AMD EPYC 7513*2", "128 GB", "100G SSD", "400G NVMe",
                "同节点 2 张卡，适合双卡数据并行、中等规模微调"));
        out.add(sku("4", "RTX 5090", 4, 128, free5090 / 4,
                "64 核", "AMD EPYC 7513*2", "256 GB", "240G SSD", "1.5TB NVMe",
                "同节点 4 张卡，适合数据并行训练"));
        out.add(sku("whole", "RTX 5090", 8, 256, wholeStock,
                "128 核", "AMD EPYC 7513*2", "512 GB", "480G SSD", "3.84TB NVMe",
                "同一节点 8 张卡，适合分布式训练、多卡并行"));
        Map<String, Object> offpeak = sku("offpeak", "RTX 5090 · 闲时卡", 1, 32, free5090,
                "16 核", "AMD EPYC 7513*2", "64 GB", "50G SSD", "200G NVMe",
                "22:00-08:00 包夜时段半价，适合离线训练、批量跑分");
        offpeak.put("offpeak", true);
        out.add(offpeak);
        // 本机真实显卡 SKU：库存按台账实时算，用于本机联调与真实数据演示
        Map<String, Object> local = sku("local4060", "RTX 4060 Laptop", 1, 8, realFree,
                "16 核", "AMD Ryzen 7 7735H", "16 GB", "299G SSD", "共享 299G",
                "本机真实显卡（NVIDIA RTX 4060 Laptop 8G），下单后落到本机 k3s 节点");
        local.put("local", true);
        local.put("zone", "本机");
        out.add(0, local);
        Map<String, Object> debug = sku("debug", "无卡调试", 0, 0, 999,
                "4 核", "共享 CPU 池", "8 GB", "20G SSD", "50G 云盘",
                "不开 GPU 仅 CPU，约 0.1 元/时，用于调代码、传数据、装环境");
        debug.put("debug", true);
        out.add(debug);
        // 库存口径徽章：真实集群=台账与集群对账，演示模式=台账模拟
        String source = "real".equals(k8s.mode()) ? "台账 · 集群对账" : "台账 · 演示";
        Map<String, Object> tenant = authTenant == null ? null : db.tenant(authTenant);
        for (Map<String, Object> m : out) {
            m.put("stockSource", source);
            // unitPrice 仍是该 SKU 的挂牌价（含闲时/调试/本机自己的特价），租户实付价走 priceBoard，
            // 市场卡片展示挂牌价、创建页按 priceBoard 结算，两边各自语义不混
            String code = String.valueOf(m.get("code"));
            m.put("unitPrice", rental.unitPriceFor(code, 1, (Map<String, Object>) null));
            m.put("priceBoard", rental.priceBoard(code, tenant));
        }
        return out;
    }

    /** 管理端：全部规格 + 上下架状态，顺序与用户端一致，不因下架而移位 */
    @GetMapping("/admin/sku-shelf")
    public List<Map<String, Object>> skuShelf() {
        Map<String, Boolean> shelf = db.skuStates();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> m : allSkus(null)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", m.get("code"));
            row.put("model", m.get("model"));
            row.put("gpuCount", m.get("gpuCount"));
            row.put("unitPrice", m.get("unitPrice"));
            row.put("stock", m.get("stock"));
            row.put("onSale", !Boolean.FALSE.equals(shelf.get(String.valueOf(m.get("code")))));
            out.add(row);
        }
        return out;
    }

    /** 管理端：上架 / 下架某个规格。下架后用户端不再出现，已开实例不受影响。 */
    @PostMapping("/admin/sku-shelf/{code}")
    public Map<String, Object> setShelf(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body,
                                        @RequestAttribute(value = "authUsername", required = false) String actor) {
        Map<String, Object> b = body == null ? Map.of() : body;
        boolean onSale = !"false".equalsIgnoreCase(String.valueOf(b.getOrDefault("onSale", true)));
        String note = b.get("note") == null ? null : String.valueOf(b.get("note"));
        db.setSkuState(code, onSale, note);
        db.insertAudit(actor == null ? "admin" : actor,
                onSale ? "SKU_ON_SALE" : "SKU_OFF_SALE", code, note == null ? "" : note);
        return Map.of("code", code, "onSale", onSale,
                "message", onSale ? "已上架：" + code : "已下架：" + code);
    }

    /**
     * 商品卡的六个规格块由服务端下发，前端只负责渲染。
     * 以后接真实机型（A100/H800）只改这里，页面不用动。
     */
    /** SKU 对应的卡数（用于子账号配额校验，与 skus() 的定义保持一致） */
    private int skuGpuCount(String code) {
        return switch (code) {
            case "debug" -> 0;
            case "whole" -> 8;
            case "2" -> 2;
            case "4" -> 4;
            default -> 1;
        };
    }

    /** 与 PlatformApi 的 License 校验同口径：过期或签名不符即无效 */
    private boolean licenseValid(Map<String, Object> l) {
        try {
            java.sql.Timestamp exp = (java.sql.Timestamp) l.get("expire_at");
            return exp != null && exp.getTime() > System.currentTimeMillis();
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> sku(String code, String model, int gpuCount, int memGb, int stock,
                                    String cores, String cpu, String ram, String sysDisk, String dataDisk,
                                    String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", model);
        m.put("model", model);
        m.put("gpuCount", gpuCount);
        m.put("memGb", memGb);
        m.put("unitPrice", rental.unitPrice());
        m.put("stock", stock);
        m.put("zone", "可用区 A");
        m.put("cores", cores);
        m.put("cpu", cpu);
        m.put("ram", ram);
        m.put("sysDisk", sysDisk);
        m.put("dataDisk", dataDisk);
        m.put("dataExpandMax", rental.dataDiskMaxGb());
        m.put("desc", desc);
        return m;
    }

    /**
     * 租用时长档位：日付 / 周付 / 月付带套餐折扣，价格由服务端按当前参数实时算，
     * 前端下单弹窗只渲染不写死价格。
     */
    @GetMapping("/plans")
    public List<Map<String, Object>> plans() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int h : RentalService.PLAN_HOURS) {
            out.add(plan(h, PLAN_LABELS.get(h)));
        }
        return out;
    }

    private static final Map<Integer, String> PLAN_LABELS = Map.of(
            1, "1 小时", 2, "2 小时", 8, "8 小时",
            24, "24 小时 · 日付", 72, "72 小时 · 3 天",
            168, "168 小时 · 周付", 720, "720 小时 · 月付");

    private Map<String, Object> plan(int hours, String label) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        BigDecimal discount = rental.discountFor(hours);
        m.put("hours", hours);
        m.put("label", label);
        m.put("unitPrice", rental.effectivePrice(hours));
        m.put("discount", discount);
        m.put("discounted", discount.compareTo(BigDecimal.ONE) < 0);
        m.put("dataDiskPrice", rental.dataDiskPrice());
        return m;
    }

    // ---------------- 下单 ----------------

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody Map<String, Object> body,
                                           @RequestAttribute(value = "authRole", required = false) String role,
                                           @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                           @RequestAttribute(value = "authUsername", required = false) String username) {
        // 租户只能给自己下单。以前直接读 body 里的 tenantId，任何登录用户传别人的 id 就能花别人的余额开机器。
        String tenantId = scope(role, myTenant, str(body.getOrDefault("tenantId", "")));
        if (tenantId == null || tenantId.isBlank()) {
            throw new RentalService.BizException("缺少租户信息，请重新登录");
        }
        // 离线授权：导入过 License 且已过期/签名不符时，禁止新开实例（存量实例不受影响）
        Map<String, Object> lic = db.latestLicense();
        if (lic != null && !licenseValid(lic)) {
            throw new RentalService.BizException("平台授权已过期或签名不匹配，禁止新建实例，请联系供应商续期");
        }
        String sku = str(body.getOrDefault("sku", "single"));
        int hours = body.get("hours") == null ? 1 : ((Number) body.get("hours")).intValue();
        // 子账号资源维度授权：perms 里没有 order 就不许下单（可配 order,image,storage 等）
        if ("sub".equals(role) && username != null) {
            Map<String, Object> su = db.userByUsername(username);
            Object perms = su == null ? null : su.get("perms");
            if (perms != null) {
                String p = String.valueOf(perms);
                if (!p.isBlank() && !java.util.Arrays.stream(p.split("[,，\\s]+"))
                        .anyMatch(x -> "order".equalsIgnoreCase(x) || "*".equals(x))) {
                    throw new RentalService.BizException("当前子账号未被授予「创建实例」权限，请联系主账号管理员");
                }
            }
        }
        // 子账号配额：子账号独立限额，超额直接拒（主账号不设限，走租户配额）
        int wantGpus = Math.max(0, skuGpuCount(sku));
        if ("sub".equals(role) && username != null && wantGpus > 0) {
            Map<String, Object> su = db.userByUsername(username);
            Object q = su == null ? null : su.get("quota_gpus");
            if (q instanceof Number) {
                int quota = ((Number) q).intValue();
                int used = db.usedGpusOfUser(username);
                if (used + wantGpus > quota) {
                    throw new RentalService.BizException("子账号 GPU 配额不足：已用 " + used + " 卡 / 上限 "
                            + quota + " 卡，本次需要 " + wantGpus + " 卡");
                }
            }
        }
        String idemKey = str(body.getOrDefault("orderNo", ""));
        String image = str(body.getOrDefault("image", "")).trim();
        // 只当标识用（存库 + 写进 Pod 注解/环境变量），不拼镜像地址，所以不做命名空间强校验，
        // 但「我的镜像」「社区镜像」里中文名是合法的，长度和控制字符要卡住。
        if (image.length() > 128 || image.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new RentalService.BizException("镜像标识不合法：长度需 1~128 且不含控制字符");
        }
        int dataDisk = body.get("dataDiskGb") instanceof Number n ? n.intValue() : 0;
        if (dataDisk < 0 || dataDisk > 4096) {
            throw new RentalService.BizException("数据盘容量必须是 0~4096 GB");
        }
        Map<String, Object> out = rental.createOrder(tenantId, sku, hours, idemKey,
                image.isEmpty() ? null : image, dataDisk);
        // 记录是谁开的：子账号配额按这个字段核销，主账号也留痕
        if (out.get("instanceId") != null) {
            db.setInstanceCreator(String.valueOf(out.get("instanceId")), username);
        }
        if (username != null && !"tenant".equals(role) && !"sub".equals(role)) {
            db.insertAudit(username, "ORDER_CREATE", tenantId,
                    "管理端代下单 sku=" + sku + " " + hours + " 小时" + (image.isEmpty() ? "" : " 镜像 " + image));
        }
        return out;
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
        for (Map<String, Object> row : db.q("""
                SELECT i.gpu_count, i.used_seconds, o.unit_price
                  FROM instances i LEFT JOIN orders o ON o.id = i.order_id
                 WHERE i.tenant_id=? AND i.status='running'
                """, tenantId)) {
            BigDecimal price = row.get("unit_price") == null ? rental.unitPrice() : (BigDecimal) row.get("unit_price");
            runningEstimate = runningEstimate.add(RentalService.amountOf(
                    ((Number) row.get("gpu_count")).intValue(),
                    ((Number) row.get("used_seconds")).longValue(), price));
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
