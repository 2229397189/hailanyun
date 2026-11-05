package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * 合规日志留存策略（C-27）：MaaS 请求日志按天数保留，超期自动清理。
 *
 * 保留天数走 settings.maas.log_retention_days，默认 180 天（可配成等保要求的期限）。
 * 每天扫一次，只删 maas_request_logs，不动账单与审计（那两类按合规要求单独保留）。
 */
@Component
public class LogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionJob.class);

    private final Db db;

    public LogRetentionJob(Db db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 24 * 3600_000L, initialDelay = 300_000L)
    public void purge() {
        int days;
        try {
            String v = db.setting("maas.log_retention_days");
            days = v == null || v.isBlank() ? 180 : Integer.parseInt(v.trim());
            if (days < 7 || days > 3650) {
                log.warn("maas.log_retention_days={} 非法，跳过清理", days);
                return;
            }
        } catch (Exception e) {
            days = 180;
        }
        Timestamp before = new Timestamp(System.currentTimeMillis() - days * 24L * 3600_000L);
        int n = db.update("DELETE FROM maas_request_logs WHERE created_at < ?", before);
        if (n > 0) {
            log.info("日志留存清理：删除 {} 条超过 {} 天的 MaaS 请求日志", n, days);
            db.insertAudit("log-retention", "MAAS_LOG_PURGE", "maas_request_logs", "超过 " + days + " 天，删 " + n + " 条");
        }
    }
}
