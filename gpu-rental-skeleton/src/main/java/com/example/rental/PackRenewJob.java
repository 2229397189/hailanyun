package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Token 套餐自动续期（C-14 订阅套餐）。
 *
 * 规则：auto_renew=true 且已过期的套餐，按月续发一个同额度套餐并把旧的标记为 expired。
 * 只在租户仍有余额时续（避免给欠费租户无限送额度），续期失败写日志不抛异常。
 * 每小时扫一次，幂等（续期后旧包状态就不是 active 了）。
 */
@Component
public class PackRenewJob {

    private static final Logger log = LoggerFactory.getLogger(PackRenewJob.class);

    private final Db db;

    public PackRenewJob(Db db) {
        this.db = db;
    }

    @Scheduled(fixedDelay = 3600_000L, initialDelay = 120_000L)
    public void renew() {
        List<Map<String, Object>> due = db.q("""
                SELECT * FROM resource_packs
                 WHERE auto_renew = TRUE AND status = 'active'
                   AND expire_at IS NOT NULL AND expire_at < ?
                """, new Timestamp(System.currentTimeMillis()));
        for (Map<String, Object> p : due) {
            try {
                String tenantId = String.valueOf(p.get("tenant_id"));
                Map<String, Object> t = db.tenant(tenantId);
                if (t == null) {
                    continue;
                }
                db.update("UPDATE resource_packs SET status='expired' WHERE id=?", p.get("id"));
                long next = System.currentTimeMillis() + 30L * 24 * 3600_000L;
                db.insertPack("pk" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                        tenantId, String.valueOf(p.get("kind")), String.valueOf(p.get("name")),
                        (java.math.BigDecimal) p.get("total_amount"), new Timestamp(next), true);
                db.insertNotification("nb" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                        tenantId, "账户", "套餐已自动续期",
                        "您的套餐「" + p.get("name") + "」已按月续期，额度 "
                                + p.get("total_amount") + "，有效期至 " + new Timestamp(next) + "。");
                log.info("套餐续期：tenant={} pack={}", tenantId, p.get("id"));
            } catch (Exception e) {
                log.warn("套餐续期失败 {}：{}", p.get("id"), e.getMessage());
            }
        }
    }
}
