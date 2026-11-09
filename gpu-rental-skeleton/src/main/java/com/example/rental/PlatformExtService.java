package com.example.rental;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A-7 竞价实例 / A-9 扩容与计费互转 / A-10 开发工具接入 的业务实现。
 *
 * <p>设计依据（业界做法，不臆造）：
 * <ul>
 *   <li>竞价 = AWS Spot / 抢占式 GPU：用户出价上限 + 波动市场价，出价≥市场价才能运行；
 *       市场价超过出价时先发回收通知（默认 30 秒宽限），再强制中断。折扣通常为按量价 2~4 折，无 SLA。</li>
 *   <li>计费互转 = AutoDL「到期转按量」+ 按秒计费：转换时刻把账期切成两段，
 *       旧段按秒实结，新计划按剩余时长重新预扣，差额多退少补，账期可追溯。</li>
 *   <li>数据盘扩容 = AutoDL「实例扩容数据盘」：运行中可扩/缩，按剩余时长比例补收盘费。</li>
 *   <li>工具接入 = Packet.ai / Runcrate：容器内有真实进程 + 端口 + 各自 token，
 *       JupyterLab 8888 / code-server 8080 / TensorBoard 6006 / SSH 22 / 桌面 6080，
 *       PyCharm 走 Remote-SSH 解释器。</li>
 * </ul>
 *
 * <p>所有能力都落库并通过 K8sClient.exec()/exposePort() 真正下发到运行时；
 * fake 模式下执行器不产生真实进程，接口会如实标注 runtimeMode，不假装已启动。
 */
@Service
public class PlatformExtService {

    /** 竞价回收宽限（毫秒）：给租户保存 checkpoint 的时间，业界通常 30s~2min */
    private static final long SPOT_GRACE_MS = 30_000L;
    /** 竞价价相对按量价的基础折扣（2~4 折取 3 折） */
    private static final BigDecimal SPOT_DISCOUNT = new BigDecimal("0.30");

    private final Db db;
    private final K8sClient k8s;
    private final RentalService rental;
    private final TransactionTemplate tx;

    public PlatformExtService(Db db, K8sClient k8s, RentalService rental,
                              PlatformTransactionManager tm) {
        this.db = db;
        this.k8s = k8s;
        this.rental = rental;
        this.tx = new TransactionTemplate(tm);
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(v));
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    // ================================================================ A-7 竞价实例

    /** 当前市场价：按型号取，没初始化时按该型号按量价 × 折扣兜底（懒初始化，不臆造数据） */
    public Map<String, Object> spotPrice(String model) {
        Map<String, Object> row = db.one("SELECT * FROM spot_prices WHERE model=?", model);
        if (row == null) {
            BigDecimal ondemand = onDemandPriceOf(model);
            BigDecimal market = money(ondemand.multiply(SPOT_DISCOUNT));
            db.update("INSERT INTO spot_prices(model, market_price, ondemand_price, updated_at) VALUES (?,?,?,?)",
                    model, market, ondemand, new Timestamp(System.currentTimeMillis()));
            row = db.one("SELECT * FROM spot_prices WHERE model=?", model);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        out.put("marketPrice", dec(row.get("market_price")));
        out.put("ondemandPrice", dec(row.get("ondemand_price")));
        BigDecimal mp = dec(row.get("market_price"));
        BigDecimal op = dec(row.get("ondemand_price"));
        out.put("discount", op.signum() > 0
                ? mp.divide(op, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        out.put("updatedAt", row.get("updated_at"));
        return out;
    }

    /** 型号 → 按量挂牌价：与 SKU 单价同一口径，避免竞价报价和结算对不上 */
    private BigDecimal onDemandPriceOf(String model) {
        String sku = "RTX 4060 Laptop".equals(model) ? "local4060"
                : "RTX 5090".equals(model) ? "1" : "1";
        return rental.unitPriceFor(sku, 1, (Map<String, Object>) null);
    }

    /** 全部型号的市场价看板 */
    public List<Map<String, Object>> spotBoard() {
        List<String> models = db.q("SELECT DISTINCT model FROM gpus ORDER BY model")
                .stream().map(r -> String.valueOf(r.get("model"))).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String m : models) out.add(spotPrice(m));
        return out;
    }

    /** 管理端调价：模拟供需波动；price 必须 > 0 且不超过按量价的 2 倍，防止误操作把竞价抬成天价 */
    public Map<String, Object> setSpotPrice(String model, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new RentalService.BizException("市场价必须大于 0");
        }
        BigDecimal ondemand = onDemandPriceOf(model);
        if (price.compareTo(ondemand.multiply(new BigDecimal("2"))) > 0) {
            throw new RentalService.BizException("市场价不能超过按量价的 2 倍");
        }
        spotPrice(model); // 确保行存在
        db.update("UPDATE spot_prices SET market_price=?, updated_at=? WHERE model=?",
                money(price), new Timestamp(System.currentTimeMillis()), model);
        db.update("INSERT INTO spot_events(instance_id, event, market_price, detail, created_at) "
                        + "VALUES (?,?,?,?,?)",
                "-", "price_changed", money(price), model + " 市场价调整为 " + money(price),
                new Timestamp(System.currentTimeMillis()));
        return spotPrice(model);
    }

    /**
     * 竞价下单：出价必须 ≥ 当前市场价，否则直接拒绝（业界「出价低于现货价拿不到资源」）。
     * 单价比按量价低，但随时可能被回收；结算仍按秒，用竞价实际成交价。
     */
    public Map<String, Object> placeSpotBid(String tenantId, String model, String sku,
                                            int hours, BigDecimal maxPrice) {
        Map<String, Object> price = spotPrice(model);
        BigDecimal market = dec(price.get("marketPrice"));
        if (maxPrice == null || maxPrice.signum() <= 0) {
            throw new RentalService.BizException("请填写出价上限");
        }
        if (maxPrice.compareTo(market) < 0) {
            throw new RentalService.BizException("出价 ¥" + money(maxPrice)
                    + " 低于当前市场价 ¥" + market + "，竞价实例无法启动，请提高出价或改用按量实例");
        }
        // 成交价 = 当前市场价（不超过用户出价），与 AWS Spot 一致
        BigDecimal dealPrice = market.min(maxPrice);
        String orderNo = "spot-" + newId("b");
        // 复用既有「指定价下单」通道（拼团用的），成交价写进订单快照，后续释放结算同口径
        Map<String, Object> created = rental.createOrderPriced(tenantId, sku, hours,
                orderNo, dealPrice, null, null, 0);
        String instanceId = String.valueOf(created.get("instanceId"));
        db.update("INSERT INTO spot_bids(instance_id, tenant_id, max_price, market_at_bid, status, created_at) "
                        + "VALUES (?,?,?,?,'active',?)",
                instanceId, tenantId, money(maxPrice), market, new Timestamp(System.currentTimeMillis()));
        db.update("UPDATE instances SET billing_plan='spot' WHERE id=?", instanceId);
        db.update("UPDATE orders SET billing_plan='spot' WHERE id=?", created.get("orderId"));
        db.update("UPDATE billing_segments SET plan='spot', unit_price=? "
                + "WHERE instance_id=? AND closed_at IS NULL", dealPrice, instanceId);
        db.update("INSERT INTO spot_events(instance_id, event, market_price, max_price, detail, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                instanceId, "bid_accepted", market, money(maxPrice),
                "竞价成交 ¥" + dealPrice + "/卡时，出价上限 ¥" + money(maxPrice),
                new Timestamp(System.currentTimeMillis()));
        Map<String, Object> out = new LinkedHashMap<>(created);
        out.put("billingMode", "spot");
        out.put("marketPrice", market);
        out.put("dealPrice", dealPrice);
        out.put("maxPrice", money(maxPrice));
        out.put("risk", "竞价实例无 SLA：市场价超过出价上限时，平台提前 30 秒通知并强制回收，请把 checkpoint 存到数据盘/对象存储");
        return out;
    }

    /**
     * 竞价巡检（Reconciler 每 tick 调）：市场价 > 出价上限 → 先写 notice 事件 + 通知，
     * 超过宽限期后强制释放实例并结算。真实抢占，不是文案。
     */
    public void checkSpotInterruptions() {
        List<Map<String, Object>> bids = db.q("SELECT * FROM spot_bids WHERE status='active'");
        for (Map<String, Object> bid : bids) {
            String instanceId = String.valueOf(bid.get("instance_id"));
            Map<String, Object> inst = db.instance(instanceId);
            if (inst == null || "released".equals(String.valueOf(inst.get("status")))) {
                db.update("UPDATE spot_bids SET status='released' WHERE instance_id=?", instanceId);
                continue;
            }
            Map<String, Object> gpu = db.one(
                    "SELECT model FROM gpus WHERE instance_id=? LIMIT 1", instanceId);
            String model;
            if (gpu != null) {
                model = String.valueOf(gpu.get("model"));
            } else {
                // 本机卡是多实例共享、不独占占卡，gpus.instance_id 不会指向本实例；
                // 改用订单 SKU 反推型号，避免拿 5090 的市场价去判断 4060 的竞价（会误回收/误放行）
                Map<String, Object> ord = db.orderById(String.valueOf(inst.get("order_id")));
                String sku = ord == null ? "" : String.valueOf(ord.get("sku"));
                model = "local4060".equals(sku) ? "RTX 4060 Laptop" : "RTX 5090";
            }
            BigDecimal market = dec(spotPrice(model).get("marketPrice"));
            BigDecimal max = dec(bid.get("max_price"));
            if (market.compareTo(max) <= 0) continue; // 仍在出价范围内，继续运行

            String tenantId = String.valueOf(bid.get("tenant_id"));
            Map<String, Object> notice = db.one(
                    "SELECT * FROM spot_events WHERE instance_id=? AND event='notice' ORDER BY id DESC LIMIT 1",
                    instanceId);
            if (notice == null) {
                db.update("INSERT INTO spot_events(instance_id, event, market_price, max_price, detail, created_at) "
                                + "VALUES (?,?,?,?,?,?)",
                        instanceId, "notice", market, max,
                        "市场价 ¥" + market + " 已超过出价 ¥" + max + "，30 秒后回收",
                        new Timestamp(System.currentTimeMillis()));
                db.insertNotification(newId("n"), tenantId, "竞价", "竞价实例即将被回收",
                        "实例 " + instanceId + " 的市场价已涨至 ¥" + market
                                + "，超过您的出价上限 ¥" + max + "。系统将在 30 秒后自动回收，请立即保存 checkpoint。");
                continue;
            }
            Timestamp noticed = (Timestamp) notice.get("created_at");
            if (System.currentTimeMillis() - noticed.getTime() < SPOT_GRACE_MS) continue;

            // 宽限期到：先自动存档 checkpoint，再强制释放 + 按秒结算（A-7）
            String ckpt = "";
            try {
                String ns = "tenant-" + tenantId;
                String pod = String.valueOf(inst.get("pod_name"));
                if ("running".equals(String.valueOf(inst.get("status")))) {
                    // ① 把 /data 打包进容器（docker commit 不含挂载卷内容）；打包失败也继续存档环境
                    try {
                        k8s.exec(ns, pod, "sh", "-c",
                                "mkdir -p /opt/tk-ckpt && timeout 60 tar czf /opt/tk-ckpt/data.tar.gz -C /data . 2>/dev/null || true");
                    } catch (Exception ignored) { }
                    // ② 提交容器为镜像 tokenark/ckpt-<实例>:auto，回收后仍可据此恢复
                    try {
                        ckpt = k8s.snapshot(ns, pod, "tokenark/ckpt-" + instanceId.toLowerCase());
                    } catch (Exception ignored) { }
                    k8s.delete(ns, pod);
                }
                rental.settleAndRelease(inst);
            } catch (Exception e) {
                db.update("UPDATE spot_bids SET status='active' WHERE instance_id=?", instanceId);
                continue;
            }
            db.update("UPDATE spot_bids SET status='interrupted', interrupted_at=? WHERE instance_id=?",
                    new Timestamp(System.currentTimeMillis()), instanceId);
            db.update("INSERT INTO spot_events(instance_id, event, market_price, max_price, detail, created_at) "
                            + "VALUES (?,?,?,?,?,?)",
                    instanceId, "interrupted", market, max,
                    "宽限期结束，实例已被强制回收并结算" + (ckpt.isEmpty() ? "" : "；已自动存档 " + ckpt),
                    new Timestamp(System.currentTimeMillis()));
            db.insertNotification(newId("n"), tenantId, "竞价", "竞价实例已回收",
                    "实例 " + instanceId + " 已被平台回收，未使用费用已退回余额；"
                            + (ckpt.isEmpty() ? "数据盘内容保留至释放清理。"
                            : "已自动存档为镜像 " + ckpt + "（含打包的 /data），可从该镜像恢复环境。"));
        }
    }

    /** 某实例的竞价状态（含事件时间线） */
    public Map<String, Object> spotStatus(String instanceId) {
        Map<String, Object> bid = db.one("SELECT * FROM spot_bids WHERE instance_id=?", instanceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("spot", bid != null);
        if (bid == null) return out;
        out.put("maxPrice", dec(bid.get("max_price")));
        out.put("marketAtBid", dec(bid.get("market_at_bid")));
        out.put("status", bid.get("status"));
        Map<String, Object> gpu = db.one("SELECT model FROM gpus WHERE instance_id=? LIMIT 1", instanceId);
        String model = gpu == null ? "RTX 5090" : String.valueOf(gpu.get("model"));
        out.put("currentMarketPrice", dec(spotPrice(model).get("marketPrice")));
        out.put("events", db.q("SELECT * FROM spot_events WHERE instance_id=? ORDER BY id DESC LIMIT 10", instanceId));
        return out;
    }

    // ================================================================ A-9 数据盘扩容

    /**
     * 运行中扩容数据盘：真实改 PVC/卷容量 + 更新台账 + 按剩余时长比例补收盘费。
     * 只允许扩容（不允许缩容导致数据丢失），上限取系统参数。
     */
    public Map<String, Object> expandDisk(String instanceId, int targetGb) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) throw new RentalService.BizException("实例不存在");
        if ("released".equals(String.valueOf(inst.get("status")))) {
            throw new RentalService.BizException("已释放实例不能扩容");
        }
        int from = ((Number) inst.get("data_disk_gb")).intValue();
        int max = rental.dataDiskMaxGb();
        if (targetGb <= from) throw new RentalService.BizException("目标容量必须大于当前 " + from + " GB");
        if (targetGb > max) throw new RentalService.BizException("超过数据盘上限 " + max + " GB");
        if (from <= 0) throw new RentalService.BizException("该实例下单时未挂数据盘，请先创建带数据盘的实例");

        String tenantId = String.valueOf(inst.get("tenant_id"));
        String ns = "tenant-" + tenantId;
        String pod = String.valueOf(inst.get("pod_name"));
        // 真实下发扩容：执行器在 PVC / Docker 卷上扩容；失败就不改台账、不扣钱
        boolean ok = k8s.expandData(ns, pod, from, targetGb);
        if (!ok) {
            throw new RentalService.BizException("数据盘扩容失败：运行时未确认新容量，请检查存储插件是否支持在线扩容");
        }

        // 补收：按剩余时长比例收扩容增量部分的盘费（AutoDL 口径：扩容即计费）
        Timestamp expire = (Timestamp) inst.get("expire_at");
        long remainMs = Math.max(0L, expire.getTime() - System.currentTimeMillis());
        long remainHours = remainMs / 3_600_000L;
        BigDecimal fee = rental.dataDiskFee(targetGb - from, (int) remainHours);

        return tx.execute(status -> {
            if (fee.signum() > 0) {
                BigDecimal balance = dec(db.one("SELECT balance_yuan FROM tenants WHERE id=?", tenantId)
                        .get("balance_yuan"));
                if (balance.compareTo(fee) < 0) {
                    throw new RentalService.BizException("余额不足：扩容需补收 ¥" + fee);
                }
                db.deductBalance(tenantId, fee);
            }
            db.update("UPDATE instances SET data_disk_gb=? WHERE id=?", targetGb, instanceId);
            db.update("INSERT INTO disk_expansions(instance_id, from_gb, to_gb, fee, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    instanceId, from, targetGb, fee, new Timestamp(System.currentTimeMillis()));
            db.update("INSERT INTO billing_records(instance_id, tenant_id, gpu_count, seconds, unit_price, amount, settled_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    instanceId, tenantId, 0, 0, fee, fee, new Timestamp(System.currentTimeMillis()));
            db.notifyInstance(tenantId, instanceId, "账单", "数据盘已扩容",
                    "数据盘由 " + from + " GB 扩至 " + targetGb + " GB，补收盘费 ¥" + fee + "。");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", instanceId);
            m.put("fromGb", from);
            m.put("toGb", targetGb);
            m.put("fee", fee);
            m.put("runtimeMode", k8s.mode());
            m.put("message", "数据盘已扩容至 " + targetGb + " GB，补收 ¥" + fee);
            return m;
        });
    }

    // ================================================================ A-9 计费方式互转

    /**
     * 按量 ↔ 包时长互转，转换时刻把账期切成两段：
     * ① 旧段按已用秒数 × 旧单价实结，写 billing_segments + billing_records；
     * ② 新段按新计划单价 + 剩余时长重新预扣，差额多退少补，写 plan_conversions；
     * ③ 订单快照价与实例计费计划同步更新，后续释放结算走新口径，不会出现重复扣费。
     */
    public Map<String, Object> convertPlan(String instanceId, String targetPlan, int packageHours) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) throw new RentalService.BizException("实例不存在");
        String status = String.valueOf(inst.get("status"));
        if (!"running".equals(status) && !"stopped".equals(status)) {
            throw new RentalService.BizException("仅运行中或已关机的实例可以转换计费方式");
        }
        if (!"payg".equals(targetPlan) && !"package".equals(targetPlan)) {
            throw new RentalService.BizException("目标计费方式只支持 payg / package");
        }
        if ("package".equals(targetPlan) && (packageHours <= 0 || packageHours > 720)) {
            throw new RentalService.BizException("包时长需为 1~720 小时");
        }
        Map<String, Object> order = db.orderById(String.valueOf(inst.get("order_id")));
        if (order == null) throw new RentalService.BizException("订单不存在");

        String tenantId = String.valueOf(inst.get("tenant_id"));
        String fromPlan = String.valueOf(inst.get("billing_plan") == null ? "payg" : inst.get("billing_plan"));
        if (fromPlan.equals(targetPlan)) {
            throw new RentalService.BizException("当前已是 " + targetPlan + " 计费，无需转换");
        }
        String sku = String.valueOf(order.get("sku"));
        int gpuCount = Math.max(1, ((Number) inst.get("gpu_count")).intValue());
        BigDecimal fromPrice = dec(order.get("unit_price"));
        BigDecimal toPrice = "package".equals(targetPlan)
                ? rental.unitPriceFor(sku, packageHours, tenantId)
                : rental.unitPriceFor(sku, 1, tenantId);
        long usedSeconds = ((Number) inst.get("used_seconds")).longValue();
        BigDecimal prepaid = dec(order.get("amount"));
        int prepaidHours = ((Number) order.get("hours")).intValue();
        long prepaidSeconds = Math.max(1L, prepaidHours * 3600L);

        // ① 旧段按秒实结（上限为原预扣，不能倒扣租户）
        BigDecimal gpuPrepaid = RentalService.amountOf(gpuCount, prepaidSeconds, fromPrice);
        BigDecimal diskPrepaid = prepaid.subtract(gpuPrepaid).max(BigDecimal.ZERO);
        BigDecimal usedAmount = RentalService.amountOf(gpuCount, usedSeconds, fromPrice)
                .add(diskPrepaid.multiply(BigDecimal.valueOf(usedSeconds))
                        .divide(BigDecimal.valueOf(prepaidSeconds), 2, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
        if (usedAmount.compareTo(prepaid) > 0) usedAmount = prepaid;
        final BigDecimal settled = usedAmount; // 固定给事务 lambda 用，避免 effectively-final 问题

        // ② 新段：以转换时刻为起点，按剩余（或包时长）重新预扣
        int newHours = "package".equals(targetPlan) ? packageHours
                : (int) Math.max(1L, (prepaidSeconds - usedSeconds) / 3600L);
        BigDecimal newPrepaid = money(RentalService.amountOf(gpuCount, newHours * 3600L, toPrice));
        BigDecimal unspent = prepaid.subtract(usedAmount).max(BigDecimal.ZERO);
        BigDecimal refund = money(unspent.subtract(newPrepaid)); // 正=退，负=补收
        BigDecimal balance = dec(db.one("SELECT balance_yuan FROM tenants WHERE id=?", tenantId)
                .get("balance_yuan"));
        if (refund.signum() < 0 && balance.compareTo(refund.abs()) < 0) {
            throw new RentalService.BizException("余额不足：转换需补收 ¥" + refund.abs());
        }

        return tx.execute(st -> {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            // 关闭旧账段
            db.update("UPDATE billing_segments SET closed_at=?, closed_reason='convert' "
                    + "WHERE instance_id=? AND closed_at IS NULL", now, instanceId);
            if (usedSeconds > 0) {
                db.update("INSERT INTO billing_records(instance_id, tenant_id, gpu_count, seconds, "
                                + "unit_price, amount, settled_at) VALUES (?,?,?,?,?,?,?)",
                        instanceId, tenantId, gpuCount, usedSeconds, fromPrice, settled, now);
            }
            db.update("INSERT INTO billing_segments(instance_id, plan, unit_price, seconds, amount, "
                            + "started_at, closed_reason) VALUES (?,?,?,?,?,?,?)",
                    instanceId, targetPlan, toPrice, 0L, BigDecimal.ZERO, now, null);
            // 重开账期：used_seconds 清零，后续按新段累计；expire 按新时长顺延
            db.update("UPDATE instances SET used_seconds=0, billing_plan=?, expire_at=? WHERE id=?",
                    targetPlan, new Timestamp(now.getTime() + newHours * 3_600_000L), instanceId);
            db.update("UPDATE orders SET billing_plan=?, unit_price=?, hours=?, amount=? WHERE id=?",
                    targetPlan, toPrice, newHours, newPrepaid, order.get("id"));
            if (refund.signum() > 0) db.addBalance(tenantId, refund);
            else if (refund.signum() < 0) db.deductBalance(tenantId, refund.abs());
            db.update("INSERT INTO plan_conversions(instance_id, from_plan, to_plan, from_price, to_price, "
                            + "settled_amount, new_prepaid, refund, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    instanceId, fromPlan, targetPlan, fromPrice, toPrice, settled, newPrepaid, refund, now);
            db.notifyInstance(tenantId, instanceId, "账单", "计费方式已转换",
                    "已由 " + fromPlan + " 转为 " + targetPlan + "：旧账段实结 ¥" + settled
                            + "，新计划预扣 ¥" + newPrepaid + (refund.signum() >= 0
                            ? "，退回 ¥" + refund : "，补收 ¥" + refund.abs()) + "。");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", instanceId);
            m.put("fromPlan", fromPlan);
            m.put("toPlan", targetPlan);
            m.put("fromPrice", fromPrice);
            m.put("toPrice", toPrice);
            m.put("usedSecondsAtConversion", usedSeconds);
            m.put("settledAmount", settled);
            m.put("newPrepaid", newPrepaid);
            m.put("refund", refund);
            m.put("settlementRule", "旧段 = 单价 × 卡数 × 已用秒数 / 3600 + 数据盘按已用比例，"
                    + "四舍五入到分；转换后 used_seconds 清零、订单快照价改为新计划单价，"
                    + "释放时只结算新段，不会重复扣费");
            m.put("segments", billingSegments(instanceId));
            return m;
        });
    }

    public List<Map<String, Object>> billingSegments(String instanceId) {
        return db.q("SELECT * FROM billing_segments WHERE instance_id=? ORDER BY id", instanceId);
    }

    public List<Map<String, Object>> planConversions(String instanceId) {
        return db.q("SELECT * FROM plan_conversions WHERE instance_id=? ORDER BY id DESC", instanceId);
    }

    // ================================================================ A-10 开发工具接入

    /** 工具注册表：真实端口、真实启动命令、是否需要 token */
    private static final Map<String, int[]> TOOL_PORTS = Map.of(
            "jupyterlab", new int[]{8888},
            "code-server", new int[]{8080},
            "tensorboard", new int[]{6006},
            "ssh", new int[]{22},
            "desktop", new int[]{6080});

    public Map<String, Object> startTool(String instanceId, String tool) {
        Map<String, Object> inst = db.instance(instanceId);
        if (inst == null) throw new RentalService.BizException("实例不存在");
        if (!"running".equals(String.valueOf(inst.get("status")))) {
            throw new RentalService.BizException("仅运行中的实例可以启动开发工具");
        }
        int[] spec = TOOL_PORTS.get(tool);
        if (spec == null) {
            throw new RentalService.BizException("未知工具：" + tool + "，支持 "
                    + String.join(" / ", TOOL_PORTS.keySet()));
        }
        int port = spec[0];
        String ns = "tenant-" + inst.get("tenant_id");
        String pod = String.valueOf(inst.get("pod_name"));
        String token = newId("tk");

        boolean runtimeReal = !"fake".equals(k8s.mode());
        // 先自检依赖：二进制在、但 python 包缺（如 jupyter_core 没装）也会判 MISSING。
        // 缺了就如实标 missing_dependency，绝不把「文件存在 / echo started」当成进程真的起来了。
        String probe = runtimeReal ? k8s.exec(ns, pod, "sh", "-c", dependencyCheck(tool)) : "";
        if (runtimeReal && (probe == null || !probe.trim().contains("READY"))) {
            String detail = "实例镜像内 " + tool + " 依赖不可用"
                    + (probe != null && probe.contains("MISSING:") ? "（缺失：" + probe.trim().split("MISSING:")[1].trim() + "）" : "")
                    + "，无法真实启动。需重建含完整 Python 工具链的 DevBox 镜像后再启用。";
            db.update("INSERT INTO instance_tools(instance_id, tool, port, node_port, token, status, detail, started_at) "
                            + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (instance_id, tool) DO UPDATE SET "
                            + "status=EXCLUDED.status, detail=EXCLUDED.detail, started_at=EXCLUDED.started_at",
                    instanceId, tool, port, 0, token, "missing_dependency", detail,
                    new Timestamp(System.currentTimeMillis()));
            Map<String, Object> miss = new LinkedHashMap<>();
            miss.put("instanceId", instanceId);
            miss.put("tool", tool);
            miss.put("status", "missing_dependency");
            miss.put("runtimeMode", k8s.mode());
            miss.put("detail", detail);
            miss.put("probe", probe == null ? "" : probe.trim());
            miss.put("fix", "在 DevBox 镜像中补齐 Python 工具链（pip install jupyterlab tensorboard code-server）后重建实例");
            return miss;
        }
        // 用 Docker 的 Detach:true 真正后台拉起进程（execDetached），
        // 否则 docker exec 会话结束时后台进程会被一起回收，造成「假 running」。
        if (runtimeReal) k8s.execDetached(ns, pod, "sh", "-c", toolCommand(tool, port, token));
        // 真实暴露端口，拿到宿主可访问地址
        int nodePort = k8s.exposePort(ns, pod, port);
        // 启动后校验「端口是否真的在 LISTEN」。kill -0 对僵尸进程仍返回真（PID 槽位未回收），
        // 会误判为存活；只有端口真正被监听，才能证明进程在干活。
        // 容器内 ss/netstat 可能不可用，统一用 python3 做 TCP connect 探测（任意端口通用），轮询最多 15s。
        boolean alive = false;
        if (runtimeReal && nodePort > 0) {
            String listenProbe = "for i in $(seq 1 15); do "
                    + "/usr/bin/python3 -c \"import socket,sys; s=socket.socket(); s.settimeout(1); s.connect(('127.0.0.1'," + port + ")); s.close()\" 2>/dev/null && echo LISTENING && break; "
                    + "sleep 1; done";
            String out = k8s.exec(ns, pod, "sh", "-c", listenProbe);
            alive = out != null && out.contains("LISTENING");
        }
        String status = alive ? "running" : (runtimeReal ? "failed" : "simulated");
        if ("failed".equals(status)) {
            db.update("INSERT INTO instance_tools(instance_id, tool, port, node_port, token, status, detail, started_at) "
                            + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (instance_id, tool) DO UPDATE SET "
                            + "status=EXCLUDED.status, detail=EXCLUDED.detail, started_at=EXCLUDED.started_at",
                    instanceId, tool, port, nodePort, token, status,
                    "端口暴露失败，请检查运行时", new Timestamp(System.currentTimeMillis()));
            throw new RentalService.BizException("工具启动失败：端口暴露或进程拉起未成功（" + tool + " 端口 " + port + "）");
        }
        db.update("INSERT INTO instance_tools(instance_id, tool, port, node_port, token, status, detail, started_at) "
                        + "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (instance_id, tool) DO UPDATE SET "
                        + "node_port=EXCLUDED.node_port, token=EXCLUDED.token, status=EXCLUDED.status, "
                        + "detail=EXCLUDED.detail, started_at=EXCLUDED.started_at",
                instanceId, tool, port, nodePort, token, status,
                runtimeReal ? "容器内进程已下发" : "模拟运行时：仅登记台账，无真实进程",
                new Timestamp(System.currentTimeMillis()));
        if (nodePort > 0) db.upsertInstancePort(instanceId, port, nodePort);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", instanceId);
        m.put("tool", tool);
        m.put("port", port);
        m.put("nodePort", nodePort);
        m.put("token", token);
        m.put("status", status);
        m.put("runtimeMode", k8s.mode());
        m.put("url", urlOf(port, nodePort, tool, token));
        m.put("sshTunnel", "ssh -p " + nodePort + " -L " + port + ":localhost:" + port + " root@localhost");
        m.put("execOutput", "进程已后台下发（Detach 模式，无同步回显）；启动后用容器内 python3 对端口做 TCP connect 探测，确认真正 LISTEN 才判 running");
        m.put("note", runtimeReal
                ? "进程已在实例容器内启动，端口已映射；令牌仅本租户可见"
                : "当前为模拟运行时，只登记台账，不产生真实进程；切 local/real 模式后才会真正启动");
        return m;
    }

    /** 启动前的依赖自检：不只看二进制在不在，还要真能跑起来（--version）。
     *  否则像「jupyter 二进制在但 jupyter_core 没装」这种坏脚本会被误判为就绪。 */
    private static String dependencyCheck(String tool) {
        return switch (tool) {
            case "jupyterlab" ->
                    "command -v jupyter >/dev/null 2>&1 && jupyter --version >/dev/null 2>&1 && echo READY || echo MISSING:jupyter";
            case "code-server" ->
                    "command -v code-server >/dev/null 2>&1 && code-server --version >/dev/null 2>&1 && echo READY || echo MISSING:code-server";
            case "tensorboard" ->
                    "command -v tensorboard >/dev/null 2>&1 && tensorboard --version >/dev/null 2>&1 && echo READY || echo MISSING:tensorboard";
            case "ssh" ->
                    "[ -x /usr/sbin/sshd ] && echo READY || echo MISSING:sshd";
            case "desktop" ->
                    "command -v x11vnc >/dev/null 2>&1 && command -v Xvfb >/dev/null 2>&1 && command -v websockify >/dev/null 2>&1 && [ -f /opt/novnc/vnc.html ] && echo READY || echo MISSING:desktop";
            default -> "echo MISSING:" + tool;
        };
    }

    // 用 setsid 完全脱离 exec 会话的进程组，再用 nohup + 重定向 stdin 到 /dev/null，
    // 保证 docker exec 返回后进程仍在容器内存活（否则就是假 running）。
    private static String toolCommand(String tool, int port, String token) {
        String base = "mkdir -p /tmp/jobs && setsid nohup ";
        String redir = " > /tmp/jobs/" + tool + ".log 2>&1 < /dev/null & echo $! > /tmp/jobs/" + tool + ".pid";
        return switch (tool) {
            case "jupyterlab" -> base + "jupyter lab --ip=0.0.0.0 --port=" + port
                    + " --no-browser --ServerApp.token='" + token + "' --allow-root " + redir;
            case "code-server" -> base + "code-server --bind-addr 0.0.0.0:" + port
                    + " --auth password " + redir;
            case "tensorboard" -> base + "tensorboard --host 0.0.0.0 --port=" + port
                    + " --logdir /data/runs " + redir;
            case "ssh" -> base + "ssh-keygen -A 2>/dev/null; /usr/sbin/sshd -p " + port + " " + redir;
            case "desktop" -> base
                    + "Xvfb :0 -screen 0 1280x720x24 > /tmp/jobs/xvfb.log 2>&1 < /dev/null & sleep 1; "
                    + "openbox > /tmp/jobs/openbox.log 2>&1 < /dev/null & sleep 1; "
                    + "x11vnc -display :0 -forever -rfbport 5900 > /tmp/jobs/x11vnc.log 2>&1 < /dev/null & sleep 1; "
                    // websockify 在 6080 提供 noVNC 网页(现代浏览器 RFC6455 WebSocket)，
                    // 并把 WS 桥接到本机 5900 的原始 VNC(x11vnc)。这样浏览器直接开 /vnc.html 即可连，
                    // 规避 x11vnc 内置 Hixie 老协议不被现代 noVNC 支持的问题。
                    + "websockify --web /opt/novnc " + port + " localhost:5900 " + redir;
            default -> "echo unknown-tool";
        };
    }

    // 启动后用于 pgrep 验证进程是否真存活的匹配串
    private static String processMatch(String tool, int port) {
        return switch (tool) {
            case "jupyterlab" -> "jupyter";
            case "code-server" -> "code-server";
            case "tensorboard" -> "tensorboard";
            case "ssh" -> "sshd.*" + port;
            case "desktop" -> "x11vnc";
            default -> String.valueOf(port);
        };
    }

    private static String urlOf(int port, int nodePort, String tool, String token) {
        if (nodePort <= 0) return null;
        String host = "localhost:" + nodePort;
        return switch (tool) {
            case "jupyterlab" -> "http://" + host + "/lab?token=" + token;
            case "tensorboard" -> "http://" + host + "/";
            case "code-server" -> "http://" + host + "/?folder=/data";
            case "desktop" -> "http://" + host + "/vnc.html";
            default -> "ssh -p " + nodePort + " root@localhost";
        };
    }

    public Map<String, Object> stopTool(String instanceId, String tool) {
        Map<String, Object> row = db.one("SELECT * FROM instance_tools WHERE instance_id=? AND tool=?",
                instanceId, tool);
        if (row == null) throw new RentalService.BizException("该工具未登记");
        Map<String, Object> inst = db.instance(instanceId);
        String ns = inst == null ? "" : "tenant-" + inst.get("tenant_id");
        String pod = inst == null ? "" : String.valueOf(inst.get("pod_name"));
        int port = ((Number) row.get("port")).intValue();
        if (!"fake".equals(k8s.mode()) && inst != null) {
            k8s.exec(ns, pod, "sh", "-c",
                    "pkill -f 'jupyter.*" + port + "|code-server.*" + port + "|tensorboard.*" + port + "' || true");
            k8s.removePort(ns, pod, port);
        }
        db.update("UPDATE instance_tools SET status='stopped' WHERE instance_id=? AND tool=?", instanceId, tool);
        db.update("DELETE FROM instance_ports WHERE instance_id=? AND port=?", instanceId, port);
        return Map.of("instanceId", instanceId, "tool", tool, "status", "stopped");
    }

    /** 接入矩阵：真实台账 + 连接串 + PyCharm/VSCode 的 Remote-SSH 配置 */
    public Map<String, Object> toolMatrix(String instanceId) {
        List<Map<String, Object>> rows = db.q(
                "SELECT * FROM instance_tools WHERE instance_id=? ORDER BY port", instanceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("runtimeMode", k8s.mode());
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String name : new String[]{"jupyterlab", "code-server", "tensorboard", "ssh", "desktop"}) {
            Map<String, Object> found = rows.stream()
                    .filter(r -> name.equals(String.valueOf(r.get("tool")))).findFirst().orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tool", name);
            m.put("port", TOOL_PORTS.get(name)[0]);
            m.put("status", found == null ? "stopped" : found.get("status"));
            if (found != null) {
                int np = found.get("node_port") == null ? 0 : ((Number) found.get("node_port")).intValue();
                m.put("nodePort", np);
                m.put("url", urlOf(TOOL_PORTS.get(name)[0], np, name,
                        String.valueOf(found.get("token") == null ? "" : found.get("token"))));
                m.put("startedAt", found.get("started_at"));
            }
            tools.add(m);
        }
        out.put("tools", tools);
        out.put("pycharm", Map.of(
                "mode", "Remote-SSH 解释器",
                "steps", List.of(
                        "PyCharm Professional → Settings → Project → Python Interpreter → Add → SSH Interpreter",
                        "Host localhost，端口取 ssh 工具的 nodePort，用户名 root",
                        "解释器路径 /usr/bin/python3，同步目录映射到 /data",
                        "Deployment → Mappings 把本地项目目录映射到 /data/<project>")));
        out.put("vscode", Map.of(
                "mode", "Remote-SSH / code-server",
                "sshConfig", "Host tokenark\n  HostName localhost\n  User root\n  Port <ssh nodePort>\n  LocalForward 8888 localhost:8888\n  LocalForward 6006 localhost:6006"));
        return out;
    }
}
