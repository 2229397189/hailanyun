package com.example.rental;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 下单与释放。这个类是整个平台唯一真正值钱的地方，两个不变量：
 *
 * 1. 下单原子性：占卡 + 建订单 + 建实例 + 扣余额，同一个事务，要么全成要么全不成。
 * 2. 释放顺序：先确认 K8s 里 Pod 真的没了，再改数据库。反过来会“账面已释放、卡还占着”。
 */
@Service
public class RentalService {

    /** 默认单价（元 / 卡 / 小时）：rental.unit_price 参数缺失或非法时的兜底 */
    public static final BigDecimal DEFAULT_UNIT_PRICE = new BigDecimal("2.80");

    /** SKU → 卡型号。分配卡时必须按型号过滤，否则「买 4060 分到 5090」会真的发生。 */
    private static final String MODEL_5090 = "RTX 5090";
    private static final String MODEL_4060 = "RTX 4060 Laptop";

    public static String modelOfSku(String sku) {
        if (sku == null) return null;
        return switch (sku) {
            case "debug" -> null;
            case "local4060" -> MODEL_4060;
            default -> MODEL_5090;
        };
    }

    private final Db db;
    private final K8sClient k8s;
    private final GroupCatalog groupCatalog;
    private final TransactionTemplate tx;

    public RentalService(Db db, K8sClient k8s, GroupCatalog groupCatalog, PlatformTransactionManager tm) {
        this.db = db;
        this.k8s = k8s;
        this.groupCatalog = groupCatalog;
        this.tx = new TransactionTemplate(tm);
    }

    public static class BizException extends RuntimeException {
        public BizException(String message) {
            super(message);
        }
    }

    /** 当前单价：读 rental.unit_price 参数，管理员改完立刻生效（下单与展示同源） */
    public BigDecimal unitPrice() {
        return decimalSetting("rental.unit_price", DEFAULT_UNIT_PRICE);
    }

    /** 套餐折扣：24/168/720 小时命中日付/周付/月付档位，其余时长不打折 */
    public BigDecimal discountFor(int hours) {
        String key = switch (hours) {
            case 24 -> "plan.discount.h24";
            case 168 -> "plan.discount.h168";
            case 720 -> "plan.discount.h720";
            default -> null;
        };
        if (key == null) {
            return BigDecimal.ONE;
        }
        BigDecimal d = decimalSetting(key, BigDecimal.ONE);
        if (d.compareTo(BigDecimal.ZERO) <= 0 || d.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return d;
    }

    /** 下单快照价 = 当前单价 × 套餐折扣，保留两位。订单落库后不再随参数变化 */
    public BigDecimal effectivePrice(int hours) {
        return unitPrice().multiply(discountFor(hours)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 学生认证折扣系数 */
    private static final BigDecimal STUDENT_DISCOUNT = new BigDecimal("0.90");

    /** 租用时长档位：/api/plans 与租户价目表共用这一份，避免两边档位不一致 */
    public static final java.util.List<Integer> PLAN_HOURS = java.util.List.of(1, 2, 8, 24, 72, 168, 720);

    /**
     * 某个租户下单时的每卡每时单价。下单扣款、续费、创建页报价全部走这里，
     * 之前页面只看 /api/plans 的挂牌价，学生认证 / 闲时卡 / 本机卡的折扣漏算，
     * 会出现「明细写 ¥2.85、实际扣 ¥2.57」这种对不上的账。
     */
    public BigDecimal unitPriceFor(String sku, int hours, Map<String, Object> tenant) {
        BigDecimal p;
        if ("debug".equals(sku)) {
            p = decimalSetting("rental.debug_price", new BigDecimal("0.10"));
        } else if ("local4060".equals(sku)) {
            p = decimalSetting("rental.local_price", new BigDecimal("0.50"));
        } else {
            p = effectivePrice(hours);
            if ("offpeak".equals(sku)) {
                p = p.multiply(offpeakDiscount());
            }
        }
        p = p.setScale(2, RoundingMode.HALF_UP);
        if (tenant != null && studentVerified(tenant)) {
            p = p.multiply(STUDENT_DISCOUNT).setScale(2, RoundingMode.HALF_UP);
        }
        return p;
    }

    public BigDecimal unitPriceFor(String sku, int hours, String tenantId) {
        return unitPriceFor(sku, hours, tenantId == null ? null : db.tenant(tenantId));
    }

    /** 售卖页用：某 SKU 在每个计费档位下对这个租户的实际单价 */
    public Map<String, Object> priceBoard(String sku, Map<String, Object> tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int h : PLAN_HOURS) {
            m.put(String.valueOf(h), unitPriceFor(sku, h, tenant));
        }
        m.put("student", tenant != null && studentVerified(tenant));
        return m;
    }

    /** 数据盘费率（元 / GB / 日）：创建页报价、下单扣款、释放实结共用这一个数 */
    public BigDecimal dataDiskPrice() {
        return decimalSetting("rental.data_disk_price", new BigDecimal("0.02"));
    }

    /** 数据盘费用 = 费率 × GB × 天数，不足一日按比例 */
    public BigDecimal dataDiskFee(int gb, int hours) {
        if (gb <= 0) {
            return BigDecimal.ZERO;
        }
        return dataDiskPrice()
                .multiply(BigDecimal.valueOf(gb))
                .multiply(BigDecimal.valueOf(hours))
                .divide(BigDecimal.valueOf(24L), 2, RoundingMode.HALF_UP);
    }

    /** 数据盘可扩容上限（GB）：管理端参数可调，创建页的输入框最大值以它为准 */
    public int dataDiskMaxGb() {
        return decimalSetting("rental.data_disk_max_gb", new BigDecimal("2000")).intValue();
    }

    private BigDecimal decimalSetting(String key, BigDecimal fallback) {        String v = db.setting(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 金额 = 单价 × 卡数 × 秒数 / 3600，保留两位（四舍五入） */
    public static BigDecimal amountOf(int gpuCount, long seconds, BigDecimal unitPrice) {
        return unitPrice
                .multiply(BigDecimal.valueOf(gpuCount))
                .multiply(BigDecimal.valueOf(seconds))
                .divide(BigDecimal.valueOf(3600L), 2, RoundingMode.HALF_UP);
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 金额文案统一两位小数：库里 DECIMAL(14,4) 读出来会带 1100.0000 这种尾巴 */
    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------ 下单

    /** 幂等命中时的统一返回体 */
    private Map<String, Object> reusedResult(Map<String, Object> order, String message) {
        Map<String, Object> inst = db.one("SELECT * FROM instances WHERE order_id=?", order.get("id"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reused", true);
        m.put("message", message);
        m.put("order", order);
        m.put("instance", inst);
        return m;
    }

    public Map<String, Object> createOrder(String tenantId, String sku, int hours, String idemKey,
                                           String imageRef, int dataDiskGb) {
        return createOrderPriced(tenantId, sku, hours, idemKey, null, null, imageRef, dataDiskGb);
    }

    /**
     * 指定价格下单：拼团成团时按拼团价开实例，不叠加套餐折扣与学生优惠。
     * 两个 override 都为 null 就是标准定价链路（日/周/月折扣 + 闲时 + 无卡调试 + 学生认证）。
     */
    public Map<String, Object> createOrderPriced(String tenantId, String sku, int hours, String idemKey,
                                                 BigDecimal priceOverride, BigDecimal amountOverride,
                                                 String imageRef, int dataDiskGb) {
        if (idemKey == null || idemKey.isBlank()) {
            throw new BizException("缺少幂等键 orderNo");
        }
        if (hours <= 0 || hours > 720) {
            throw new BizException("时长必须是 1~720 小时");
        }

        // 幂等：同一个 orderNo 重复提交，直接返回原订单，不产生第二个实例
        Map<String, Object> existed = db.orderByNo(idemKey);
        if (existed != null) {
            return reusedResult(existed, "重复提交，已返回原订单");
        }

        int gpuCount = gpuCountOf(sku);
        String instanceId = newId("i");
        String orderId = newId("o");
        String podName = "gpu-pod-" + instanceId;

        Map<String, Object> created;
        try {
            created = tx.execute(status -> {
            Map<String, Object> tenant = db.tenant(tenantId);
            if (tenant == null) {
                throw new BizException("租户不存在: " + tenantId);
            }

            int quota = ((Number) tenant.get("quota_gpus")).intValue();
            int occupied = db.occupiedGpusOfTenant(tenantId);
            if (occupied + gpuCount > quota) {
                throw new BizException("配额不足：已用 " + occupied + " 卡 / 配额 " + quota + " 卡");
            }

            BigDecimal unitPrice;
            BigDecimal amount;
            if (priceOverride != null) {
                unitPrice = priceOverride.setScale(4, RoundingMode.HALF_UP);
                amount = (amountOverride != null
                        ? amountOverride
                        : amountOf(Math.max(gpuCount, 1), hours * 3600L, unitPrice))
                        .setScale(2, RoundingMode.HALF_UP);
            } else {
                unitPrice = unitPriceFor(sku, hours, tenant);
                amount = amountOf(Math.max(gpuCount, 1), hours * 3600L, unitPrice);
            }
            // 数据盘按 GB·日计费。创建页显示的总额必须等于这里扣的钱，所以费率只有一份。
            BigDecimal diskFee = dataDiskFee(dataDiskGb, hours);
            amount = amount.add(diskFee).setScale(2, RoundingMode.HALF_UP);
            BigDecimal balance = (BigDecimal) tenant.get("balance_yuan");
            if (balance.compareTo(amount) < 0) {
                throw new BizException("余额不足：需预扣 " + amount + " 元，可用 " + balance + " 元");
            }

            List<String> gpuIds;
            String nodeId;
            String model = modelOfSku(sku);
            if ("debug".equals(sku)) {
                gpuIds = List.of();
                nodeId = null;
            } else if ("whole".equals(sku)) {
                Map<String, Object> node = db.pickWholeNode(gpuCount, model);
                if (node == null) {
                    throw new BizException("无整机可用：需要同一节点上 " + gpuCount + " 张空闲的 "
                            + (model == null ? "卡" : model));
                }
                nodeId = (String) node.get("node_id");
                gpuIds = db.freeGpuIdsOfNode(nodeId, gpuCount);
            } else {
                gpuIds = db.pickSingleGpuIds(gpuCount, model);
                if (gpuIds.size() < gpuCount) {
                    throw new BizException("已售罄：当前无空闲的 " + (model == null ? "卡" : model));
                }
                nodeId = (String) db.one("SELECT node_id FROM gpus WHERE id=?", gpuIds.get(0)).get("node_id");
            }

            // 占卡用条件更新（status='free'），并发下不会把同一张卡卖给两个人
            if (!gpuIds.isEmpty()) {
                int rows = db.occupy(gpuIds, instanceId);
                if (rows != gpuCount) {
                    throw new BizException("资源被并发占用，请重试");
                }
            }

            db.insertOrder(orderId, idemKey, tenantId, sku, gpuCount, hours, unitPrice, amount);
            db.insertInstance(instanceId, orderId, tenantId, nodeId, String.join(",", gpuIds),
                    gpuCount, podName,
                    new Timestamp(System.currentTimeMillis() + hours * 3600_000L), imageRef, dataDiskGb);
            db.deductBalance(tenantId, amount);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", instanceId);
            m.put("orderId", orderId);
            m.put("nodeId", nodeId);
            m.put("gpuIds", gpuIds);
            m.put("prepaid", amount);
            m.put("unitPrice", unitPrice);
            return m;
            });
        } catch (BizException e) {
            // 并发下同一 orderNo 的请求会先抢卡、再撞幂等检查。
            // 回查一次：若订单已被别的请求建好，直接返回原单，而不是误报「资源被占用」。
            Map<String, Object> again = db.orderByNo(idemKey);
            if (again != null) {
                return reusedResult(again, "并发重复提交，已返回原订单");
            }
            throw e;
        }

        // 事务提交后再碰外部系统。真实系统这一步要做 outbox / 补偿，
        // 这里直接 try-catch 补偿，逻辑上等价。
        try {
            k8s.create("tenant-" + tenantId, podName, gpuCount, imageRef, dataDiskGb);
        } catch (Exception e) {
            // 事务已提交（余额已扣、卡已占），这里必须补偿：退钱 + 还卡 + 订单作废
            BigDecimal prepaid = (BigDecimal) created.get("prepaid");
            db.setStatus(instanceId, "failed");
            db.releaseByInstance(instanceId);
            if (prepaid != null && prepaid.signum() > 0) {
                db.addBalance(tenantId, prepaid);
            }
            db.insertNotification(newId("n"), tenantId, "账单", "下单失败已退款",
                    "实例创建失败（" + e.getMessage() + "），预扣 ¥" + prepaid + " 已全额退回余额。");
            throw new BizException("实例创建失败，已回滚占卡并退款：" + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reused", false);
        out.put("message", "下单成功，实例创建中");
        out.putAll(created);
        out.put("discount", priceOverride == null ? discountFor(hours) : BigDecimal.ONE);
        db.notifyInstance(tenantId, (String) created.get("instanceId"), "实例", "实例创建中",
                "实例 " + created.get("instanceId") + " 已下单，约数秒后可用（预扣 ¥" + created.get("prepaid") + "）。");
        return out;
    }

    /** 实例上记的数据盘容量，老数据没有这一列时按 0 处理 */
    private static int diskGbOf(Map<String, Object> inst) {
        Object v = inst.get("data_disk_gb");
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** SKU → 卡数：whole=8，debug=0（无卡调试），其余按数字 1/2/4/8（闲时卡按 1 卡） */
    private static int gpuCountOf(String sku) {        if (sku == null) {
            return 1;
        }
        return switch (sku) {
            case "whole" -> 8;
            case "debug" -> 0;
            case "offpeak" -> 1;
            default -> {
                try {
                    int n = Integer.parseInt(sku.trim());
                    yield n >= 1 && n <= 8 ? n : 1;
                } catch (NumberFormatException e) {
                    yield 1;
                }
            }
        };
    }

    /** 闲时折扣：rental.offpeak_discount，0~1 之外视为无效 */
    public BigDecimal offpeakDiscount() {
        BigDecimal d = decimalSetting("rental.offpeak_discount", new BigDecimal("0.5"));
        if (d.compareTo(BigDecimal.ZERO) <= 0 || d.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return d;
    }

    private boolean studentVerified(Map<String, Object> tenant) {
        Object v = tenant.get("student_verified");
        return v instanceof Boolean b && b;
    }

    // ------------------------------------------------------------------ 开关机 / 重置 / 克隆 / 升降配

    /** 关机：删 Pod、停计费、保留数据与占卡（数据盘不丢），关机保留 7 天后由巡检自动释放 */
    public Map<String, Object> powerOff(String instanceId) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if (!"running".equals(String.valueOf(inst.get("status")))) {
            throw new BizException("仅运行中的实例可以关机");
        }
        String pod = (String) inst.get("pod_name");
        if (!k8s.delete("tenant-" + inst.get("tenant_id"), pod)) {
            throw new BizException("Pod 仍在删除中，请稍后重试");
        }
        db.cancelSchedulesOfInstance(instanceId);
        db.setStatus(instanceId, "stopped");
        db.notifyInstance((String) inst.get("tenant_id"), instanceId, "实例", "实例已关机",
                "实例 " + instanceId + " 已关机：停止计费、数据保留，7 天内开机数据不丢。");
        return Map.of("status", "stopped", "message", "已关机：停止计费、数据保留（7 天保留期）");
    }

    /** 开机：重建 Pod，就绪后恢复计费 */
    public Map<String, Object> powerOn(String instanceId) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if (!"stopped".equals(String.valueOf(inst.get("status")))) {
            throw new BizException("仅已关机的实例可以开机");
        }
        Timestamp expire = (Timestamp) inst.get("expire_at");
        if (expire != null && expire.getTime() < System.currentTimeMillis()) {
            throw new BizException("实例已到期，请先续费后再开机");
        }
        String ns = "tenant-" + inst.get("tenant_id");
        k8s.create(ns, (String) inst.get("pod_name"), ((Number) inst.get("gpu_count")).intValue(),
                (String) inst.get("image_ref"), diskGbOf(inst));
        db.setStatus(instanceId, "provisioning");
        db.notifyInstance((String) inst.get("tenant_id"), instanceId, "实例", "实例开机中",
                "实例 " + instanceId + " 已提交开机，就绪后自动恢复计费。");
        return Map.of("status", "provisioning", "message", "开机中，就绪后自动恢复计费");
    }

    /** 重启：删 Pod 后立即重建。用户端与管理端共用这一条路径，两边行为完全一致 */
    public Map<String, Object> restart(String instanceId) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if (!"running".equals(String.valueOf(inst.get("status")))) {
            throw new BizException("仅运行中的实例可以重启");
        }
        String ns = "tenant-" + inst.get("tenant_id");
        String pod = (String) inst.get("pod_name");
        if (!k8s.delete(ns, pod)) {
            throw new BizException("Pod 仍在删除中，请稍后重试");
        }
        k8s.create(ns, pod, ((Number) inst.get("gpu_count")).intValue(),
                (String) inst.get("image_ref"), diskGbOf(inst));
        db.setStatus(instanceId, "provisioning");
        db.notifyInstance((String) inst.get("tenant_id"), instanceId, "实例", "实例重启中",
                "实例 " + instanceId + " 已提交重启，约数秒后恢复。");
        return Map.of("status", "provisioning", "message", "实例重启中，就绪后自动恢复运行");
    }

    /** 重置系统：系统盘还原为镜像初始状态（真实集群需镜像快照组件，此处按生命周期模拟） */
    public Map<String, Object> resetSystem(String instanceId) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if (!"running".equals(String.valueOf(inst.get("status")))) {
            throw new BizException("仅运行中的实例可以重置系统");
        }
        db.setStatus(instanceId, "resetting");
        db.notifyInstance((String) inst.get("tenant_id"), instanceId, "实例", "实例重置中",
                "实例 " + instanceId + " 系统盘重置中，约数秒后恢复。数据盘不受影响。");
        return Map.of("status", "resetting", "message", "系统盘重置中，数据盘数据不受影响");
    }

    /** 克隆：按原规格与剩余时长开新实例（新订单、新计费），原实例不动 */
    public Map<String, Object> cloneInstance(String instanceId, String tenantId) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        Map<String, Object> order = db.orderById((String) inst.get("order_id"));
        String sku = order == null ? "1" : String.valueOf(order.get("sku"));
        int gpuCount = ((Number) inst.get("gpu_count")).intValue();
        if ("whole".equals(sku) == false && gpuCount > 1) {
            sku = String.valueOf(gpuCount);
        }
        long remainMs = ((Timestamp) inst.get("expire_at")).getTime() - System.currentTimeMillis();
        int hours = Math.max(1, Math.min(720, (int) Math.ceil(remainMs / 3600_000.0)));
        return createOrder(tenantId, sku, hours, "clone-" + instanceId + "-" + System.currentTimeMillis(),
                (String) inst.get("image_ref"), diskGbOf(inst));
    }

    /** 升降配：先按新规格下单占卡，成功后再结算释放旧实例（旧实例删除失败会留给巡检重试，不会丢卡） */
    public Map<String, Object> resize(String instanceId, int newCount) {
        if (newCount < 1 || newCount > 8) {
            throw new BizException("卡数必须是 1~8");
        }
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if (!"running".equals(String.valueOf(inst.get("status")))) {
            throw new BizException("仅运行中的实例可以升降配");
        }
        if (((Number) inst.get("gpu_count")).intValue() == newCount) {
            throw new BizException("卡数没有变化");
        }
        String tenantId = (String) inst.get("tenant_id");
        String sku = newCount == 8 ? "whole" : String.valueOf(newCount);
        long remainMs = ((Timestamp) inst.get("expire_at")).getTime() - System.currentTimeMillis();
        int hours = Math.max(1, Math.min(720, (int) Math.ceil(remainMs / 3600_000.0)));
        Map<String, Object> created = createOrder(tenantId, sku, hours, "resize-" + instanceId + "-" + newCount,
                (String) inst.get("image_ref"), diskGbOf(inst));
        db.notifyInstance(tenantId, instanceId, "实例", "升降配已提交",
                "新实例 " + created.get("instanceId") + "（" + newCount + " 卡）已创建，旧实例 " + instanceId + " 进入回收结算。");
        try {
            release(instanceId);
        } catch (BizException e) {
            return Map.of("status", "partial", "message",
                    "新实例已就绪，旧实例回收中（巡检会自动结算，不会丢卡）: " + e.getMessage(),
                    "newInstance", created.get("instanceId"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "resized");
        out.put("newInstanceId", created.get("instanceId"));
        out.put("message", "升降配完成：新实例 " + created.get("instanceId") + "（" + newCount + " 卡），旧实例已按秒结算释放");
        return out;
    }

    // ------------------------------------------------------------------ 续费

    /** 续费：余额再预扣 hours 的时长；预扣池(orders.amount)累加、到期时间顺延，结算口径不变 */
    public Map<String, Object> renew(String instanceId, int hours) {
        if (hours <= 0 || hours > 720) {
            throw new BizException("时长必须是 1~720 小时");
        }
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if (!"running".equals(String.valueOf(inst.get("status")))) {
            throw new BizException("仅运行中的实例可以续费");
        }
        String tenantId = String.valueOf(inst.get("tenant_id"));
        int gpuCount = ((Number) inst.get("gpu_count")).intValue();
        // 续费必须与下单同一口径：按 SKU 与租户的真实单价，数据盘也要继续计费，
        // 否则闲时卡续费按全价扣、带扩容盘的实例续费漏收盘钱
        Map<String, Object> priceTenant = db.tenant(tenantId);
        Map<String, Object> orderRow = db.orderById(String.valueOf(inst.get("order_id")));
        String skuCode = orderRow == null ? null : String.valueOf(orderRow.get("sku"));
        BigDecimal unitPrice = unitPriceFor(skuCode, hours, priceTenant);
        BigDecimal amount = amountOf(Math.max(gpuCount, 1), hours * 3600L, unitPrice)
                .add(dataDiskFee(diskGbOf(inst), hours))
                .setScale(2, RoundingMode.HALF_UP);

        return tx.execute(status -> {
            Map<String, Object> tenant = db.tenant(tenantId);
            BigDecimal balance = (BigDecimal) tenant.get("balance_yuan");
            if (balance.compareTo(amount) < 0) {
                throw new BizException("余额不足：续费需预扣 " + amount + " 元，可用 " + balance + " 元");
            }
            Timestamp oldExpire = (Timestamp) inst.get("expire_at");
            long base = oldExpire != null ? oldExpire.getTime() : System.currentTimeMillis();
            Timestamp newExpire = new Timestamp(base + hours * 3600_000L);
            db.deductBalance(tenantId, amount);
            db.addOrderAmount((String) inst.get("order_id"), amount);
            db.setExpire(instanceId, newExpire);
            db.notifyInstance(tenantId, instanceId, "账单", "实例已续费",
                    "实例 " + instanceId + " 已续费 " + hours + " 小时，预扣 ¥" + amount
                            + "，到期时间顺延至 " + newExpire + "。");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "renewed");
            m.put("prepaid", amount);
            m.put("expireAt", newExpire);
            m.put("message", "续费成功，预扣 ¥" + amount + "，到期时间顺延至 " + newExpire);
            return m;
        });
    }

    // ------------------------------------------------------------------ 释放

    public Map<String, Object> release(String instanceId) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new BizException("实例不存在: " + instanceId);
        }
        if ("released".equals(inst.get("status"))) {
            return Map.of("status", "released", "message", "实例已释放，无需重复操作");
        }

        String podName = (String) inst.get("pod_name");
        String namespace = "tenant-" + inst.get("tenant_id");
        boolean gone = k8s.delete(namespace, podName);
        if (!gone) {
            // 关键：Pod 没删掉就绝不改数据库。标记 releasing，交给巡检重试。
            db.setStatus(instanceId, "releasing");
            throw new BizException("Pod 仍在 Terminating，实例置为 releasing，等待巡检重试（不会丢卡，也不会重复计费）");
        }
        // 释放 = 数据一起销毁，这是和关机最大的区别
        k8s.deleteData(namespace, podName);
        return settleAndRelease(inst);
    }

    /** 结算 + 释放：按实际秒数算钱，预扣的差额退回余额 */
    public Map<String, Object> settleAndRelease(Map<String, Object> inst) {
        String instanceId = (String) inst.get("id");
        String tenantId = (String) inst.get("tenant_id");
        int gpuCount = ((Number) inst.get("gpu_count")).intValue();
        long seconds = ((Number) inst.get("used_seconds")).longValue();

        return tx.execute(status -> {
            // 结算按订单快照价：下单后管理员改价不影响已售订单（预扣与实结必须同一口径，退款才对得上）
            Map<String, Object> order = db.orderById((String) inst.get("order_id"));
            BigDecimal orderPrice = order == null ? unitPrice() : (BigDecimal) order.get("unit_price");
            BigDecimal prepaid = order == null ? BigDecimal.ZERO : (BigDecimal) order.get("amount");
            int prepaidHours = order == null ? 0 : ((Number) order.get("hours")).intValue();
            long prepaidSeconds = Math.max(1L, prepaidHours * 3600L);
            // 无卡调试按 1 个计费单位收，和下单预扣同一口径，否则调试机释放时全额退款等于白用
            int billUnits = Math.max(gpuCount, 1);
            // 预扣里的数据盘部分单独拆出来按已用时长比例实结，费率中途被管理员改动也不会算错账
            BigDecimal gpuPrepaid = amountOf(billUnits, prepaidSeconds, orderPrice);
            BigDecimal diskPrepaid = prepaid.subtract(gpuPrepaid).max(BigDecimal.ZERO);
            BigDecimal actual = amountOf(billUnits, seconds, orderPrice)
                    .add(diskPrepaid.multiply(BigDecimal.valueOf(seconds))
                            .divide(BigDecimal.valueOf(prepaidSeconds), 2, RoundingMode.HALF_UP))
                    .setScale(2, RoundingMode.HALF_UP);
            // 实结上限就是预扣池：拼团价换算成小时单价除不尽（1300/720）时会多出几分钱，
            // 到期释放不能倒扣租户，超出的零头由平台承担。
            if (actual.compareTo(prepaid) > 0) {
                actual = prepaid;
            }
            if (seconds > 0) {
                db.insertBilling(instanceId, tenantId, gpuCount, seconds, orderPrice, actual);
            }

            BigDecimal refund = prepaid.subtract(actual);
            if (refund.signum() != 0) {
                db.addBalance(tenantId, refund);
            }

            // 释放前先记录这批卡属于哪些机型，卡归还后给订阅了「到货提醒」的租户发通知并完结订阅
            List<String> models = db.q("SELECT DISTINCT model FROM gpus WHERE instance_id=?", instanceId)
                    .stream().map(r -> String.valueOf(r.get("model"))).toList();

            db.markReleased(instanceId);
            db.releaseByInstance(instanceId);
            db.notifyInstance(tenantId, instanceId, "账单", "实例已释放",
                    "实例 " + instanceId + " 已释放，实结 ¥" + actual + "，预扣差额 ¥" + refund + " 已退回余额。");

            for (String model : models) {
                for (Map<String, Object> alert : db.openStockAlerts(model)) {
                    db.insertNotification(newId("n"), String.valueOf(alert.get("tenant_id")), "库存",
                            "到货提醒 · " + model, "您关注的 " + model + " 当前有可用库存，快去下单吧。");
                    db.fulfillStockAlert((String) alert.get("id"));
                }
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "released");
            m.put("usedSeconds", seconds);
            m.put("actualAmount", actual);
            m.put("refund", refund);
            return m;
        });
    }

    // ------------------------------------------------------------------ 拼团

    /** 拼团卡实时进度：目录静态数据 + group_joins 里的真实拼单（forming / formed 都算占坑） */
    public Map<String, Object> groupCard(String cardKey) {
        Map<String, Object> card = groupCatalog.card(cardKey);
        if (card == null) {
            throw new BizException("拼团机型不存在: " + cardKey);
        }
        int total = ((Number) card.get("total")).intValue();
        int base = ((Number) card.get("done")).intValue();
        int joined = db.activeJoinsOfCard(cardKey).stream()
                .mapToInt(r -> ((Number) r.get("qty")).intValue()).sum();
        card.put("joined", joined);
        card.put("done", Math.min(total, base + joined));
        card.put("left", Math.max(0, total - base - joined));
        return card;
    }

    /**
     * 立即拼单：预扣整月拼团价 → 落拼单记录 → 满员自动成团开机。
     * 成团前随时可取消（预付款原路全额退回），成团后不可退，与页面「拼机须知」一致。
     */
    public Map<String, Object> groupJoin(String tenantId, String cardKey, int qty) {
        Map<String, Object> card = groupCard(cardKey);
        if (qty < 1 || qty > 8) {
            throw new BizException("拼租数量必须是 1~8 张卡");
        }
        int left = ((Number) card.get("left")).intValue();
        if (left <= 0) {
            throw new BizException("该团已满员，请选择其他机型或地区");
        }
        if (qty > left) {
            throw new BizException("该团仅剩 " + left + " 张卡，拼不了 " + qty + " 张");
        }
        if (db.formingJoinByTenantCard(tenantId, cardKey) != null) {
            throw new BizException("您已在这个团里，可在「我的拼团」取消后再重新拼单");
        }
        Map<String, Object> tenant = db.tenant(tenantId);
        if (tenant == null) {
            throw new BizException("租户不存在: " + tenantId);
        }

        BigDecimal prepay = BigDecimal.valueOf(((Number) card.get("price")).longValue() * qty);
        BigDecimal balance = (BigDecimal) tenant.get("balance_yuan");
        if (balance.compareTo(prepay) < 0) {
            throw new BizException("余额不足：拼团预付款 ¥" + money(prepay) + "，可用 " + money(balance) + " 元");
        }

        String joinId = newId("g");
        String model = (String) card.get("model");
        int vram = ((Number) card.get("vram")).intValue();
        String zone = (String) card.get("zone");
        tx.executeWithoutResult(status -> {
            db.deductBalance(tenantId, prepay);
            db.insertGroupJoin(joinId, cardKey, model, vram, zone, tenantId, qty, prepay);
        });
        db.insertNotification(newId("n"), tenantId, "拼团", "拼单已提交",
                model + " " + vram + "G（" + zone + "）拼 " + qty + " 张卡，预付款 ¥" + money(prepay)
                        + " 已扣；满员自动成团，成团前取消全额退回。");

        Map<String, Object> after = groupCard(cardKey);
        List<Map<String, Object>> formed = List.of();
        if (((Number) after.get("left")).intValue() == 0) {
            formed = formGroup(cardKey);
            after = groupCard(cardKey);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("joinId", joinId);
        out.put("prepay", prepay);
        out.put("card", after);
        out.put("formed", formed);
        out.put("status", formed.isEmpty() ? "forming" : "formed");
        out.put("message", formed.isEmpty()
                ? "拼单成功，再拼 " + after.get("left") + " 张卡即可起飞"
                : "拼团成功，实例已创建");
        return out;
    }

    /**
     * 成团：把这个团里所有 forming 拼单转成真实实例（720 小时 / 拼团价）。
     * 每一单独立结算，某一单失败（配额不足、无空闲卡）只影响它自己：预付款退回并标记 failed，
     * 其余照常成团，不会因为一个人失败把整团卡住。
     */
    public List<Map<String, Object>> formGroup(String cardKey) {
        Map<String, Object> card = groupCatalog.card(cardKey);
        if (card == null) {
            throw new BizException("拼团机型不存在: " + cardKey);
        }
        // 拼团价是「元 / 卡 / 月」，换算成平台统一的「元 / 卡 / 小时」快照价，结算口径与普通订单一致
        BigDecimal unitPrice = BigDecimal.valueOf(((Number) card.get("price")).longValue())
                .divide(BigDecimal.valueOf(720), 4, RoundingMode.HALF_UP);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> join : db.formingJoinsOfCard(cardKey)) {
            String joinId = (String) join.get("id");
            String tenantId = (String) join.get("tenant_id");
            int qty = ((Number) join.get("qty")).intValue();
            BigDecimal prepay = (BigDecimal) join.get("price_yuan");

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("joinId", joinId);
            r.put("tenantId", tenantId);
            r.put("qty", qty);
            // 预付款转订单：先原路退回再按拼团价下单，余额流水能对上账，失败也不会重复退款
            db.addBalance(tenantId, prepay);
            try {
                Map<String, Object> created = createOrderPriced(tenantId, String.valueOf(qty), 720,
                        "grp-" + joinId, unitPrice, prepay, null, 0);
                db.setGroupJoinStatus(joinId, "formed", (String) created.get("instanceId"), null);
                db.insertNotification(newId("n"), tenantId, "拼团", "拼团成功",
                        card.get("model") + " " + card.get("vram") + "G（" + card.get("zone") + "）已成团，实例 "
                                + created.get("instanceId") + " 已创建：" + qty + " 张卡 / 720 小时。");
                r.put("status", "formed");
                r.put("instanceId", created.get("instanceId"));
            } catch (BizException e) {
                db.setGroupJoinStatus(joinId, "failed", null, e.getMessage());
                db.insertNotification(newId("n"), tenantId, "拼团", "拼团失败，预付款已退回",
                        "成团时创建实例失败（" + e.getMessage() + "），预付款 ¥" + money(prepay) + " 已退回余额。");
                r.put("status", "failed");
                r.put("reason", e.getMessage());
            }
            results.add(r);
        }
        return results;
    }

    /** 取消拼单：仅 forming 可取消，预付款原路全额退回（管理员代撤传 tenantId=null） */
    public Map<String, Object> cancelGroupJoin(String joinId, String tenantId, boolean byAdmin) {
        Map<String, Object> join = db.groupJoin(joinId);
        if (join == null) {
            throw new BizException("拼单不存在: " + joinId);
        }
        String owner = (String) join.get("tenant_id");
        if (tenantId != null && !owner.equals(tenantId)) {
            throw new BizException("无权操作他人的拼单");
        }
        String st = String.valueOf(join.get("status"));
        if (!"forming".equals(st)) {
            throw new BizException("formed".equals(st)
                    ? "拼团已成功，不可取消退费（可在实例列表按秒释放）"
                    : "该拼单已是 " + st + " 状态，无需取消");
        }
        BigDecimal prepay = ((BigDecimal) join.get("price_yuan")).setScale(2, RoundingMode.HALF_UP);
        tx.executeWithoutResult(s -> {
            db.setGroupJoinStatus(joinId, "cancelled", null, byAdmin ? "管理员撤销" : "用户取消");
            db.addBalance(owner, prepay);
        });
        db.insertNotification(newId("n"), owner, "拼团", "拼单已取消",
                join.get("model") + " " + join.get("vram") + "G（" + join.get("zone") + "）拼单已取消，预付款 ¥"
                        + money(prepay) + " 已原路退回余额。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "cancelled");
        out.put("refund", prepay);
        out.put("card", groupCard((String) join.get("card_key")));
        out.put("message", "拼单已取消，预付款 ¥" + money(prepay) + " 已退回余额");
        return out;
    }
}
