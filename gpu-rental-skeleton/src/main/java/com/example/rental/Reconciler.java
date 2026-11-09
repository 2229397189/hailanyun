package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 巡检与状态推进。每 5 秒一次，做三件事：
 *
 * 1. provisioning → running（Pod 真的 Ready 了才转）
 * 2. running：累加计费秒数；到期自动回收
 * 3. releasing：重试删除卡住的 Pod
 *
 * 这三件事就是“卡不丢”的全部保障。真实环境还要加：节点 NotReady 时的实例状态纠正。
 */
@Component
public class Reconciler {

    private static final Logger log = LoggerFactory.getLogger(Reconciler.class);
    private static final long TICK_SECONDS = 5;

    private final Db db;
    private final K8sClient k8s;
    private final RentalService rental;
    private final PlatformExtService platformExt;

    public Reconciler(Db db, K8sClient k8s, RentalService rental, PlatformExtService platformExt) {
        this.db = db;
        this.k8s = k8s;
        this.rental = rental;
        this.platformExt = platformExt;
    }

    @Scheduled(fixedDelay = TICK_SECONDS * 1000L, initialDelay = 2000)
    public void tick() {
        promoteProvisioning();
        chargeRunning();
        retryReleasing();
        db.promoteWorkloads();
        runSchedules();
        forcePowerOffArrears();
        expireStopped();
        finishResetting();
        // A-7：竞价市场价超过出价上限时发通知并（宽限期后）强制回收
        try {
            platformExt.checkSpotInterruptions();
        } catch (Exception e) {
            log.warn("竞价巡检异常：{}", e.getMessage());
        }
    }

    /** 定时关机：到点的 pending 计划，对运行中实例执行关机并完结计划 */
    private void runSchedules() {
        for (Map<String, Object> s : db.pendingSchedules("shutdown")) {
            String scheduleId = (String) s.get("id");
            String instanceId = (String) s.get("instance_id");
            Map<String, Object> inst = db.instance(instanceId);
            if (inst == null || !"running".equals(String.valueOf(inst.get("status")))) {
                // 实例已被用户自己关掉或释放，计划无需执行，直接完结
                db.finishSchedule(scheduleId);
                continue;
            }
            // 先完结再关机：powerOff 会取消该实例剩余的待执行计划，顺序反了这条会显示成「已取消」
            db.finishSchedule(scheduleId);
            try {
                rental.powerOff(instanceId);
                log.info("实例 {} 定时关机已执行", instanceId);
            } catch (Exception e) {
                log.warn("实例 {} 定时关机失败：{}", instanceId, e.getMessage());
                db.notifyInstance((String) inst.get("tenant_id"), instanceId, "实例", "定时关机未成功",
                        "实例 " + instanceId + " 的定时关机执行失败（" + e.getMessage() + "），仍在运行计费，请手动关机。");
            }
        }
    }

    /** 欠费强制关机：余额为负的租户，运行中实例全部关机停计费（数据保留，充值后开机） */
    private void forcePowerOffArrears() {
        for (Map<String, Object> t : db.tenantsWithNegativeBalance()) {
            String tenantId = (String) t.get("id");
            for (Map<String, Object> inst : db.runningInstancesOfTenant(tenantId)) {
                String id = (String) inst.get("id");
                try {
                    rental.powerOff(id);
                    log.info("租户 {} 欠费，实例 {} 已强制关机", tenantId, id);
                } catch (Exception e) {
                    log.warn("欠费关机未完成 {}：{}", id, e.getMessage());
                }
            }
        }
    }

    /** 关机保留期：到期后 7 天内可开机，超过 7 天自动释放（A-25 关机保留期与自动释放规则） */
    private void expireStopped() {
        long now = System.currentTimeMillis();
        for (Map<String, Object> inst : db.instancesByStatus("stopped")) {
            Timestamp expire = (Timestamp) inst.get("expire_at");
            if (expire != null && now > expire.getTime() + 7L * 24 * 3600_000L) {
                String id = (String) inst.get("id");
                log.info("实例 {} 关机超 7 天保留期，自动释放", id);
                try {
                    rental.release(id);
                } catch (Exception e) {
                    log.warn("实例 {} 保留期释放未完成：{}", id, e.getMessage());
                }
            }
        }
    }

    /** 重置后必须等重建的 Pod 真的 Ready 才恢复 running，避免状态先于容器就绪 */
    private void finishResetting() {
        for (Map<String, Object> inst : db.instancesByStatus("resetting")) {
            String pod = (String) inst.get("pod_name");
            String ns = "tenant-" + inst.get("tenant_id");
            if (pod != null && k8s.ready(ns, pod)) {
                db.markRunning((String) inst.get("id"));
                log.info("实例 {} 系统重置完成，容器已就绪", inst.get("id"));
            }
        }
    }

    private void promoteProvisioning() {
        List<Map<String, Object>> list = db.instancesByStatus("provisioning");
        for (Map<String, Object> inst : list) {
            String pod = (String) inst.get("pod_name");
            String ns = "tenant-" + inst.get("tenant_id");
            if (pod != null && k8s.ready(ns, pod)) {
                db.markRunning((String) inst.get("id"));
                log.info("实例 {} 已就绪", inst.get("id"));
            }
        }
    }

    private void chargeRunning() {
        long now = System.currentTimeMillis();
        for (Map<String, Object> inst : db.instancesByStatus("running")) {
            String id = (String) inst.get("id");
            db.addUsedSeconds(id, TICK_SECONDS);
            // A-9：当前开放的计费段同步累计秒数与金额，分段账随时可核对
            try {
                int units = Math.max(1, ((Number) inst.get("gpu_count")).intValue());
                db.update("UPDATE billing_segments SET seconds = seconds + ? "
                        + "WHERE instance_id=? AND closed_at IS NULL", TICK_SECONDS, id);
                // 用更新后的 seconds 重算金额，避免 SET 子句引用旧值导致少算一个 tick
                db.update("UPDATE billing_segments SET amount = "
                                + "ROUND((unit_price * ? * seconds / 3600.0)::numeric, 2) "
                                + "WHERE instance_id=? AND closed_at IS NULL",
                        units, id);
            } catch (Exception e) {
                log.debug("计费段累计跳过 {}：{}", id, e.getMessage());
            }

            Timestamp expire = (Timestamp) inst.get("expire_at");
            if (expire != null && now > expire.getTime()) {
                log.info("实例 {} 到期，自动回收", id);
                try {
                    rental.release(id);
                } catch (Exception e) {
                    log.warn("实例 {} 到期回收未完成：{}", id, e.getMessage());
                }
            }
        }
    }

    private void retryReleasing() {
        for (Map<String, Object> inst : db.instancesByStatus("releasing")) {
            String id = (String) inst.get("id");
            String pod = (String) inst.get("pod_name");
            String ns = "tenant-" + inst.get("tenant_id");
            if (k8s.delete(ns, pod)) {
                k8s.deleteData(ns, pod);
                Map<String, Object> fresh = db.instance(id);
                if (fresh != null) {
                    rental.settleAndRelease(fresh);
                    log.info("实例 {} 补删成功，已结算释放", id);
                }
            }
        }
    }
}
