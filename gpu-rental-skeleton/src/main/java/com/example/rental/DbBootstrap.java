package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * 数据库初始化：只在“表不存在”时建表、只在“空库”时灌演示数据。
 *
 * 为什么要自己做而不是用 spring.sql.init：
 * 那个每次启动都执行脚本，配合 DROP TABLE 会把生产数据清空——
 * 对演示项目无所谓，对企业项目是事故。这里做成幂等：
 *   有表 → 跳过建表；有数据 → 跳过种子。
 */
@Component
@Order(1)
public class DbBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbBootstrap.class);

    private final DataSource ds;
    private final JdbcTemplate jdbc;

    public DbBootstrap(DataSource ds, JdbcTemplate jdbc) {
        this.ds = ds;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection c = ds.getConnection()) {
            if (!tableExists(c)) {
                log.info("首次启动：初始化表结构（schema.sql）");
                ScriptUtils.executeSqlScript(c, new ClassPathResource("schema.sql"));
            }

            Integer users = count("users");
            Integer tenants = count("tenants");
            if (users == 0 && tenants == 0) {
                log.info("空库：写入演示数据（data.sql）");
                ScriptUtils.executeSqlScript(c, new ClassPathResource("data.sql"));
            } else {
                log.info("已有数据，跳过种子（users={}，tenants={}）", users, tenants);
            }

            // 老库升级：新表与新参数行幂等补齐（不会动已有数据）
            ensureUpgrades(c);
        }
    }

    /** 老库升级：新表用 IF NOT EXISTS 幂等补齐，新参数行按主键守卫插入（不动已有数据） */
    private void ensureUpgrades(Connection c) {
        ScriptUtils.executeSqlScript(c, new ClassPathResource("schema.sql")); // 全是 IF NOT EXISTS，幂等
        // 老库缺 users.created_at（子账号列表需要）：幂等补列
        try {
            jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP");
        } catch (Exception e) {
            log.warn("users.created_at 升级跳过：{}", e.getMessage());
        }
        // 告警 AI 诊断 / 巡检转工单：幂等补列
        try {
            jdbc.execute("ALTER TABLE alerts ADD COLUMN IF NOT EXISTS diagnosis VARCHAR(512)");
        } catch (Exception e) {
            log.warn("alerts.diagnosis 升级跳过：{}", e.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE inspection_items ADD COLUMN IF NOT EXISTS diagnosis VARCHAR(512)");
        } catch (Exception e) {
            log.warn("inspection_items.diagnosis 升级跳过：{}", e.getMessage());
        }
        try {
            jdbc.execute("ALTER TABLE inspection_items ADD COLUMN IF NOT EXISTS ticket_id VARCHAR(32)");
        } catch (Exception e) {
            log.warn("inspection_items.ticket_id 升级跳过：{}", e.getMessage());
        }
        // Key 月度额度按 token 级金额算，2 位小数会把 0.0000x 抹成 0，加宽到 6 位
        try {
            jdbc.execute("ALTER TABLE api_keys ALTER COLUMN quota_yuan TYPE DECIMAL(14,6)");
        } catch (Exception e) {
            log.warn("api_keys.quota_yuan 加宽跳过：{}", e.getMessage());
        }
        // 本机 agent 补齐整机参数：CPU 型号/核心/使用率、磁盘、系统版本、运行时长
        for (String ddl : new String[]{
                "ALTER TABLE agent_snapshots ADD COLUMN IF NOT EXISTS os_version VARCHAR(128)",
                "ALTER TABLE agent_snapshots ADD COLUMN IF NOT EXISTS cpu_model VARCHAR(128)",
                "ALTER TABLE agent_snapshots ADD COLUMN IF NOT EXISTS cpu_cores INTEGER",
                "ALTER TABLE agent_snapshots ADD COLUMN IF NOT EXISTS disk_total_gb DOUBLE PRECISION",
                "ALTER TABLE agent_snapshots ADD COLUMN IF NOT EXISTS disk_used_gb DOUBLE PRECISION",
                "ALTER TABLE agent_snapshots ADD COLUMN IF NOT EXISTS uptime_seconds BIGINT"}) {
            try {
                jdbc.execute(ddl);
            } catch (Exception e) {
                log.warn("agent_snapshots 升级跳过：{}", e.getMessage());
            }
        }
        // MaaS Token 计量按 4 位小数扣费，余额列从 DECIMAL(12,2) 加宽（老库幂等）
        try {
            jdbc.execute("ALTER TABLE tenants ALTER COLUMN balance_yuan TYPE DECIMAL(14,4)");
        } catch (Exception e) {
            log.warn("tenants.balance_yuan 加宽跳过：{}", e.getMessage());
        }
        // 拼团价是「元/卡/月」，换算成小时单价除不尽（1300/720=1.8056），2 位小数会把快照价四舍五入，
        // 结算时和预付款对不上。订单与账单的单价列加宽到 4 位，保证预扣与实结同口径。
        try {
            jdbc.execute("ALTER TABLE orders ALTER COLUMN unit_price TYPE DECIMAL(14,4)");
            jdbc.execute("ALTER TABLE billing_records ALTER COLUMN unit_price TYPE DECIMAL(14,4)");
        } catch (Exception e) {
            log.warn("unit_price 加宽跳过：{}", e.getMessage());
        }
        // 工具台账 status 需容纳 missing_dependency（18 字符），老库 VARCHAR(16) 会超长报错
        try {
            jdbc.execute("ALTER TABLE instance_tools ALTER COLUMN status TYPE VARCHAR(32)");
        } catch (Exception e) {
            log.warn("instance_tools.status 加宽跳过：{}", e.getMessage());
        }
        // 老租户没有邀请码：一次性补齐 8 位码（已有的不覆盖），控制台「邀请赠送」才有真码可复制
        try {
            for (Map<String, Object> r : jdbc.queryForList("SELECT id FROM tenants WHERE invite_code IS NULL")) {
                String code = freshInviteCode();
                jdbc.update("UPDATE tenants SET invite_code=? WHERE id=?", code, r.get("id"));
                log.info("租户 {} 补齐邀请码 {}", r.get("id"), code);
            }
        } catch (Exception e) {
            log.warn("邀请码补齐跳过：{}", e.getMessage());
        }
        String[][] settingSeeds = {
                {"plan.discount.h24", "0.95", "日付套餐折扣（24 小时）"},
                {"plan.discount.h168", "0.90", "周付套餐折扣（168 小时）"},
                {"plan.discount.h720", "0.85", "月付套餐折扣（720 小时）"},
                {"brand.name", "TokenArk 创元方舟", "白标：平台名称"},
                {"brand.slogan", "GPU 算力即服务", "白标：Slogan"},
                {"brand.logo_text", "TokenArk", "白标：Logo 文案"},
                {"brand.primary_color", "#7c3aed", "白标：品牌主色"},
                {"brand.copyright", "© 2026 TokenArk 创元方舟 · 保留所有权利", "白标：页脚版权"},
                {"brand.icp", "", "白标：ICP 备案号（可为空）"},
                {"alert.webhook_url", "", "告警 webhook 通知地址（可选，留空则仅记录日志）"},
                {"llm.base_url", "", "大模型 OpenAI 兼容接口地址（可选，如 https://api.openai.com/v1）"},
                {"llm.api_key", "", "大模型 API Key（可选）"},
                {"llm.model", "gpt-4o-mini", "大模型名称"},
                {"agent.token", "hly-local-agent-2026", "本机 GPU 采集 agent 上报令牌（演示默认值，可在系统参数里改）"},
                {"rental.offpeak_discount", "0.5", "闲时卡折扣（22:00-08:00 包夜时段，闲时 SKU 单价 × 此系数）"},
                {"rental.debug_price", "0.10", "无卡调试模式单价（元 / 时，仅 CPU 不占 GPU）"},
                {"rental.local_price", "0.50", "本机真实显卡（RTX 4060 Laptop）单价（元 / 卡 / 时）"},
                {"rental.data_disk_price", "0.02", "数据盘扩容单价（元 / GB / 日，创建实例页选盘时按此计费，不足一日按比例）"},
                {"rental.data_disk_max_gb", "2000", "数据盘可扩容上限（GB，创建实例页输入框的最大值）"},
                {"academic.accelerator", "gitclone.com / hf-mirror.com", "学术资源加速镜像（GitHub / HuggingFace，实例内展示用）"},
                {"console.novnc_url", "", "远程桌面 NoVNC 入口模板（可选，留空则页面标注需真实集群部署）"},
                {"invite.min_recharge", "1", "邀请返券门槛：被邀请人首次充值满多少元触发发券"},
                {"invite.reward_invitee", "5", "被邀请人首充赠送代金券金额（元）"},
                {"invite.reward_inviter", "3", "邀请人获得的代金券金额（元）"},
                {"invite.coupon_days", "90", "邀请赠送代金券有效期（天）"},
        };
        for (String[] s : settingSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM settings WHERE k=?", Integer.class, s[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO settings(k,v,note) VALUES (?,?,?)", s[0], s[1], s[2]);
            }
        }
        String[][] dictSeeds = {
                {"d-alert-p0", "alert_level", "P0", "严重（P0）", "1", "TRUE"},
                {"d-alert-p1", "alert_level", "P1", "警告（P1）", "2", "TRUE"},
                {"d-alert-p2", "alert_level", "P2", "提示（P2）", "3", "TRUE"},
                {"d-inst-provisioning", "instance_status", "provisioning", "创建中", "1", "TRUE"},
                {"d-inst-running", "instance_status", "running", "运行中", "2", "TRUE"},
                {"d-inst-releasing", "instance_status", "releasing", "回收中", "3", "TRUE"},
                {"d-inst-released", "instance_status", "released", "已释放", "4", "TRUE"},
                {"d-inst-failed", "instance_status", "failed", "失败", "5", "TRUE"},
                {"d-node-master", "node_role", "master", "控制节点", "1", "TRUE"},
                {"d-node-agent", "node_role", "agent", "计算节点", "2", "TRUE"},
        };
        for (String[] d : dictSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dict_items WHERE id=?", Integer.class, d[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO dict_items(id, category, code, label, sort, enabled) VALUES (?,?,?,?,?,?)",
                        d[0], d[1], d[2], d[3], Integer.parseInt(d[4]), Boolean.parseBoolean(d[5]));
            }
        }
        // 演示数据对齐：6 个命名租户与对应账号（按 id/username 幂等补齐，不覆盖已有数据）
        String[][] tenantSeeds = {
                {"t2", "凌云机器人", "4", "500.00", "active", "tenant_b", "2026-10-22 14:20:00"},
                {"t4", "幻核互动娱乐", "4", "2000.00", "active", "tenant_c", "2026-10-26 11:00:00"},
                {"t5", "极光量化科技", "2", "1200.00", "active", "tenant_d", "2026-10-28 15:30:00"},
                {"t6", "蓝湾医疗影像", "2", "800.00", "active", "tenant_e", "2026-10-30 09:40:00"},
        };
        for (String[] t : tenantSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id=?", Integer.class, t[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO tenants(id, name, quota_gpus, balance_yuan, status, owner, created_at) VALUES (?,?,?,?,?,?,?)",
                        t[0], t[1], Integer.parseInt(t[2]), new BigDecimal(t[3]), t[4], t[5],
                        java.sql.Timestamp.valueOf(t[6]));
            }
        }
        // 老库 t2 名字从“演示租户 B”改成正式命名（只改一次，之后保持不动）
        jdbc.update("UPDATE tenants SET name='凌云机器人' WHERE id='t2' AND name='演示租户 B'");
        String[][] userSeeds = {
                {"u6", "tenant_c", "幻核互动娱乐 · 管理员", "tenant", "t4"},
                {"u7", "tenant_d", "极光量化科技 · 管理员", "tenant", "t5"},
                {"u8", "tenant_e", "蓝湾医疗影像 · 管理员", "tenant", "t6"},
        };
        for (String[] u : userSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, u[1]);
            if (n == 0) {
                jdbc.update("INSERT INTO users(id, username, display_name, role, tenant_id, status) VALUES (?,?,?,?,?,'active')",
                        u[0], u[1], u[2], u[3], u[4]);
            }
        }
        // 需求补齐批次：公共数据集 / 驱动版本矩阵 / 演示代金券（幂等补齐）
        String[][] datasetSeeds = {
                {"ds1", "ImageNet-1K", "图像分类基准数据集（140 万标注图，143GB）", "143", "/datasets/imagenet", "CV"},
                {"ds2", "COCO 2017", "目标检测与分割基准（25GB）", "25", "/datasets/coco", "CV"},
                {"ds3", "MNIST", "手写数字识别入门集（12MB）", "0.1", "/datasets/mnist", "CV"},
                {"ds4", "CIFAR-10", "10 类小图分类集（163MB）", "0.2", "/datasets/cifar10", "CV"},
                {"ds5", "OpenWebText", "大模型预训练语料（38GB）", "38", "/datasets/openwebtext", "NLP"},
        };
        for (String[] d : datasetSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM datasets WHERE id=?", Integer.class, d[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO datasets(id, name, description, size_gb, path, category) VALUES (?,?,?,?,?,?)",
                        d[0], d[1], d[2], new BigDecimal(d[3]), d[4], d[5]);
            }
        }
        String[][] matrixSeeds = {
                {"dm1", "RTX 5090", "570.86.16", "CUDA 12.8", "主力卡 · 32GB GDDR7"},
                {"dm2", "RTX PRO 5500 Blackwell", "580.x", "CUDA 13.0", "主力卡 · 84GB GDDR7 ECC · 支持 MIG"},
                {"dm3", "RTX PRO 6000D", "580.x", "CUDA 13.0", "主力卡 · 84GB GDDR7 ECC · 中国大陆合规特供"},
                {"dm4", "RTX 4060 Laptop", "566.26", "CUDA 12.7", "本机实采演示卡（Windows host agent）"},
        };
        for (String[] d : matrixSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM driver_matrix WHERE id=?", Integer.class, d[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO driver_matrix(id, model, driver_version, cuda_version, note) VALUES (?,?,?,?,?)",
                        d[0], d[1], d[2], d[3], d[4]);
            }
        }
        String[][] couponSeeds = {
                {"cp1", "t1", "新人注册代金券", "5.00", "unused", "2026-12-31 23:59:59", "2026-11-04 00:00:00"},
                {"cp2", "t1", "邀请好友奖励券", "3.00", "unused", "2026-12-31 23:59:59", "2026-11-04 00:00:00"},
        };
        for (String[] cp : couponSeeds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM coupons WHERE id=?", Integer.class, cp[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO coupons(id, tenant_id, title, amount_yuan, status, expire_at, created_at) VALUES (?,?,?,?,?,?,?)",
                        cp[0], cp[1], cp[2], new BigDecimal(cp[3]), cp[4],
                        java.sql.Timestamp.valueOf(cp[5]), java.sql.Timestamp.valueOf(cp[6]));
            }
        }
    }

    private boolean tableExists(Connection c) {
        try (Statement s = c.createStatement()) {
            s.execute("SELECT 1 FROM users WHERE 1=0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private Integer count(String table) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 生成一个库内唯一的邀请码（去掉易混淆的 0/O/1/I），控制台直接展示 */
    private String freshInviteCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
            }
            String code = sb.toString();
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE invite_code=?", Integer.class, code);
            if (n == null || n == 0) {
                return code;
            }
        }
        return "INV" + System.currentTimeMillis() % 100000;
    }
}
