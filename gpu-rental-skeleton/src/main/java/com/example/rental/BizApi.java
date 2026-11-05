package com.example.rental;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 官网与控制台的业务动作：拼团、商务需求登记、智能助手。
 *
 * 这三个入口以前点了只弹一句「演示环境，仅展示交互」，现在每条都是真流程：
 * 拼单会预扣拼团价、满员自动成团并真的开实例；需求登记会落库进管理端商务跟进列表；
 * 助手回答带平台实时数据（单价、空闲卡、本租户余额与配额）。
 */
@RestController
@RequestMapping("/api")
public class BizApi {

    private static final String ASSISTANT_SYSTEM = """
            你是 TokenArk 创元方舟 GPU 算力平台的智能助手。回答要求：
            1. 只用「平台实时数据」里的数字，不要编造价格、库存、型号；
            2. 中文、口语化、不超过 150 字，能给结论就不要罗列；
            3. 涉及付款、退款、配额时说明规则，不承诺人工审批结果；
            4. 数据里没有的信息，直说「需要在算力市场页查看」或建议提交工单。
            """;

    private final Db db;
    private final RentalService rental;
    private final GroupCatalog groupCatalog;
    private final LlmClient llm;

    public BizApi(Db db, RentalService rental, GroupCatalog groupCatalog, LlmClient llm) {
        this.db = db;
        this.rental = rental;
        this.groupCatalog = groupCatalog;
        this.llm = llm;
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

    /** 这些动作会动钱或留商务记录，必须用租户账号；管理员不代替租户下单 */
    private String requireTenant(String role, String myTenant) {
        if (!"tenant".equals(role) && !"sub".equals(role)) {
            throw new AuthException(403, "请用租户账号登录后再操作");
        }
        if (myTenant == null) {
            throw new AuthException(403, "会话缺少租户信息，请重新登录");
        }
        return myTenant;
    }

    // ============================================================ 拼团

    /** 立即拼单：预扣拼团价 → 落拼单 → 满员自动成团开实例 */
    @PostMapping("/group/join")
    public Map<String, Object> joinGroup(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(value = "authRole", required = false) String role,
                                         @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                         @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = requireTenant(role, myTenant);
        String cardKey = str(body.get("cardKey"), "");
        int qty;
        try {
            qty = Integer.parseInt(str(body.get("qty"), "1"));
        } catch (NumberFormatException e) {
            throw new RentalService.BizException("拼租数量格式不正确");
        }
        Map<String, Object> out = rental.groupJoin(tenantId, cardKey, qty);
        db.insertAudit(str(username, "tenant"), "GROUP_JOIN", cardKey,
                "拼单 " + out.get("joinId") + " · " + qty + " 张卡 · 预付款 ¥" + out.get("prepay"));
        return out;
    }

    /** 我的拼团：拼单状态 + 该团实时进度 + 成团后的实例状态 */
    @GetMapping("/group/joins")
    public Map<String, Object> myGroupJoins(@RequestAttribute(value = "authRole", required = false) String role,
                                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = requireTenant(role, myTenant);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> j : db.myGroupJoins(tenantId)) {
            Map<String, Object> m = new LinkedHashMap<>(j);
            m.putAll(cardProgress((String) j.get("card_key")));
            Object instanceId = j.get("instance_id");
            if (instanceId != null) {
                Map<String, Object> inst = db.instance(String.valueOf(instanceId));
                if (inst != null) {
                    m.put("instanceStatus", inst.get("status"));
                    m.put("expireAt", inst.get("expire_at"));
                }
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("forming", items.stream().filter(i -> "forming".equals(i.get("status"))).count());
        out.put("formed", items.stream().filter(i -> "formed".equals(i.get("status"))).count());
        return out;
    }

    /** 取消拼单：仅未成团可取消，预付款原路全额退回 */
    @PostMapping("/group/joins/{id}/cancel")
    public Map<String, Object> cancelGroupJoin(@PathVariable String id,
                                               @RequestAttribute(value = "authRole", required = false) String role,
                                               @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                               @RequestAttribute(value = "authUsername", required = false) String username) {
        String tenantId = requireTenant(role, myTenant);
        Map<String, Object> out = rental.cancelGroupJoin(id, tenantId, false);
        db.insertAudit(str(username, "tenant"), "GROUP_CANCEL", id, "预付款 ¥" + out.get("refund") + " 已退回");
        return out;
    }

    /** 目录静态字段 + 实时成团进度，前端「我的拼团」直接渲染，不再自己算 */
    private Map<String, Object> cardProgress(String cardKey) {
        Map<String, Object> card = groupCatalog.card(cardKey);
        if (card == null) {
            return Map.of();
        }
        int total = ((Number) card.get("total")).intValue();
        int base = ((Number) card.get("done")).intValue();
        int joined = db.activeJoinsOfCard(cardKey).stream()
                .mapToInt(r -> ((Number) r.get("qty")).intValue()).sum();
        Map<String, Object> m = new LinkedHashMap<>(card);
        m.put("joined", joined);
        m.put("done", Math.min(total, base + joined));
        m.put("left", Math.max(0, total - base - joined));
        return m;
    }

    // ============================================================ 商务需求登记

    /** 登录租户提交需求（裸金属申请 / 演示机型开通 / 大客户需求），带上租户便于商务回访 */
    @PostMapping("/leads")
    public Map<String, Object> createLead(@RequestBody Map<String, Object> body,
                                          @RequestAttribute(value = "authRole", required = false) String role,
                                          @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                          @RequestAttribute(value = "authUsername", required = false) String username) {
        return submitLead(requireTenant(role, myTenant), body, str(username, "tenant"));
    }

    /** 我的需求：状态与管理端回复都能在这里看到 */
    @GetMapping("/leads")
    public List<Map<String, Object>> myLeads(@RequestAttribute(value = "authRole", required = false) String role,
                                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        return db.myLeads(requireTenant(role, myTenant));
    }

    /**
     * 需求落库。官网未登录也能提交（PublicApi 转调，tenantId 传 null），
     * 登录后提交会额外给租户发一条受理通知，管理端跟进后回复也会推给租户。
     */
    public Map<String, Object> submitLead(String tenantId, Map<String, Object> body, String actor) {
        String source = str(body.get("source"), "官网");
        String model = str(body.get("model"), "");
        String name = str(body.get("contactName"), "");
        String phone = str(body.get("contactPhone"), "");
        String org = str(body.get("org"), "");
        String note = str(body.get("note"), "");
        if (name.isBlank()) {
            throw new RentalService.BizException("请填写联系人姓名，商务才能回访");
        }
        if (!phone.matches("[0-9+\\-() ]{6,20}")) {
            throw new RentalService.BizException("请填写正确的手机号（6~20 位数字）");
        }
        if (note.length() > 500) {
            throw new RentalService.BizException("需求说明请精简到 500 字以内");
        }

        String id = newId("ld");
        db.insertLead(id, tenantId, source, model, name, phone, note, org.isBlank() ? null : org);
        db.insertAudit(actor, "LEAD_CREATE", id, source + (model.isBlank() ? "" : " · " + model) + " · " + name + " " + phone
                + (org.isBlank() ? "" : " · " + org));
        if (tenantId != null) {
            db.insertNotification(newId("n"), tenantId, "商务", "需求已受理",
                    (model.isBlank() ? source : source + " · " + model) + " 需求已提交，商务会尽快联系 " + name + "。");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "new");
        out.put("message", "需求已提交，商务会尽快联系 " + name + "（" + phone + "）");
        return out;
    }

    // ============================================================ 智能助手

    /** 智能助手：优先走已配置的大模型，未配置或调用失败时用内置规则回答（同样带实时数据） */
    @PostMapping("/assistant/chat")
    public Map<String, Object> assistant(@RequestBody Map<String, Object> body,
                                         @RequestAttribute(value = "authRole", required = false) String role,
                                         @RequestAttribute(value = "authTenant", required = false) String myTenant,
                                         @RequestAttribute(value = "authUsername", required = false) String username) {
        boolean tenantScoped = "tenant".equals(role) || "sub".equals(role);
        return ask(body, tenantScoped ? myTenant : null, str(username, "guest"));
    }

    /** 官网未登录也能问，只是回答里没有「本租户余额与配额」这一段（PublicApi 转调，tenantId 传 null） */
    public Map<String, Object> ask(Map<String, Object> body, String tenantId, String actor) {
        String question = str(body.get("message"), "");
        if (question.isBlank()) {
            throw new RentalService.BizException("请输入要咨询的问题");
        }
        if (question.length() > 500) {
            throw new RentalService.BizException("问题请精简到 500 字以内");
        }

        LlmClient.Result r = llm.chat(ASSISTANT_SYSTEM, "平台实时数据：\n" + platformContext(tenantId)
                + "\n\n用户问题：" + question);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("source", r.ok() ? "llm" : "builtin");
        out.put("answer", r.ok() ? r.content() : builtinAnswer(question));
        out.put("message", r.message());
        out.put("context", platformContext(tenantId));
        db.insertAudit(actor, "ASSISTANT_ASK", out.get("source").toString(),
                question.length() > 120 ? question.substring(0, 120) + "…" : question);
        return out;
    }

    /** 助手回答的事实来源：全部现查，避免模型张口就来 */
    private String platformContext(String tenantId) {
        int total = count("SELECT COUNT(*) AS c FROM gpus");
        int free = count("SELECT COUNT(*) AS c FROM gpus WHERE status='free'");
        int running = count("SELECT COUNT(*) AS c FROM instances WHERE status='running'");
        StringBuilder sb = new StringBuilder();
        sb.append("单价 ¥").append(rental.unitPrice()).append(" 元/卡/小时，按秒计费；")
                .append("套餐折扣 日付×").append(rental.discountFor(24))
                .append(" 周付×").append(rental.discountFor(168))
                .append(" 月付×").append(rental.discountFor(720))
                .append("；闲时卡×").append(rental.offpeakDiscount())
                .append("；无卡调试 ¥").append(str(db.setting("rental.debug_price"), "0.10")).append(" 元/小时；")
                .append("学生认证再×0.90。");
        sb.append("卡池 ").append(total).append(" 张，空闲 ").append(free).append(" 张，运行中实例 ")
                .append(running).append(" 个。");
        if (tenantId != null) {
            Map<String, Object> t = db.tenant(tenantId);
            if (t != null) {
                sb.append("当前租户「").append(t.get("name")).append("」余额 ¥").append(t.get("balance_yuan"))
                        .append("，配额 ").append(t.get("quota_gpus")).append(" 卡，已用 ")
                        .append(db.occupiedGpusOfTenant(tenantId)).append(" 卡。");
            }
        }
        return sb.toString();
    }

    private int count(String sql) {
        return ((Number) db.q(sql).get(0).get("c")).intValue();
    }

    /** 未配置大模型时的内置回答：按关键词给结论，数字全部来自实时查询 */
    private String builtinAnswer(String q) {
        String s = q.toLowerCase();
        int total = count("SELECT COUNT(*) AS c FROM gpus");
        int free = count("SELECT COUNT(*) AS c FROM gpus WHERE status='free'");
        BigDecimal price = rental.unitPrice();

        if (has(s, "价格", "多少钱", "计费", "收费", "费用", "套餐")) {
            return "当前 ¥" + price + " 元/卡/小时，按秒计费，释放时没用完的预扣款自动退回余额。"
                    + "套餐：日付×" + rental.discountFor(24) + "、周付×" + rental.discountFor(168)
                    + "、月付×" + rental.discountFor(720) + "；闲时卡再×" + rental.offpeakDiscount()
                    + "；无卡调试 ¥" + str(db.setting("rental.debug_price"), "0.10") + " 元/小时；学生认证再打 9 折。"
                    + "现在卡池 " + total + " 张、空闲 " + free + " 张。";
        }
        if (has(s, "拼团", "拼机", "拼租", "成团")) {
            return "在「多人拼团」页选机型点「立即拼单」：系统预扣整月拼团价并占住名额，满员自动成团、直接开实例（720 小时）。"
                    + "成团前可在「我的拼团」取消，预付款原路全额退回；成团后不可退，但实例可按秒释放、差额退回。";
        }
        if (has(s, "显存", "选型", "推荐", "跑", "微调", "推理", "训练", "多大")) {
            return "按模型规模选显存：7B~13B 微调 / 出图 → 24G（RTX 3090、4090）；32G（RTX 5090）留余量更稳；"
                    + "34B~70B 推理 → 40G~80G（A100、A800）；70B 训练或多卡并行 → 80G（A100/H100）整机 8 卡；"
                    + "超长上下文大模型 → H20 141G。告诉我模型名和批量大小，我帮你算需要几张卡。";
        }
        if (has(s, "退款", "退费", "释放", "退钱")) {
            return "释放实例时按实际使用秒数结算：预扣金额减去实结金额，差额立刻退回余额，账单页能看到明细。"
                    + "关机不结算但保留数据 7 天，7 天内开机数据不丢；到期未续费由巡检自动释放并结算。";
        }
        if (has(s, "到货", "缺货", "售罄", "库存", "没卡")) {
            return "售罄机型点「到货提醒」登记即可，同一机型只保留一条订阅；有卡释放回池时系统自动推送通知并完结订阅。"
                    + "当前空闲 " + free + " 张 / 共 " + total + " 张。";
        }
        if (has(s, "ssh", "连接", "登录不", "密码", "接入")) {
            return "实例详情页点「SSH 接入」可以看到连接命令、端口与随机密码，也能上传自己的公钥；"
                    + "密码忘了用「重置密码」，重置后实例会进入 resetting，数秒后恢复。";
        }
        if (has(s, "配额", "扩容", "不够")) {
            return "配额在「账户中心」能看到已用 / 总量；不够用时提交工单或商务需求申请扩容，管理员在后台改配额立即生效，"
                    + "下单时超配额会被直接拦住，不会先扣钱再失败。";
        }
        if (has(s, "发票", "开票")) {
            return "在「账户中心 → 发票」提交开票信息，平台按月汇总已结算金额开票，提交后可在列表里看到状态。";
        }
        if (has(s, "工单", "客服", "人工", "投诉")) {
            return "点右侧「联系客服」或控制台「工单」提交，主题写清楚实例 ID 和现象，管理员回复后你会在消息中心收到通知。";
        }
        return "我可以帮你算价格与套餐、按模型推荐显存、讲清拼团与退款规则、登记到货提醒、排查 SSH 接入与配额问题。"
                + "当前平台 " + total + " 张卡、空闲 " + free + " 张、单价 ¥" + price + " 元/卡/小时。"
                + "需要人工跟进就直接提交工单。";
    }

    private static boolean has(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
