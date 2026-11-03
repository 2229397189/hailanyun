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

    public Reconciler(Db db, K8sClient k8s, RentalService rental) {
        this.db = db;
        this.k8s = k8s;
        this.rental = rental;
    }

    @Scheduled(fixedDelay = TICK_SECONDS * 1000L, initialDelay = 2000)
    public void tick() {
        promoteProvisioning();
        chargeRunning();
        retryReleasing();
        db.promoteWorkloads();
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
                Map<String, Object> fresh = db.instance(id);
                if (fresh != null) {
                    rental.settleAndRelease(fresh);
                    log.info("实例 {} 补删成功，已结算释放", id);
                }
            }
        }
    }
}
