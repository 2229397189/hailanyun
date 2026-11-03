package com.example.rental;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 把业务状态暴露成 Prometheus 指标。
 *
 * 为什么要在 Java 侧做：Prometheus 只能抓「暴露出来的数」，
 * 而「运行实例数、占用卡数、活跃告警」这些只有应用自己知道。
 * Gauge 的取值函数在每次 scrape 时才执行，等于每次抓取查一次轻量 COUNT。
 */
@Component
public class MetricsExporter {

    public MetricsExporter(MeterRegistry registry, Db db) {
        gauge(registry, db, "gpu_rental_instances_running", "运行中的算力实例数",
                "SELECT COUNT(*) AS c FROM instances WHERE status='running'");
        gauge(registry, db, "gpu_rental_instances_provisioning", "下发中的实例数",
                "SELECT COUNT(*) AS c FROM instances WHERE status='provisioning'");
        gauge(registry, db, "gpu_rental_gpus_occupied", "已占用显卡数",
                "SELECT COUNT(*) AS c FROM gpus WHERE status='occupied'");
        gauge(registry, db, "gpu_rental_gpus_free", "空闲显卡数",
                "SELECT COUNT(*) AS c FROM gpus WHERE status='free'");
        gauge(registry, db, "gpu_rental_gpus_fault", "故障显卡数",
                "SELECT COUNT(*) AS c FROM gpus WHERE status='fault'");
        gauge(registry, db, "gpu_rental_tenants_active", "服务中的租户数",
                "SELECT COUNT(*) AS c FROM tenants WHERE status='active'");
    }

    private void gauge(MeterRegistry registry, Db db, String name, String desc, String sql) {
        Gauge.builder(name, db, d -> scalar(d, sql))
                .description(desc)
                .register(registry);
    }

    /** 取第一行第一列，失败返回 0 —— 指标异常不能把业务带崩 */
    private static double scalar(Db db, String sql) {
        try {
            Object v = db.q(sql).get(0).values().iterator().next();
            return v == null ? 0 : ((Number) v).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }
}
