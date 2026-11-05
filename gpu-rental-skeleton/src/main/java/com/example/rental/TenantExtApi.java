package com.example.rental;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户端此前缺后端的九类功能：API 密钥 / 消息通知 / 子账号 / 镜像 / 文件存储 /
 * 充值 / 发票 / 工单 / 实例重启。全部落库，租户数据按会话租户隔离。
 */
@RestController
@RequestMapping("/api")
public class TenantExtApi {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final Db db;
    private final K8sClient k8s;
    private final RentalService rental;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public TenantExtApi(Db db, K8sClient k8s, RentalService rental) {
        this.db = db;
        this.k8s = k8s;
        this.rental = rental;
    }

    /** 租户会话校验：tenant / sub 角色只能看自己租户的数据，管理员可用 tenantId 参数指定 */
    private String myTenant(@RequestAttribute(value = "authRole", required = false) String role,
                            @RequestAttribute(value = "authTenant", required = false) String myTenant,
                            String requested) {
        boolean tenantScoped = "tenant".equals(role) || "sub".equals(role);
        if (!tenantScoped) {
            return requested == null || requested.isBlank() ? myTenant : requested;
        }
        if (myTenant == null) {
            throw new AuthException(403, "会话缺少租户信息，请重新登录");
        }
        if (requested != null && !requested.isBlank() && !requested.equals(myTenant)) {
            throw new AuthException(403, "无权操作其它租户的数据");
        }
        return myTenant;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 公钥这类「属于某个租户」的资源，必须有租户上下文；管理员账号不代替租户存公钥 */
    private String requireTenant(String role, String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        if (tenantId == null || tenantId.isBlank()) {
            throw new AuthException(403, "请用租户账号登录后再操作");
        }
        return tenantId;
    }

    // ============================================================ API 密钥

    @GetMapping("/api-keys")
    public List<Map<String, Object>> apiKeys(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.apiKeys(myTenant(role, myTenant, null));
    }

    /** 创建密钥：只返回一次完整 key（sk- 前缀 + 32 位随机），库里只存 SHA-256 哈希 */
    @PostMapping("/api-keys")
    public Map<String, Object> createApiKey(@RequestBody Map<String, Object> body,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String name = String.valueOf(body.getOrDefault("name", "未命名密钥")).trim();
        if (name.isBlank()) {
            name = "未命名密钥";
        }
        if (db.apiKeys(tenantId).size() >= 5) {
            throw new RentalService.BizException("最多创建 5 个密钥");
        }
        byte[] rnd = new byte[24];
        RANDOM.nextBytes(rnd);
        String secret = HexFormat.of().formatHex(rnd);
        String full = "sk-" + secret;
        String prefix = full.substring(0, 10);
        String id = newId("ak");
        db.insertApiKey(id, tenantId, name, prefix, sha256(full));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("key", full);
        out.put("prefix", prefix);
        out.put("status", "on");
        out.put("message", "密钥已创建，完整 key 仅此一次展示，请妥善保存");
        return out;
    }

    @PostMapping("/api-keys/{id}/toggle")
    public Map<String, Object> toggleApiKey(@PathVariable String id,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        Map<String, Object> k = db.q("SELECT status FROM api_keys WHERE id=? AND tenant_id=?", id, tenantId)
                .stream().findFirst().orElse(null);
        if (k == null) {
            throw new RentalService.BizException("密钥不存在");
        }
        String next = "on".equals(String.valueOf(k.get("status"))) ? "off" : "on";
        db.setApiKeyStatus(id, tenantId, next);
        return Map.of("changed", 1, "status", next);
    }

    @DeleteMapping("/api-keys/{id}")
    public Map<String, Object> deleteApiKey(@PathVariable String id,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.deleteApiKey(id, myTenant(role, myTenant, null));
        return Map.of("deleted", n, "message", n > 0 ? "密钥已删除" : "密钥不存在");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ============================================================ 消息通知

    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        List<Map<String, Object>> list = db.notifications(myTenant(role, myTenant, null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", list);
        out.put("total", list.size());
        out.put("unread", list.stream().filter(n -> !Boolean.TRUE.equals(n.get("is_read"))).count());
        return out;
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAllNotifications(@RequestAttribute(value = "authRole", required = false) String role,
                                                    @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.markAllNotificationsRead(myTenant(role, myTenant, null));
        return Map.of("marked", n, "message", "已全部标为已读");
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> readNotification(@PathVariable String id,
                                                @RequestAttribute(value = "authRole", required = false) String role,
                                                @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.markNotificationRead(id, myTenant(role, myTenant, null));
        return Map.of("marked", n);
    }

    // ============================================================ 子账号

    @GetMapping("/subaccounts")
    public List<Map<String, Object>> subaccounts(@RequestAttribute(value = "authRole", required = false) String role,
                                                 @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.subaccounts(myTenant(role, myTenant, null));
    }

    /** 新建子账号：同一租户下 role='sub' 的用户，初始密码由租户管理员设定 */
    @PostMapping("/subaccounts")
    public Map<String, Object> createSubaccount(@RequestBody Map<String, Object> body,
                                                @RequestAttribute(value = "authRole", required = false) String role,
                                                @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                                @RequestAttribute(value = "authUsername", required = false) String actor) {
        String tenantId = myTenant(role, myTenant, null);
        String username = String.valueOf(body.getOrDefault("username", "")).trim();
        String password = String.valueOf(body.getOrDefault("password", ""));
        if (!username.matches("[A-Za-z0-9_]{4,16}")) {
            throw new RentalService.BizException("用户名需为 4-16 位字母、数字或下划线");
        }
        AuthService.requireStrongPassword(password);
        if (db.userByUsername(username) != null) {
            throw new RentalService.BizException("用户名已存在");
        }
        if (db.subaccounts(tenantId).size() >= 20) {
            throw new RentalService.BizException("子账号数量已达上限（20）");
        }
        String id = newId("u");
        db.insertUser(id, username, username, "sub", tenantId);
        db.setPasswordHash(id, encoder.encode(password));
        db.insertAudit(actor == null ? "tenant" : actor, "SUBACCOUNT_CREATE", tenantId + "/" + username, "角色 sub");
        return Map.of("id", id, "message", "子账号已创建：" + username);
    }

    @DeleteMapping("/subaccounts/{id}")
    public Map<String, Object> deleteSubaccount(@PathVariable String id,
                                                @RequestAttribute(value = "authRole", required = false) String role,
                                                @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.deleteSubaccount(id, myTenant(role, myTenant, null));
        return Map.of("deleted", n, "message", n > 0 ? "子账号已删除" : "子账号不存在");
    }

    // ============================================================ 镜像

    @GetMapping("/images")
    public List<Map<String, Object>> images(@RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.images(myTenant(role, myTenant, null));
    }

    @PostMapping("/images")
    public Map<String, Object> createImage(@RequestBody Map<String, Object> body,
                                           @RequestAttribute(value = "authRole", required = false) String role,
                                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) {
            throw new RentalService.BizException("镜像名称不能为空");
        }
        String base = String.valueOf(body.getOrDefault("base", "pytorch:2.4"));
        String zone = String.valueOf(body.getOrDefault("zone", "可用区 A"));
        BigDecimal size = new BigDecimal(String.valueOf(body.getOrDefault("sizeGb", "2.1")));
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String id = newId("img");
        db.insertImage(id, tenantId, name, base, size, zone, uuid);
        return Map.of("id", id, "uuid", uuid, "message", "镜像已创建（演示：保存实例环境即可生成专属镜像）");
    }

    @DeleteMapping("/images/{id}")
    public Map<String, Object> deleteImage(@PathVariable String id,
                                           @RequestAttribute(value = "authRole", required = false) String role,
                                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.deleteImage(id, myTenant(role, myTenant, null));
        return Map.of("deleted", n, "message", n > 0 ? "镜像已删除" : "镜像不存在");
    }

    // ============================================================ 文件存储

    @GetMapping("/storage")
    public Map<String, Object> storage(@RequestAttribute(value = "authRole", required = false) String role,
                                       @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                       @RequestParam(defaultValue = "可用区 A") String zone) {
        String tenantId = myTenant(role, myTenant, null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("zone", zone);
        out.put("items", db.storageItems(tenantId).stream()
                .filter(i -> zone.equals(String.valueOf(i.get("zone")))).toList());
        out.put("usedGb", db.storageUsedGb(tenantId));
        out.put("freeGb", new BigDecimal("20"));
        return out;
    }

    /** 新建文件夹 / 上传文件：演示环境只落库元数据，不上传真实对象 */
    @PostMapping("/storage")
    public Map<String, Object> createStorageItem(@RequestBody Map<String, Object> body,
                                                 @RequestAttribute(value = "authRole", required = false) String role,
                                                 @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) {
            throw new RentalService.BizException("名称不能为空");
        }
        String zone = String.valueOf(body.getOrDefault("zone", "可用区 A"));
        boolean isDir = Boolean.TRUE.equals(body.get("isDir"));
        BigDecimal size = new BigDecimal(String.valueOf(body.getOrDefault("sizeGb", "0")));
        String id = newId("st");
        db.insertStorageItem(id, tenantId, zone, name, isDir, size);
        return Map.of("id", id, "message", isDir ? "文件夹已创建" : "文件已登记（演示环境不上传真实对象）");
    }

    @DeleteMapping("/storage/{id}")
    public Map<String, Object> deleteStorageItem(@PathVariable String id,
                                                 @RequestAttribute(value = "authRole", required = false) String role,
                                                 @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.deleteStorageItem(id, myTenant(role, myTenant, null));
        return Map.of("deleted", n, "message", n > 0 ? "已删除" : "条目不存在");
    }

    // ============================================================ 充值

    @GetMapping("/recharges")
    public List<Map<String, Object>> recharges(@RequestAttribute(value = "authRole", required = false) String role,
                                               @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.recharges(myTenant(role, myTenant, null));
    }

    /** 演示充值：模拟支付回调直接到账（真实接入时此端点换成支付渠道的异步通知校验） */
    @PostMapping("/recharges")
    public Map<String, Object> recharge(@RequestBody Map<String, Object> body,
                                        @RequestAttribute(value = "authRole", required = false) String role,
                                        @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                        @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = myTenant(role, myTenant, null);
        BigDecimal amount = new BigDecimal(String.valueOf(body.getOrDefault("amount", "0")));
        if (amount.compareTo(new BigDecimal("0.01")) < 0 || amount.compareTo(new BigDecimal("100000")) > 0) {
            throw new RentalService.BizException("充值金额需在 0.01 ~ 100000 元之间");
        }
        String channel = String.valueOf(body.getOrDefault("channel", "微信支付"));
        String id = newId("rc");
        String orderNo = "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        db.insertRecharge(id, tenantId, orderNo, amount, channel);
        db.addBalance(tenantId, amount);
        db.insertAudit(username == null ? "tenant" : username, "RECHARGE", tenantId, channel + " +" + amount + " 元");
        String inviteMsg = grantInviteRewards(tenantId, username, amount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("orderNo", orderNo);
        out.put("amount", amount);
        out.put("balance", ((Map<String, Object>) db.tenant(tenantId)).get("balance_yuan"));
        out.put("message", "充值成功：" + channel + " ¥" + amount + "（演示环境模拟到账）"
                + (inviteMsg.isEmpty() ? "" : "；" + inviteMsg));
        return out;
    }

    /**
     * 邀请返券：被邀请人首次充值满门槛，双方各得一张代金券（金额与有效期都是系统参数）。
     * 用 markInviteRewarded 的条件更新做幂等，重复充值不会重复发券。
     */
    private String grantInviteRewards(String tenantId, String username, BigDecimal amount) {
        if (username == null) {
            return "";
        }
        Map<String, Object> me = db.userByUsername(username);
        if (me == null) {
            return "";
        }
        Object inviterId = me.get("invited_by");
        if (inviterId == null || String.valueOf(inviterId).isBlank()) {
            return "";
        }
        BigDecimal min = decimal(db.setting("invite.min_recharge"), BigDecimal.ONE);
        if (amount.compareTo(min) < 0 || db.countRecharges(tenantId) > 1) {
            return "";
        }
        if (db.markInviteRewarded((String) me.get("id")) == 0) {
            return "";
        }
        BigDecimal toInvitee = decimal(db.setting("invite.reward_invitee"), new BigDecimal("5"));
        BigDecimal toInviter = decimal(db.setting("invite.reward_inviter"), new BigDecimal("3"));
        int days = Integer.parseInt(orDefault(db.setting("invite.coupon_days"), "90"));
        Timestamp expire = new Timestamp(System.currentTimeMillis() + days * 86_400_000L);
        String inviter = String.valueOf(inviterId);

        db.insertCoupon(newId("cp"), tenantId, "新人首充赠送（邀请活动）", toInvitee, expire);
        db.insertCoupon(newId("cp"), inviter, "邀请好友返券 · " + username, toInviter, expire);
        db.insertNotification(newId("n"), tenantId, "活动", "首充赠送已到账",
                "首次充值满 " + min.stripTrailingZeros().toPlainString() + " 元，赠送 "
                        + toInvitee.stripTrailingZeros().toPlainString() + " 元代金券，" + days + " 天内有效，可在「券包」查看。");
        db.insertNotification(newId("n"), inviter, "邀请", "邀请返券已到账",
                username + " 完成首次充值，你获得 " + toInviter.stripTrailingZeros().toPlainString()
                        + " 元代金券（" + days + " 天有效）。");
        db.insertAudit(username, "INVITE_REWARD", tenantId,
                "被邀请人 +" + toInvitee + " 元券，邀请人 " + inviter + " +" + toInviter + " 元券");
        return "首充赠送 " + toInvitee.stripTrailingZeros().toPlainString() + " 元代金券已发放，邀请人同时获得 "
                + toInviter.stripTrailingZeros().toPlainString() + " 元返券";
    }

    private static BigDecimal decimal(String v, BigDecimal fallback) {
        try {
            return v == null || v.isBlank() ? fallback : new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    // ============================================================ 邀请

    /** 我的邀请码 + 邀请来的人 + 已到账的返券，控制台「邀请赠送」卡片全部用这里的真数据 */
    @GetMapping("/invite")
    public Map<String, Object> invite(@RequestAttribute(value = "authRole", required = false) String role,
                                      @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = requireTenant(role, myTenant);
        Map<String, Object> tenant = db.tenant(tenantId);
        String code = tenant == null || tenant.get("invite_code") == null ? "" : String.valueOf(tenant.get("invite_code"));
        if (code.isBlank()) {
            code = db.newInviteCode();
            db.setInviteCode(tenantId, code);
        }
        List<Map<String, Object>> invited = db.usersInvitedBy(tenantId);
        List<Map<String, Object>> rewards = new ArrayList<>();
        for (Map<String, Object> c : db.couponsOfTenant(tenantId)) {
            String title = String.valueOf(c.get("title"));
            if (title.contains("邀请") || title.contains("首充")) {
                rewards.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("registerUrl", "/login?tab=register&invite=" + code);
        out.put("invited", invited);
        out.put("invitedCount", invited.size());
        out.put("rewardedCount", invited.stream().filter(u -> Boolean.parseBoolean(String.valueOf(u.get("invite_rewarded")))).count());
        out.put("rewards", rewards);
        out.put("rule", Map.of(
                "minRecharge", orDefault(db.setting("invite.min_recharge"), "1"),
                "rewardInvitee", orDefault(db.setting("invite.reward_invitee"), "5"),
                "rewardInviter", orDefault(db.setting("invite.reward_inviter"), "3"),
                "couponDays", orDefault(db.setting("invite.coupon_days"), "90")));
        return out;
    }

    // ============================================================ 发票

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(@RequestAttribute(value = "authRole", required = false) String role,
                                              @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.invoices(myTenant(role, myTenant, null));
    }

    /** 按月生成发票：金额 = 该月已结算账单合计，同月重复生成直接返回原发票 */
    @PostMapping("/invoices/generate")
    public Map<String, Object> generateInvoice(@RequestBody Map<String, Object> body,
                                               @RequestAttribute(value = "authRole", required = false) String role,
                                               @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String period = String.valueOf(body.getOrDefault("period", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))));
        if (!period.matches("\\d{4}-\\d{2}")) {
            throw new RentalService.BizException("账期格式应为 yyyy-MM");
        }
        Map<String, Object> existed = db.invoice(tenantId, period);
        if (existed != null) {
            return Map.of("reused", true, "invoice", existed, "message", "该账期发票已存在");
        }
        // 账期区间按自然月计算（当月 1 日 00:00 ~ 次月 1 日 00:00）
        BigDecimal amount = (BigDecimal) db.q("""
                SELECT COALESCE(SUM(amount),0) AS s FROM billing_records
                 WHERE tenant_id=? AND settled_at >= ? AND settled_at < ?
                """, tenantId,
                java.sql.Timestamp.valueOf(period + "-01 00:00:00"),
                java.sql.Timestamp.valueOf(monthAfter(period) + "-01 00:00:00")).stream().findFirst().get().get("s");
        String id = newId("inv");
        db.insertInvoice(id, tenantId, period, amount);
        return Map.of("id", id, "period", period, "amount", amount, "message", "发票草稿已生成");
    }

    @PostMapping("/invoices/{id}/issue")
    public Map<String, Object> issueInvoice(@PathVariable String id,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        int n = db.issueInvoice(id, myTenant(role, myTenant, null));
        return Map.of("issued", n, "message", n > 0 ? "发票已开具（演示环境为电子票）" : "发票不存在或已开具");
    }

    private static String monthAfter(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(5, 7));
        return m == 12 ? (y + 1) + "-01" : y + "-" + String.format("%02d", m + 1);
    }

    // ============================================================ 工单

    @GetMapping("/tickets")
    public List<Map<String, Object>> tickets(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        boolean tenantScoped = "tenant".equals(role) || "sub".equals(role);
        return db.tickets(tenantScoped ? myTenant(role, myTenant, null) : null);
    }

    @PostMapping("/tickets")
    public Map<String, Object> createTicket(@RequestBody Map<String, Object> body,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                            @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = myTenant(role, myTenant, null);
        String subject = String.valueOf(body.getOrDefault("subject", "")).trim();
        String content = String.valueOf(body.getOrDefault("content", ""));
        if (subject.isBlank()) {
            throw new RentalService.BizException("工单主题不能为空");
        }
        String id = newId("tk");
        db.insertTicket(id, tenantId, subject, content);
        db.insertAudit(username == null ? "tenant" : username, "TICKET_CREATE", tenantId, subject);
        return Map.of("id", id, "message", "工单已提交，平台会尽快处理");
    }

    // ============================================================ 实例重启（租户侧）

    /** 重启自己的实例：与管理端「重启」走同一条 RentalService.restart，行为一致 */
    @PostMapping("/instances/{id}/restart")
    public Map<String, Object> restartInstance(@PathVariable String id,
                                               @RequestAttribute(value = "authRole", required = false) String role,
                                               @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return rental.restart(id);
    }

    /** 实例消息订阅开关：关掉后该实例的开关机 / 续费 / 释放等通知不再推送 */
    @PostMapping("/instances/{id}/subscribe")
    public Map<String, Object> subscribeInstance(@PathVariable String id,
                                                 @RequestBody Map<String, Object> body,
                                                 @RequestAttribute(value = "authRole", required = false) String role,
                                                 @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = own(id, role, myTenant);
        Map<String, Object> inst = db.instance(id);
        boolean current = db.instanceNotifyOn(id);
        boolean on = body.get("on") == null ? !current : Boolean.parseBoolean(String.valueOf(body.get("on")));
        db.setInstanceNotify(id, on);
        if (on && !current) {
            // 重新打开订阅时补一条确认，否则用户看不到任何反馈，以为按钮是坏的
            db.insertNotification(newId("n"), tenantId, "实例", "已开启消息推送",
                    "实例 " + id + " 的开关机、续费、释放等通知已恢复推送。");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", id);
        out.put("notifyPush", on);
        out.put("status", String.valueOf(inst.get("status")));
        out.put("message", on ? "已开启该实例的消息推送" : "已关闭该实例的消息推送（计费与操作不受影响）");
        return out;
    }

    /** 实例归属校验：租户只能动自己的实例，管理员可代操作（返回实例所属租户） */
    private String own(String instanceId, String role, String myTenant) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) {
            throw new RentalService.BizException("实例不存在");
        }
        String owner = String.valueOf(inst.get("tenant_id"));
        String tenantId = myTenant(role, myTenant, owner);
        if (!tenantId.equals(owner)) {
            throw new RentalService.BizException("无权操作其它租户的实例");
        }
        return owner;
    }

    // ============================================================ 续费

    /** 续费：余额预扣 → 预扣池累加 → 到期时间顺延，同一事务 */
    @PostMapping("/instances/{id}/renew")
    public Map<String, Object> renewInstance(@PathVariable String id,
                                             @RequestBody Map<String, Object> body,
                                             @RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        int hours;
        try {
            hours = Integer.parseInt(String.valueOf(body.getOrDefault("hours", "1")));
        } catch (NumberFormatException e) {
            throw new RentalService.BizException("时长格式不正确");
        }
        return rental.renew(id, hours);
    }

    // ============================================================ 到货提醒

    /** 登记到货提醒：同一租户同一机型只保留一条未完结订阅，重复登记不重复入库 */
    @PostMapping("/stock-alerts")
    public Map<String, Object> stockAlert(@RequestBody Map<String, Object> body,
                                          @RequestAttribute(value = "authRole", required = false) String role,
                                          @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String model = String.valueOf(body.getOrDefault("model", "RTX 5090")).trim();
        if (model.isBlank()) {
            throw new RentalService.BizException("机型不能为空");
        }
        String zone = String.valueOf(body.getOrDefault("zone", "")).trim();
        int qty = body.get("qty") instanceof Number n ? n.intValue() : 1;
        if (qty < 1 || qty > 64) {
            throw new RentalService.BizException("数量阈值要在 1~64 卡之间");
        }
        Map<String, Object> existed = db.stockAlertByTenantModel(tenantId, model);
        if (existed != null) {
            // 重复登记不是「什么都不做」：用户这次改的地区和阈值要覆盖旧值，否则改了个寂寞
            String newZone = zone.isBlank() ? (String) existed.get("zone") : zone;
            db.updateStockAlertConfig(String.valueOf(existed.get("id")), newZone, qty);
            return Map.of("reused", true, "id", existed.get("id"), "zone", newZone == null ? "" : newZone, "qty", qty,
                    "message", "已更新「" + model + "」的到货提醒" + (newZone == null || newZone.isBlank() ? "" : "（" + newZone + "）")
                            + "，空闲卡达到 " + qty + " 张就通知你");
        }
        String id = newId("sa");
        db.insertStockAlert(id, tenantId, model, zone.isBlank() ? null : zone, qty);
        db.insertAudit(String.valueOf(body.getOrDefault("actor", "tenant")), "STOCK_ALERT", id,
                model + (zone.isBlank() ? "" : " · " + zone) + " · 阈值 " + qty + " 卡");
        return Map.of("reused", false, "id", id, "zone", zone, "qty", qty,
                "message", "已登记「" + model + "」到货提醒" + (zone.isBlank() ? "" : "（" + zone + "）")
                        + "，空闲卡达到 " + qty + " 张就通知你");
    }

    // ============================================================ MaaS 用量（控制台展示）

    /** 模型目录（与 /v1/models 同一份数据，给控制台页面渲染） */
    @GetMapping("/maas/models")
    public Map<String, Object> maasModels() {
        List<Map<String, Object>> items = MaasCatalog.MODELS.stream().map(m -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", m.id());
            o.put("display", m.display());
            o.put("inPerM", m.inPerM());
            o.put("outPerM", m.outPerM());
            return o;
        }).toList();
        return Map.of("items", items);
    }

    @GetMapping("/maas/usage")
    public Map<String, Object> maasUsage(@RequestAttribute(value = "authRole", required = false) String role,
                                         @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        List<Map<String, Object>> items = db.maasUsage(myTenant(role, myTenant, null));
        BigDecimal total = items.stream()
                .map(r -> (BigDecimal) r.get("amount_yuan"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        return out;
    }

    // ============================================================ 实例进阶动作（A-8/A-9）

    private Map<String, Object> ownInstance(String id, String role, String myTenant) {
        Map<String, Object> inst = db.instance(id);
        if (inst == null) {
            throw new RentalService.BizException("实例不存在");
        }
        String tenantId = myTenant(role, myTenant, String.valueOf(inst.get("tenant_id")));
        if (!tenantId.equals(String.valueOf(inst.get("tenant_id")))) {
            throw new RentalService.BizException("无权操作其它租户的实例");
        }
        return inst;
    }

    /** 关机：停止计费、保留数据与占卡，7 天保留期内可开机 */
    @PostMapping("/instances/{id}/poweroff")
    public Map<String, Object> powerOff(@PathVariable String id,
                                        @RequestAttribute(value = "authRole", required = false) String role,
                                        @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        return rental.powerOff(id);
    }

    /** 开机：重建 Pod，就绪后恢复计费 */
    @PostMapping("/instances/{id}/poweron")
    public Map<String, Object> powerOn(@PathVariable String id,
                                       @RequestAttribute(value = "authRole", required = false) String role,
                                       @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        return rental.powerOn(id);
    }

    /** 重置系统：系统盘还原，数据盘不受影响 */
    @PostMapping("/instances/{id}/reset")
    public Map<String, Object> resetSystem(@PathVariable String id,
                                           @RequestAttribute(value = "authRole", required = false) String role,
                                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        return rental.resetSystem(id);
    }

    /** 克隆：按原规格与剩余时长开新实例 */
    @PostMapping("/instances/{id}/clone")
    public Map<String, Object> cloneInstance(@PathVariable String id,
                                             @RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> inst = ownInstance(id, role, myTenant);
        return rental.cloneInstance(id, String.valueOf(inst.get("tenant_id")));
    }

    /** 升降配：新规格下单占卡 → 旧实例按秒结算释放 */
    @PostMapping("/instances/{id}/resize")
    public Map<String, Object> resize(@PathVariable String id,
                                      @RequestBody Map<String, Object> body,
                                      @RequestAttribute(value = "authRole", required = false) String role,
                                      @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        int count;
        try {
            count = Integer.parseInt(String.valueOf(body.getOrDefault("gpuCount", "0")));
        } catch (NumberFormatException e) {
            throw new RentalService.BizException("卡数格式不正确");
        }
        return rental.resize(id, count);
    }

    /** 定时关机：at 为 ISO 时间（yyyy-MM-dd HH:mm:ss 或 epoch 毫秒均可） */
    @PostMapping("/instances/{id}/schedule-shutdown")
    public Map<String, Object> scheduleShutdown(@PathVariable String id,
                                                @RequestBody Map<String, Object> body,
                                                @RequestAttribute(value = "authRole", required = false) String role,
                                                @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> inst = ownInstance(id, role, myTenant);
        String at = String.valueOf(body.getOrDefault("at", ""));
        Timestamp ts;
        try {
            ts = at.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}(:\\d{2})?")
                    ? Timestamp.valueOf(at.length() == 16 ? at + ":00" : at)
                    : new Timestamp(Long.parseLong(at));
        } catch (Exception e) {
            throw new RentalService.BizException("时间格式不正确：请用 yyyy-MM-dd HH:mm:ss 或时间戳毫秒");
        }
        if (ts.getTime() <= System.currentTimeMillis()) {
            throw new RentalService.BizException("定时关机时间必须晚于当前时间");
        }
        db.cancelSchedulesOfInstance(id);
        db.insertSchedule(newId("sch"), id, String.valueOf(inst.get("tenant_id")), "shutdown", ts);
        return Map.of("message", "已设置定时关机：" + ts);
    }

    @GetMapping("/instances/{id}/schedules")
    public List<Map<String, Object>> schedules(@PathVariable String id,
                                               @RequestAttribute(value = "authRole", required = false) String role,
                                               @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        return db.schedulesOfInstance(id);
    }

    @PostMapping("/instances/{id}/cancel-schedule")
    public Map<String, Object> cancelSchedule(@PathVariable String id,
                                              @RequestAttribute(value = "authRole", required = false) String role,
                                              @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        int n = db.cancelSchedulesOfInstance(id);
        return Map.of("cancelled", n, "message", n > 0 ? "已取消定时关机" : "没有待执行的定时关机");
    }

    // ============================================================ 镜像：保存 / 共享 / 扫描（A-16/17/18）

    /** 系统盘保存为私有镜像（真实集群需镜像快照组件，此处按生命周期模拟并如实标注） */
    @PostMapping("/instances/{id}/save-image")
    public Map<String, Object> saveImage(@PathVariable String id,
                                         @RequestBody Map<String, Object> body,
                                         @RequestAttribute(value = "authRole", required = false) String role,
                                         @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> inst = ownInstance(id, role, myTenant);
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.isBlank()) {
            throw new RentalService.BizException("镜像名称不能为空");
        }
        String imageId = newId("img");
        db.insertSavedImage(imageId, String.valueOf(inst.get("tenant_id")), name,
                "自定义快照（演示）", new BigDecimal("30"), id);
        return Map.of("id", imageId, "message", "镜像保存中（演示模式秒级完成），可在镜像页私有列表查看");
    }

    @PostMapping("/images/{id}/share")
    public Map<String, Object> shareImage(@PathVariable String id,
                                          @RequestAttribute(value = "authRole", required = false) String role,
                                          @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        if (db.shareImage(id, tenantId) == 0) {
            throw new RentalService.BizException("镜像不存在或无权分享");
        }
        return Map.of("message", "已分享到社区，其他用户可在社区镜像页使用");
    }

    /** 镜像安全扫描：内置基线检查（演示级），社区镜像入库时同样触发 */
    @PostMapping("/images/{id}/scan")
    public Map<String, Object> scanImage(@PathVariable String id,
                                         @RequestAttribute(value = "authRole", required = false) String role,
                                         @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        if (db.setScanStatus(id, "扫描中") == 0) {
            throw new RentalService.BizException("镜像不存在或无权扫描");
        }
        // 演示级基线检查：名称含"自定义快照（演示）"的镜像模拟检出风险项，其余通过
        Map<String, Object> img = db.one("SELECT * FROM images WHERE id=?", id);
        String verdict = String.valueOf(img.get("base")).contains("演示") ? "风险" : "安全";
        db.setScanStatus(id, verdict);
        return Map.of("status", verdict, "message",
                "风险".equals(verdict) ? "扫描完成：检出 1 个风险项（演示基线：未打安全补丁），已在页面标注"
                        : "扫描完成：未发现风险（演示基线检查）");
    }

    @GetMapping("/images/community")
    public List<Map<String, Object>> communityImages() {
        return db.communityImages();
    }

    /**
     * 社区镜像「使用」：把别人分享的镜像复制进自己的镜像库（真实集群里就是 pull 下来 retag 到自己命名空间）。
     * 复制后创建实例时可选它，安全扫描结论沿用原镜像，不重复扫描。
     */
    @PostMapping("/images/community/{id}/use")
    public Map<String, Object> useCommunityImage(@PathVariable String id,
                                                 @RequestAttribute(value = "authRole", required = false) String role,
                                                 @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                                 @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = requireTenant(role, myTenant);
        Map<String, Object> src = db.one("SELECT * FROM images WHERE id=? AND shared=TRUE", id);
        if (src == null) {
            throw new RentalService.BizException("社区镜像不存在或已被下架");
        }
        String name = String.valueOf(src.get("name"));
        for (Map<String, Object> mine : db.images(tenantId)) {
            if (name.equals(String.valueOf(mine.get("name")))) {
                return Map.of("reused", true, "id", mine.get("id"),
                        "message", "「" + name + "」已经在你的镜像库里，直接去创建实例选它就行");
            }
        }
        BigDecimal size = src.get("size_gb") == null ? new BigDecimal("2.1") : (BigDecimal) src.get("size_gb");
        String newId = newId("img");
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        db.insertImage(newId, tenantId, name,
                "社区镜像 · " + String.valueOf(src.getOrDefault("base", "")), size,
                String.valueOf(src.getOrDefault("zone", "可用区 A")), uuid);
        db.setScanStatus(newId, String.valueOf(src.getOrDefault("scan_status", "未扫描")));
        db.insertAudit(username == null ? "tenant" : username, "IMAGE_USE", tenantId,
                "复制社区镜像 " + id + "（" + name + "）到自己的镜像库");
        db.insertNotification(newId("n"), tenantId, "镜像", "社区镜像已复制",
                "「" + name + "」已复制到你的镜像库（" + size + " GB），创建实例时可直接选用。");
        return Map.of("reused", false, "id", newId, "sizeGb", size,
                "message", "已复制「" + name + "」到你的镜像库，创建实例时可选用");
    }

    // ============================================================ 数据集（A-21）

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets() {
        return db.datasets();
    }

    /** 数据集挂载到实例：真实集群为 PVC 挂载，此处台账模拟并标注 */
    @PostMapping("/instances/{id}/mount-dataset")
    public Map<String, Object> mountDataset(@PathVariable String id,
                                            @RequestBody Map<String, Object> body,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> inst = ownInstance(id, role, myTenant);
        String datasetId = String.valueOf(body.getOrDefault("datasetId", ""));
        if (db.dataset(datasetId) == null) {
            throw new RentalService.BizException("数据集不存在");
        }
        int n = db.mountDataset(id, datasetId);
        if (n == 0) {
            return Map.of("reused", true, "message", "该数据集已挂载到此实例");
        }
        db.insertNotification(newId("n"), String.valueOf(inst.get("tenant_id")), "存储",
                "数据集已挂载", "数据集已挂载到实例 " + id + "（演示环境为台账模拟，真实集群为 PVC 挂载）。");
        return Map.of("reused", false, "message", "数据集已挂载到实例 " + id);
    }

    @GetMapping("/instances/{id}/mounts")
    public List<Map<String, Object>> mounts(@PathVariable String id,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        ownInstance(id, role, myTenant);
        return db.mountsOfInstance(id);
    }

    // ============================================================ 优惠券（A-26）

    @GetMapping("/coupons")
    public List<Map<String, Object>> coupons(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.couponsOfTenant(myTenant(role, myTenant, null));
    }

    /** 兑换券：券面金额直接充入余额（条件更新防重复核销） */
    @PostMapping("/coupons/{id}/redeem")
    public Map<String, Object> redeemCoupon(@PathVariable String id,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        Map<String, Object> c = db.coupon(id);
        if (c == null || !tenantId.equals(String.valueOf(c.get("tenant_id")))) {
            throw new RentalService.BizException("优惠券不存在");
        }
        if (db.redeemCoupon(id, tenantId) == 0) {
            throw new RentalService.BizException("优惠券已使用或已过期");
        }
        BigDecimal amount = (BigDecimal) c.get("amount_yuan");
        db.addBalance(tenantId, amount);
        db.insertNotification(newId("n"), tenantId, "账单", "优惠券已兑换",
                "「" + c.get("title") + "」已兑换 ¥" + amount + "，已充入账户余额。");
        return Map.of("amount", amount, "message", "兑换成功，¥" + amount + " 已充入余额");
    }

    // ============================================================ 实名认证（A-28/A-45）

    @GetMapping("/realname")
    public Map<String, Object> realname(@RequestAttribute(value = "authRole", required = false) String role,
                                        @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> r = db.realnameOfTenant(myTenant(role, myTenant, null));
        return r == null ? Map.of("status", "none") : r;
    }

    /** 提交实名：证件号脱敏存储（仅存前 3 后 4），待平台审核 */
    @PostMapping("/realname")
    public Map<String, Object> submitRealname(@RequestBody Map<String, Object> body,
                                              @RequestAttribute(value = "authRole", required = false) String role,
                                              @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String realName = String.valueOf(body.getOrDefault("realName", "")).trim();
        String idNo = String.valueOf(body.getOrDefault("idNo", "")).trim();
        String phone = String.valueOf(body.getOrDefault("phone", "")).trim();
        if (realName.isBlank() || idNo.length() < 15) {
            throw new RentalService.BizException("请填写真实姓名与完整证件号码");
        }
        Map<String, Object> exist = db.realnameOfTenant(tenantId);
        if (exist != null && "pending".equals(String.valueOf(exist.get("status")))) {
            return Map.of("reused", true, "message", "已提交实名认证，等待平台审核");
        }
        String masked = idNo.length() >= 7 ? idNo.substring(0, 3) + "********" + idNo.substring(idNo.length() - 4) : idNo;
        db.insertRealname(newId("rn"), tenantId, realName, masked, phone);
        return Map.of("reused", false, "message", "实名认证已提交，平台审核通过后即可使用端口开放等敏感能力");
    }

    // ============================================================ 学生认证（A-30）

    @GetMapping("/student-cert")
    public Map<String, Object> studentCert(@RequestAttribute(value = "authRole", required = false) String role,
                                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> r = db.studentCertOfTenant(myTenant(role, myTenant, null));
        return r == null ? Map.of("status", "none") : r;
    }

    @PostMapping("/student-cert")
    public Map<String, Object> submitStudentCert(@RequestBody Map<String, Object> body,
                                                 @RequestAttribute(value = "authRole", required = false) String role,
                                                 @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = myTenant(role, myTenant, null);
        String school = String.valueOf(body.getOrDefault("school", "")).trim();
        String studentNo = String.valueOf(body.getOrDefault("studentNo", "")).trim();
        if (school.isBlank() || studentNo.isBlank()) {
            throw new RentalService.BizException("请填写学校与学号");
        }
        Map<String, Object> exist = db.studentCertOfTenant(tenantId);
        if (exist != null && "pending".equals(String.valueOf(exist.get("status")))) {
            return Map.of("reused", true, "message", "已提交学生认证，等待平台审核");
        }
        db.insertStudentCert(newId("st"), tenantId, school, studentNo);
        return Map.of("reused", false, "message", "学生认证已提交，审核通过后下单自动享 9 折优惠");
    }

    // ============================================================ SSH 公钥

    /** 我的公钥列表：实例接入信息里的 authorized_keys 就是这几条 */
    @GetMapping("/ssh-keys")
    public List<Map<String, Object>> sshKeys(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.mySshKeys(requireTenant(role, myTenant));
    }

    /**
     * 上传公钥。只接受公钥（ssh-rsa / ed25519 / ecdsa），私钥粘进来直接拒；
     * 同租户最多 10 条，超了要先删。
     */
    @PostMapping("/ssh-keys")
    public Map<String, Object> addSshKey(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(value = "authRole", required = false) String role,
                                         @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                         @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = requireTenant(role, myTenant);
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        String key = body.get("pubkey") == null ? "" : String.valueOf(body.get("pubkey")).trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new RentalService.BizException("请填写 1~64 字的公钥名称（如 我的工作笔记本）");
        }
        if (!key.matches("(ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-dss)\\s+[A-Za-z0-9+/=]{40,}\\s*\\S{0,64}")) {
            throw new RentalService.BizException("公钥格式不正确：需要完整的 ssh-rsa / ssh-ed25519 公钥一行（不要粘私钥）");
        }
        if (key.length() > 2048) {
            throw new RentalService.BizException("公钥过长，请确认粘贴的是公钥而不是私钥");
        }
        if (db.mySshKeys(tenantId).size() >= 10) {
            throw new RentalService.BizException("最多保存 10 条公钥，请先删除不再使用的");
        }
        for (Map<String, Object> k : db.mySshKeys(tenantId)) {
            if (key.equals(String.valueOf(k.get("pubkey")).trim())) {
                throw new RentalService.BizException("这条公钥已经保存过了（" + k.get("name") + "）");
            }
        }
        String id = newId("k");
        db.insertSshKey(id, tenantId, name, key);
        db.insertAudit(username == null ? "tenant" : username, "SSH_KEY_ADD", id, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("message", "公钥已保存，实例「开发接入」页可直接复制 authorized_keys 内容");
        return out;
    }

    @DeleteMapping("/ssh-keys/{id}")
    public Map<String, Object> deleteSshKey(@PathVariable String id,
                                            @RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                            @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = requireTenant(role, myTenant);
        Map<String, Object> k = db.sshKey(id);
        if (k == null) {
            throw new RentalService.BizException("公钥不存在");
        }
        if (!tenantId.equals(String.valueOf(k.get("tenant_id")))) {
            throw new RentalService.BizException("无权删除其它租户的公钥");
        }
        db.deleteSshKey(id);
        db.insertAudit(username == null ? "tenant" : username, "SSH_KEY_DEL", id, String.valueOf(k.get("name")));
        return Map.of("id", id, "message", "公钥「" + k.get("name") + "」已删除，已开实例里的旧授权不会自动回收");
    }

    // ============================================================ 实例接入矩阵（A-10/11/12/13/29）

    /** 开发接入入口：SSH / JupyterLab / VSCode / 远程桌面 / 守护进程指引 / 学术加速，按实例下发 */
    @GetMapping("/instances/{id}/access")
    public Map<String, Object> instanceAccess(@PathVariable String id,
                                              @RequestAttribute(value = "authRole", required = false) String role,
                                              @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        Map<String, Object> inst = ownInstance(id, role, myTenant);
        Map<String, Object> out = new LinkedHashMap<>();
        String ip = "10.0.0.11";
        String sshPort = "3" + Math.abs(id.hashCode() % 10000);
        out.put("ssh", "ssh root@" + ip + " -p " + sshPort);
        out.put("jupyter", "http://" + ip + ":" + (30000 + Math.abs(id.hashCode() % 999)) + "/lab");
        out.put("vscode", "http://" + ip + ":" + (31000 + Math.abs(id.hashCode() % 999)) + "/");
        out.put("tensorboard", "http://" + ip + ":" + (32000 + Math.abs(id.hashCode() % 999)) + "/");
        out.put("daemonGuide", "SSH 断开后任务继续运行：nohup python train.py > train.log 2>&1 & 或 tmux new -s train");
        String novnc = db.setting("console.novnc_url");
        out.put("novnc", (novnc == null || novnc.isBlank())
                ? "" : novnc.replace("{ip}", ip).replace("{id}", id));
        out.put("novncNote", (novnc == null || novnc.isBlank())
                ? "远程桌面需真实集群部署 noVNC 组件，当前演示环境未接入" : "远程桌面入口");
        out.put("accelerator", db.setting("academic.accelerator"));
        out.put("acceleratorNote", "学术加速：pip/conda 与 GitHub/HuggingFace 走镜像通道（"
                + db.setting("academic.accelerator") + "），实例内已配置环境变量");
        out.put("note", "演示环境：连接命令按实例分配端口展示，真实开放需集群 Ingress/端口转发组件");
        out.put("status", inst.get("status"));

        // 公钥：租户名下的 authorized_keys 台账，接入页直接给出可粘贴内容，便于排查免密登录
        List<Map<String, Object>> keys = db.mySshKeys(String.valueOf(inst.get("tenant_id")));
        List<Map<String, Object>> ks = new ArrayList<>();
        StringBuilder authorized = new StringBuilder();
        for (Map<String, Object> k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            String pub = String.valueOf(k.get("pubkey")).trim();
            m.put("id", k.get("id"));
            m.put("name", k.get("name"));
            m.put("fingerprint", pub.length() > 34 ? pub.substring(0, 12) + "…" + pub.substring(pub.length() - 18) : pub);
            ks.add(m);
            authorized.append(pub).append('\n');
        }
        out.put("sshKeys", ks);
        out.put("authorizedKeys", authorized.toString().trim());
        out.put("sshKeyNote", ks.isEmpty()
                ? "还没有保存公钥，只能用随机密码登录；在「SSH 公钥」里添加后，这里会给出可直接写入实例的 authorized_keys"
                : "已保存 " + ks.size() + " 条公钥。演示镜像内未运行 sshd，接入真实镜像后由初始化脚本写入 /root/.ssh/authorized_keys");
        out.put("notifyPush", db.instanceNotifyOn(id));
        return out;
    }
}
