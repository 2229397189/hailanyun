package com.example.rental;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端业务运营：商务需求跟进、拼团管理、到货提醒处置、代租户操作实例。
 *
 * 关键是最后一段：管理端对用户实例的关机 / 开机 / 重启 / 释放 / 续费，调的是和用户端
 * 完全相同的 RentalService 方法，所以两边行为、计费、通知口径一模一样，
 * 不会出现「管理员点一下只是改个状态、卡还占着」这种半截功能。
 */
@RestController
@RequestMapping("/api/admin")
public class BizAdminApi {

    private final Db db;
    private final RentalService rental;
    private final GroupCatalog groupCatalog;

    public BizAdminApi(Db db, RentalService rental, GroupCatalog groupCatalog) {
        this.db = db;
        this.rental = rental;
        this.groupCatalog = groupCatalog;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String str(Object v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private String tenantName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "未登录访客";
        }
        Map<String, Object> t = db.tenant(tenantId);
        return t == null ? tenantId : String.valueOf(t.get("name"));
    }

    // ============================================================ 商务需求

    @GetMapping("/leads")
    public Map<String, Object> leads(@RequestParam(required = false) String status) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> l : db.leads(status)) {
            Map<String, Object> m = new LinkedHashMap<>(l);
            m.put("tenantName", tenantName((String) l.get("tenant_id")));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("newCount", db.leads("new").size());
        out.put("followingCount", db.leads("following").size());
        return out;
    }

    /** 跟进需求：改状态 + 写回复，回复会推到租户消息中心（访客需求没有账号，只在列表里可见） */
    @PostMapping("/leads/{id}/follow")
    public Map<String, Object> followLead(@PathVariable String id,
                                          @RequestBody Map<String, Object> body,
                                          @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> lead = db.lead(id);
        if (lead == null) {
            throw new RentalService.BizException("需求不存在: " + id);
        }
        String status = str(body.get("status"), "following");
        if (!List.of("new", "following", "done", "rejected").contains(status)) {
            throw new RentalService.BizException("状态只能是 new / following / done / rejected");
        }
        String reply = str(body.get("reply"), "");
        if (reply.length() > 500) {
            throw new RentalService.BizException("回复请精简到 500 字以内");
        }
        if (("done".equals(status) || "rejected".equals(status)) && reply.isBlank()) {
            throw new RentalService.BizException("结单或驳回必须填写回复，租户要能看到结论");
        }
        db.followLead(id, status, reply);
        db.insertAudit(str(username, "admin"), "LEAD_FOLLOW", id, status + (reply.isBlank() ? "" : " · " + reply));

        String tenantId = (String) lead.get("tenant_id");
        boolean pushed = false;
        if (tenantId != null && !reply.isBlank()) {
            db.insertNotification(newId("n"), tenantId, "商务",
                    "done".equals(status) ? "需求已办结" : "rejected".equals(status) ? "需求已驳回" : "需求跟进中",
                    str(lead.get("model"), str(lead.get("source"), "需求")) + "：" + reply);
            pushed = true;
        }

        Map<String, Object> out = new LinkedHashMap<>(db.lead(id));
        out.put("tenantName", tenantName(tenantId));
        out.put("message", "已更新为 " + status + (pushed
                ? "，回复已推送到租户消息中心"
                : "（访客需求没有线上账号，请电话/微信回访 " + str(lead.get("contact_phone"), "") + "）"));
        return out;
    }

    // ============================================================ 拼团管理

    /** 拼团卡目录 + 实时进度：管理端拼团页与官网读同一份口径 */
    @GetMapping("/group-cards")
    public List<Map<String, Object>> groupCards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : groupCatalog.cards()) {
            String key = (String) c.get("cardKey");
            int total = ((Number) c.get("total")).intValue();
            int base = ((Number) c.get("done")).intValue();
            List<Map<String, Object>> active = db.activeJoinsOfCard(key);
            int joined = active.stream().mapToInt(r -> ((Number) r.get("qty")).intValue()).sum();
            c.put("joined", joined);
            c.put("done", Math.min(total, base + joined));
            c.put("left", Math.max(0, total - base - joined));
            c.put("formingJoins", db.formingJoinsOfCard(key).size());
            out.add(c);
        }
        return out;
    }

    @GetMapping("/group-joins")
    public Map<String, Object> groupJoins(@RequestParam(required = false) String status) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> j : db.groupJoins(status)) {
            Map<String, Object> m = new LinkedHashMap<>(j);
            m.put("tenantName", tenantName((String) j.get("tenant_id")));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("formingCount", db.groupJoins("forming").size());
        out.put("formedCount", db.groupJoins("formed").size());
        return out;
    }

    /** 管理员手动成团：满员会自动成团，这个是给「差一个人但客户催着要」的场景兜底 */
    @PostMapping("/group-cards/form")
    public Map<String, Object> formGroup(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(value = "authUsername", required = false) String username) {
        String cardKey = str(body.get("cardKey"), "");
        List<Map<String, Object>> results = rental.formGroup(cardKey);
        if (results.isEmpty()) {
            throw new RentalService.BizException("该团没有待成团的拼单");
        }
        db.insertAudit(str(username, "admin"), "GROUP_FORM", cardKey,
                "成团 " + results.size() + " 单，成功 "
                        + results.stream().filter(r -> "formed".equals(r.get("status"))).count() + " 单");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cardKey", cardKey);
        out.put("results", results);
        out.put("card", rental.groupCard(cardKey));
        out.put("message", "已处理 " + results.size() + " 个拼单");
        return out;
    }

    /** 管理员代撤拼单：仅未成团可撤，预付款同样原路退回租户余额 */
    @PostMapping("/group-joins/{id}/cancel")
    public Map<String, Object> cancelGroupJoin(@PathVariable String id,
                                               @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> out = rental.cancelGroupJoin(id, null, true);
        db.insertAudit(str(username, "admin"), "GROUP_CANCEL", id, "预付款 ¥" + out.get("refund") + " 已退回租户");
        return out;
    }

    // ============================================================ 到货提醒

    @GetMapping("/stock-alerts")
    public Map<String, Object> stockAlerts(@RequestParam(required = false) String status) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> a : db.stockAlerts(status)) {
            Map<String, Object> m = new LinkedHashMap<>(a);
            m.put("tenantName", tenantName((String) a.get("tenant_id")));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("openCount", db.stockAlerts("open").size());
        return out;
    }

    /**
     * 手动完结到货提醒：卡释放回池时系统会自动完结并通知，这里是给「线下已协调好机器」的场景用的。
     * 完结即视为已通知，所以同时给租户补一条消息，不能只改状态。
     */
    @PostMapping("/stock-alerts/{id}/fulfill")
    public Map<String, Object> fulfillStockAlert(@PathVariable String id,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> alert = db.one("SELECT * FROM stock_alerts WHERE id=?", id);
        if (alert == null) {
            throw new RentalService.BizException("到货提醒不存在: " + id);
        }
        if (!"open".equals(String.valueOf(alert.get("status")))) {
            throw new RentalService.BizException("该提醒已完结，无需重复处理");
        }
        String note = str(body == null ? null : body.get("note"), "");
        db.fulfillStockAlert(id);
        db.insertNotification(newId("n"), String.valueOf(alert.get("tenant_id")), "库存",
                "到货提醒 · " + alert.get("model"),
                "您关注的 " + alert.get("model") + " 已有可用库存" + (note.isBlank() ? "" : "（" + note + "）") + "，快去下单吧。");
        db.insertAudit(str(username, "admin"), "STOCK_ALERT_FULFILL", id,
                alert.get("model") + " · 租户 " + tenantName((String) alert.get("tenant_id"))
                        + (note.isBlank() ? "" : " · " + note));
        return Map.of("id", id, "status", "fulfilled", "message", "已完结并通知租户");
    }

    // ============================================================ 代租户操作实例

    /**
     * 管理端实例台账：一次查出所有租户的实例，带上租户名、规格、镜像与占用卡。
     * 用户端能做的关机/开机/重启/释放/重置/续费，这一页全都能代做，走的是同一批 RentalService 方法。
     */
    @GetMapping("/instances")
    public Map<String, Object> instances(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String tenantId) {
        StringBuilder sql = new StringBuilder("""
                SELECT i.*, o.unit_price, o.hours, t.name AS tenant_name
                  FROM instances i
                  LEFT JOIN orders o ON o.id = i.order_id
                  LEFT JOIN tenants t ON t.id = i.tenant_id
                 WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND i.status=?");
            args.add(status);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            sql.append(" AND i.tenant_id=?");
            args.add(tenantId);
        }
        sql.append(" ORDER BY i.created_at DESC LIMIT 200");

        List<Map<String, Object>> items = new ArrayList<>();
        int running = 0;
        int stopped = 0;
        int occupiedCards = 0;
        for (Map<String, Object> r : db.q(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            int gc = ((Number) r.getOrDefault("gpu_count", 0)).intValue();
            m.put("spec", gc == 0 ? "无卡调试" : gc == 8 ? "整机 8 卡" : gc + " 卡");
            m.put("gpuIds", str(r.get("gpu_ids"), ""));
            items.add(m);
            String st = String.valueOf(r.get("status"));
            if ("running".equals(st)) {
                running++;
                occupiedCards += gc;
            } else if ("stopped".equals(st)) {
                stopped++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("runningCount", running);
        out.put("stoppedCount", stopped);
        out.put("occupiedCards", occupiedCards);
        return out;
    }

    /** 管理端实例列表的四个动作，全部复用用户端同一条业务路径 */
    @PostMapping("/instances/{id}/poweroff")
    public Map<String, Object> powerOff(@PathVariable String id,
                                        @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> out = rental.powerOff(id);
        db.insertAudit(str(username, "admin"), "INSTANCE_POWEROFF", id, "管理端代操作");
        return out;
    }

    @PostMapping("/instances/{id}/poweron")
    public Map<String, Object> powerOn(@PathVariable String id,
                                       @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> out = rental.powerOn(id);
        db.insertAudit(str(username, "admin"), "INSTANCE_POWERON", id, "管理端代操作");
        return out;
    }

    @PostMapping("/instances/{id}/restart")
    public Map<String, Object> restart(@PathVariable String id,
                                       @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> out = rental.restart(id);
        db.insertAudit(str(username, "admin"), "INSTANCE_RESTART", id, "管理端代操作");
        return out;
    }

    @PostMapping("/instances/{id}/release")
    public Map<String, Object> release(@PathVariable String id,
                                       @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> out = rental.release(id);
        db.insertAudit(str(username, "admin"), "INSTANCE_RELEASE", id,
                "管理端代操作 · 实结 ¥" + out.get("actualAmount") + " · 退回 ¥" + out.get("refund"));
        return out;
    }

    @PostMapping("/instances/{id}/reset")
    public Map<String, Object> reset(@PathVariable String id,
                                     @RequestAttribute(value = "authUsername", required = false) String username) {
        Map<String, Object> out = rental.resetSystem(id);
        db.insertAudit(str(username, "admin"), "INSTANCE_RESET", id, "管理端代操作系统盘重置");
        return out;
    }

    /** 代租户续费：钱从租户余额扣，管理员不能凭空续期，否则账对不上 */
    @PostMapping("/instances/{id}/renew")
    public Map<String, Object> renew(@PathVariable String id,
                                     @RequestBody Map<String, Object> body,
                                     @RequestAttribute(value = "authUsername", required = false) String username) {
        int hours;
        try {
            hours = Integer.parseInt(str(body.get("hours"), "1"));
        } catch (NumberFormatException e) {
            throw new RentalService.BizException("时长格式不正确");
        }
        Map<String, Object> out = rental.renew(id, hours);
        db.insertAudit(str(username, "admin"), "INSTANCE_RENEW", id,
                "管理端代续费 " + hours + " 小时 · 预扣 ¥" + out.get("prepaid"));
        return out;
    }
}
