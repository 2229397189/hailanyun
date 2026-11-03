package com.example.rental;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
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

    /** 单价：元 / 卡 / 小时 */
    public static final BigDecimal UNIT_PRICE = new BigDecimal("2.80");

    private final Db db;
    private final K8sClient k8s;
    private final TransactionTemplate tx;

    public RentalService(Db db, K8sClient k8s, PlatformTransactionManager tm) {
        this.db = db;
        this.k8s = k8s;
        this.tx = new TransactionTemplate(tm);
    }

    public static class BizException extends RuntimeException {
        public BizException(String message) {
            super(message);
        }
    }

    /** 金额 = 单价 × 卡数 × 秒数 / 3600，保留两位（四舍五入） */
    public static BigDecimal amountOf(int gpuCount, long seconds) {
        return UNIT_PRICE
                .multiply(BigDecimal.valueOf(gpuCount))
                .multiply(BigDecimal.valueOf(seconds))
                .divide(BigDecimal.valueOf(3600L), 2, RoundingMode.HALF_UP);
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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

    public Map<String, Object> createOrder(String tenantId, String sku, int hours, String idemKey) {
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

        int gpuCount = "whole".equals(sku) ? 8 : 1;
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

            BigDecimal amount = amountOf(gpuCount, hours * 3600L);
            BigDecimal balance = (BigDecimal) tenant.get("balance_yuan");
            if (balance.compareTo(amount) < 0) {
                throw new BizException("余额不足：需预扣 " + amount + " 元，可用 " + balance + " 元");
            }

            List<String> gpuIds;
            String nodeId;
            if ("whole".equals(sku)) {
                Map<String, Object> node = db.pickWholeNode(gpuCount);
                if (node == null) {
                    throw new BizException("无整机可用：需要同一节点上 " + gpuCount + " 张空闲卡");
                }
                nodeId = (String) node.get("node_id");
                gpuIds = db.freeGpuIdsOfNode(nodeId, gpuCount);
            } else {
                gpuIds = db.pickSingleGpuIds(gpuCount);
                if (gpuIds.size() < gpuCount) {
                    throw new BizException("已售罄：当前无空闲卡");
                }
                nodeId = (String) db.one("SELECT node_id FROM gpus WHERE id=?", gpuIds.get(0)).get("node_id");
            }

            // 占卡用条件更新（status='free'），并发下不会把同一张卡卖给两个人
            int rows = db.occupy(gpuIds, instanceId);
            if (rows != gpuCount) {
                throw new BizException("资源被并发占用，请重试");
            }

            db.insertOrder(orderId, idemKey, tenantId, sku, gpuCount, hours, UNIT_PRICE, amount);
            db.insertInstance(instanceId, orderId, tenantId, nodeId, String.join(",", gpuIds),
                    gpuCount, podName,
                    new Timestamp(System.currentTimeMillis() + hours * 3600_000L));
            db.deductBalance(tenantId, amount);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", instanceId);
            m.put("orderId", orderId);
            m.put("nodeId", nodeId);
            m.put("gpuIds", gpuIds);
            m.put("prepaid", amount);
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
            k8s.create("tenant-" + tenantId, podName, gpuCount);
        } catch (Exception e) {
            db.setStatus(instanceId, "failed");
            db.releaseByInstance(instanceId);
            throw new BizException("实例创建失败，已回滚占卡：" + e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reused", false);
        out.put("message", "下单成功，实例创建中");
        out.putAll(created);
        return out;
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
        return settleAndRelease(inst);
    }

    /** 结算 + 释放：按实际秒数算钱，预扣的差额退回余额 */
    public Map<String, Object> settleAndRelease(Map<String, Object> inst) {
        String instanceId = (String) inst.get("id");
        String tenantId = (String) inst.get("tenant_id");
        int gpuCount = ((Number) inst.get("gpu_count")).intValue();
        long seconds = ((Number) inst.get("used_seconds")).longValue();

        return tx.execute(status -> {
            BigDecimal actual = amountOf(gpuCount, seconds);
            if (seconds > 0) {
                db.insertBilling(instanceId, tenantId, gpuCount, seconds, UNIT_PRICE, actual);
            }

            Map<String, Object> order = db.orderById((String) inst.get("order_id"));
            BigDecimal prepaid = order == null ? BigDecimal.ZERO : (BigDecimal) order.get("amount");
            BigDecimal refund = prepaid.subtract(actual);
            if (refund.signum() != 0) {
                db.addBalance(tenantId, refund);
            }

            db.markReleased(instanceId);
            db.releaseByInstance(instanceId);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "released");
            m.put("usedSeconds", seconds);
            m.put("actualAmount", actual);
            m.put("refund", refund);
            return m;
        });
    }
}
