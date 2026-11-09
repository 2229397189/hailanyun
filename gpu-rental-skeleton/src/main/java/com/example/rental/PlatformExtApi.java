package com.example.rental;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-7 竞价实例 / A-9 扩容与计费互转 / A-10 开发工具接入 的 HTTP 入口。
 * 独立 Controller，不改既有 TenantExtApi 的业务逻辑；租户隔离沿用同一套会话校验。
 */
@RestController
@RequestMapping("/api")
public class PlatformExtApi {

    private final PlatformExtService svc;
    private final Db db;
    private final K8sClient k8s;

    public PlatformExtApi(PlatformExtService svc, Db db, K8sClient k8s) {
        this.svc = svc;
        this.db = db;
        this.k8s = k8s;
    }

    /** 租户作用域校验：tenant/sub 只能操作自己的实例，admin 可指定任意实例 */
    private Map<String, Object> own(String id, String role, String myTenant) {
        Map<String, Object> inst = db.instance(id);
        if (inst == null) throw new RentalService.BizException("实例不存在");
        boolean scoped = "tenant".equals(role) || "sub".equals(role);
        if (scoped && !String.valueOf(inst.get("tenant_id")).equals(myTenant)) {
            throw new AuthException(403, "无权操作其它租户的实例");
        }
        return inst;
    }

    private static BigDecimal bd(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    private static int integer(Object v, int fallback) {
        return v instanceof Number n ? n.intValue() : fallback;
    }

    // ================================================================ A-7 竞价

    @GetMapping("/spot/prices")
    public Object spotPrices() {
        return svc.spotBoard();
    }

    @GetMapping("/spot/price/{model}")
    public Object spotPrice(@PathVariable String model) {
        return svc.spotPrice(model);
    }

    /** 管理端调整市场价（模拟供需波动，触发抢占） */
    @PostMapping("/admin/spot/price")
    public Object setSpotPrice(@RequestBody Map<String, Object> body,
                              @RequestAttribute(value = "authRole", required = false) String role) {
        if (!"admin".equals(role) && !"ops".equals(role)) {
            throw new AuthException(403, "仅管理员可调整市场价");
        }
        String model = String.valueOf(body.getOrDefault("model", "RTX 5090"));
        return svc.setSpotPrice(model, bd(body.get("price")));
    }

    /** 竞价下单：body = {sku, hours, maxPrice, model} */
    @PostMapping("/spot/bids")
    public Object placeBid(@RequestBody Map<String, Object> body,
                           @RequestAttribute(value = "authRole", required = false) String role,
                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        String tenantId = String.valueOf(body.getOrDefault("tenantId", myTenant));
        if ((myTenant == null || myTenant.isBlank()) && !"admin".equals(role)) {
            throw new AuthException(403, "缺少租户信息，请重新登录");
        }
        if (("tenant".equals(role) || "sub".equals(role)) && myTenant != null
                && !myTenant.equals(tenantId)) {
            throw new AuthException(403, "无权为其它租户下单");
        }
        return svc.placeSpotBid(tenantId,
                String.valueOf(body.getOrDefault("model", "RTX 5090")),
                String.valueOf(body.getOrDefault("sku", "1")),
                integer(body.get("hours"), 1), bd(body.get("maxPrice")));
    }

    @GetMapping("/instances/{id}/spot")
    public Object spotStatus(@PathVariable String id,
                             @RequestAttribute(value = "authRole", required = false) String role,
                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return svc.spotStatus(id);
    }

    // ================================================================ A-9 扩容 / 互转

    /** 运行中扩容数据盘：body = {targetGb} */
    @PostMapping("/instances/{id}/expand-disk")
    public Object expandDisk(@PathVariable String id, @RequestBody Map<String, Object> body,
                             @RequestAttribute(value = "authRole", required = false) String role,
                             @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return svc.expandDisk(id, integer(body.get("targetGb"), 0));
    }

    /** 计费方式互转：body = {targetPlan: payg|package, packageHours} */
    @PostMapping("/instances/{id}/convert-plan")
    public Object convertPlan(@PathVariable String id, @RequestBody Map<String, Object> body,
                              @RequestAttribute(value = "authRole", required = false) String role,
                              @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return svc.convertPlan(id, String.valueOf(body.getOrDefault("targetPlan", "package")),
                integer(body.get("packageHours"), 24));
    }

    /** 分段账：转换前后每一段的单价、秒数、金额，可逐笔核对 */
    @GetMapping("/instances/{id}/billing-segments")
    public Object segments(@PathVariable String id,
                           @RequestAttribute(value = "authRole", required = false) String role,
                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segments", svc.billingSegments(id));
        out.put("conversions", svc.planConversions(id));
        return out;
    }

    // ================================================================ A-10 开发工具

    /** 完整接入矩阵：真实台账 + 连接串 + PyCharm / VSCode Remote-SSH 配置 */
    @GetMapping("/instances/{id}/tools")
    public Object tools(@PathVariable String id,
                        @RequestAttribute(value = "authRole", required = false) String role,
                        @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return svc.toolMatrix(id);
    }

    /** 启动工具：真实下发进程 + 暴露端口；body = {tool} */
    @PostMapping("/instances/{id}/tools")
    public Object startTool(@PathVariable String id, @RequestBody Map<String, Object> body,
                            @RequestAttribute(value = "authRole", required = false) String role,
                            @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return svc.startTool(id, String.valueOf(body.getOrDefault("tool", "jupyterlab")));
    }

    /** 停止工具：真实 kill 进程 + 撤销端口 */
    @PostMapping("/instances/{id}/tools/{tool}/stop")
    public Object stopTool(@PathVariable String id, @PathVariable String tool,
                           @RequestAttribute(value = "authRole", required = false) String role,
                           @RequestAttribute(value = "authTenant", required = false) String myTenant) {
        own(id, role, myTenant);
        return svc.stopTool(id, tool);
    }
}
