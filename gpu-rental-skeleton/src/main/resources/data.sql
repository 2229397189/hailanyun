-- ============================================================
-- 演示种子数据（仅空库首次启动时写入，见 DbBootstrap；老库由 ensureUpgrades 幂等补齐）
-- 演示账号统一初始密码：Hly@2026（SeedUsers 启动时写入 BCrypt 哈希，不含明文密码）
-- ============================================================

INSERT INTO pools (id, name, policy, description) VALUES
  ('p1', '通用租赁池', 'binpack', '对外售卖的显卡，优先填满单节点，保留整机可售'),
  ('p2', '备用资源池', 'spread', '备用节点池，当前无节点，扩容时接入');

INSERT INTO queues (id, pool_id, name, gpu_quota, weight, priority) VALUES
  ('q1', 'p1', 'default-queue', 14, 10, 10),
  ('q2', 'p2', 'platform-queue', 2, 1, 100);

INSERT INTO nodes (id, hostname, ip, status, role, cpu_cores, mem_gb, disk_gb, k8s_version, pod_count, gpu_count, pool_id, labels, taints) VALUES
  ('n1', 'gpu-node-1', '10.0.0.11', 'Ready', 'server', 64, 512, 3800, 'v1.34.1+k3s1', 24, 8, 'p1',
   'gpu.model=rtx5090,node.role=server,zone=rack-a', ''),
  ('n2', 'gpu-node-2', '10.0.0.12', 'Ready', 'agent', 64, 512, 3800, 'v1.34.1+k3s1', 18, 8, 'p1',
   'gpu.model=rtx5090,node.role=agent,zone=rack-a', '');

INSERT INTO gpus (id, node_id, idx, model, mem_gb, status, util_pct, temp_c, power_w) VALUES
  ('g1-1','n1',1,'RTX 5090',32,'free',0,44,38),
  ('g1-2','n1',2,'RTX 5090',32,'free',0,43,36),
  ('g1-3','n1',3,'RTX 5090',32,'free',0,82,41),
  ('g1-4','n1',4,'RTX 5090',32,'free',0,45,39),
  ('g1-5','n1',5,'RTX 5090',32,'free',0,44,37),
  ('g1-6','n1',6,'RTX 5090',32,'free',0,46,40),
  ('g1-7','n1',7,'RTX 5090',32,'free',0,45,38),
  ('g1-8','n1',8,'RTX 5090',32,'reserved',12,43,52),
  ('g2-1','n2',1,'RTX 5090',32,'free',0,43,35),
  ('g2-2','n2',2,'RTX 5090',32,'free',0,44,36),
  ('g2-3','n2',3,'RTX 5090',32,'free',0,44,37),
  ('g2-4','n2',4,'RTX 5090',32,'free',0,45,38),
  ('g2-5','n2',5,'RTX 5090',32,'free',0,43,35),
  ('g2-6','n2',6,'RTX 5090',32,'free',0,46,40),
  ('g2-7','n2',7,'RTX 5090',32,'free',0,44,36),
  ('g2-8','n2',8,'RTX 5090',32,'free',0,45,37);

INSERT INTO users (id, username, display_name, role, tenant_id, status) VALUES
  ('u1', 'admin', '平台管理员', 'admin', NULL, 'active'),
  ('u2', 'ops', '值班运维', 'ops', NULL, 'active'),
  ('u3', 'tenant_a', '星辰智能科技 · 管理员', 'tenant', 't1', 'active'),
  ('u4', 'tenant_b', '凌云机器人 · 管理员', 'tenant', 't2', 'active'),
  ('u6', 'tenant_c', '幻核互动娱乐 · 管理员', 'tenant', 't4', 'active'),
  ('u7', 'tenant_d', '极光量化科技 · 管理员', 'tenant', 't5', 'active'),
  ('u8', 'tenant_e', '蓝湾医疗影像 · 管理员', 'tenant', 't6', 'active'),
  ('u5', 'guest', '已停用示例账号', 'tenant', 't3', 'disabled');

INSERT INTO tenants (id, name, quota_gpus, balance_yuan, status, owner, created_at) VALUES
  ('t1', '星辰智能科技', 8, 5000.00, 'active', 'tenant_a', '2026-10-20 09:00:00'),
  ('t2', '凌云机器人', 4, 500.00, 'active', 'tenant_b', '2026-10-22 14:20:00'),
  ('t3', '海蓝云自营', 2, 0.00, 'frozen', 'ops', '2026-10-25 10:00:00'),
  ('t4', '幻核互动娱乐', 4, 2000.00, 'active', 'tenant_c', '2026-10-26 11:00:00'),
  ('t5', '极光量化科技', 2, 1200.00, 'active', 'tenant_d', '2026-10-28 15:30:00'),
  ('t6', '蓝湾医疗影像', 2, 800.00, 'active', 'tenant_e', '2026-10-30 09:40:00');

INSERT INTO alerts (id, level, title, source, target, status, detail, created_at) VALUES
  ('a1', 'P1', '显卡温度持续偏高', 'gpu', 'g1-3', 'active', '温度 82°C，已持续 12 分钟，建议检查风道或降频', '2026-11-02 18:40:00'),
  ('a2', 'P2', '实例将在 2 小时内到期', 'tenant', 't1', 'acked', '实例到期时间 20:30，已通知租户', '2026-11-02 18:32:00'),
  ('a3', 'P0', '节点心跳丢失', 'node', 'n2', 'resolved', 'kubelet 30 秒未上报，已在 18:05 自动恢复', '2026-11-02 18:04:00'),
  ('a4', 'P2', '租户余额低于预警线', 'tenant', 't2', 'active', '余额 500 元，按当前用量约 3 天耗尽', '2026-11-02 17:50:00');

INSERT INTO inspections (id, run_at, health_score, total_issues, summary) VALUES
  ('ins1', '2026-11-02 18:50:00', 86, 3, '整体健康，2 项待处理：显卡温度、租户余额预警'),
  ('ins2', '2026-11-02 12:00:00', 92, 1, '集群稳定，仅 1 项磁盘使用率提醒');

INSERT INTO inspection_items (inspection_id, level, item, target, suggestion, fixed) VALUES
  ('ins1', 'P1', 'GPU 温度超过 80°C', 'g1-3', '检查机箱风道，或将任务迁移到 g1-4', FALSE),
  ('ins1', 'P2', '租户余额低于预警线', 't2', '提醒租户充值，或下调配额', FALSE),
  ('ins1', 'P2', '镜像仓库磁盘使用率 71%', 'n1', '清理 30 天前的构建缓存', TRUE),
  ('ins2', 'P2', '镜像仓库磁盘使用率 68%', 'n1', '计划本周清理', FALSE);

INSERT INTO orders (id, order_no, tenant_id, sku, gpu_count, hours, unit_price, amount, status, created_at) VALUES
  ('o-h1', 'ORD-20261030-0001', 't1', 'whole', 8, 24, 2.80, 537.60, 'closed', '2026-10-30 10:00:00'),
  ('o-h2', 'ORD-20261031-0002', 't1', 'single', 1, 8, 2.80, 22.40, 'closed', '2026-10-31 09:12:00'),
  ('o-h3', 'ORD-20261031-0003', 't2', 'single', 2, 4, 2.80, 22.40, 'closed', '2026-10-31 15:30:00');

INSERT INTO instances (id, order_id, tenant_id, node_id, gpu_ids, gpu_count, pod_name, status, created_at, started_at, expire_at, used_seconds, released_at) VALUES
  ('i-h2', 'o-h2', 't1', 'n1', 'g1-2', 1, 'gpu-pod-i-h2', 'released',
   '2026-10-31 09:12:00', '2026-10-31 09:12:40', '2026-10-31 17:12:00', 14400, '2026-10-31 13:12:00'),
  ('i-h3', 'o-h3', 't2', 'n1', 'g1-4,g1-5', 2, 'gpu-pod-i-h3', 'released',
   '2026-10-31 15:30:00', '2026-10-31 15:30:35', '2026-10-31 19:30:00', 9600, '2026-10-31 18:10:00');

INSERT INTO billing_records (instance_id, tenant_id, gpu_count, seconds, unit_price, amount, settled_at) VALUES
  ('i-h2', 't1', 1, 14400, 2.80, 11.20, '2026-10-31 13:12:00'),
  ('i-h3', 't2', 2, 9600, 2.80, 14.93, CURRENT_TIMESTAMP);

INSERT INTO workloads (id, name, kind, namespace, image, gpu_count, mem_limit, policy, status, tenant_id, created_at) VALUES
  ('w1', 'qwen-infer', 'Deployment', 'tenant-t1', 'registry.local/ai/vllm:0.6.3', 1, 'whole', 'binpack', 'Running', 't1', '2026-11-02 09:10:00'),
  ('w2', 'llama-finetune', 'Job', 'tenant-t1', 'registry.local/ai/pytorch:2.4-cu124', 4, 'whole', 'gang', 'Running', 't1', '2026-11-02 10:25:00'),
  ('w3', 'embedding-svc', 'Deployment', 'tenant-t2', 'registry.local/ai/tei:1.5', 1, 'half', 'spread', 'Running', 't2', '2026-11-02 11:40:00');

INSERT INTO alert_rules (id, name, target, expr, level, notify, enabled) VALUES
  ('r1', '显卡掉卡 / XID 错误', 'gpu', 'DCGM_FI_DEV_XID_ERRORS > 0', 'P0', '电话 + 群', TRUE),
  ('r2', '节点心跳丢失', 'node', 'node_status != Ready 持续 60s', 'P0', '电话 + 群', TRUE),
  ('r3', '显卡温度过高', 'gpu', 'temp_c > 80 持续 5min', 'P1', '群', TRUE),
  ('r4', '显存接近打满', 'gpu', 'mem_used / mem_total > 95%', 'P1', '群', TRUE),
  ('r5', '实例回收卡住', 'platform', 'status = releasing 持续 10min', 'P1', '群', TRUE),
  ('r6', '租户余额预警', 'tenant', 'balance_yuan < 1000', 'P2', '邮件', TRUE);

INSERT INTO settings (k, v, note) VALUES
  ('platform.name', 'TokenArk 创元方舟 · 算力运营平台', '平台显示名称'),
  ('platform.currency', 'CNY', '计费币种'),
  ('rental.unit_price', '2.80', '单价（元 / 卡 / 小时）'),
  ('rental.prepaid', 'true', '下单时是否全额预扣'),
  ('cluster.name', 'prod-cluster', '当前纳管集群'),
  ('cluster.k8s_version', 'v1.34.1+k3s1', '集群版本'),
  ('scheduler.name', 'volcano', '调度器'),
  ('scheduler.preempt_tenant', 'false', '是否允许抢占租户实例（对外租赁必须为 false）'),
  ('plan.discount.h24', '0.95', '日付套餐折扣（24 小时）'),
  ('plan.discount.h168', '0.90', '周付套餐折扣（168 小时）'),
  ('plan.discount.h720', '0.85', '月付套餐折扣（720 小时）'),
  ('brand.name', 'TokenArk 创元方舟', '白标：平台名称'),
  ('brand.slogan', 'GPU 算力即服务', '白标：Slogan'),
  ('brand.logo_text', 'TokenArk', '白标：Logo 文案'),
  ('brand.primary_color', '#7c3aed', '白标：品牌主色'),
  ('brand.copyright', '© 2026 TokenArk 创元方舟 · 保留所有权利', '白标：页脚版权'),
  ('brand.icp', '', '白标：ICP 备案号（可为空）');

INSERT INTO dict_items (id, category, code, label, sort, enabled) VALUES
  ('d-alert-p0', 'alert_level', 'P0', '严重（P0）', 1, TRUE),
  ('d-alert-p1', 'alert_level', 'P1', '警告（P1）', 2, TRUE),
  ('d-alert-p2', 'alert_level', 'P2', '提示（P2）', 3, TRUE),
  ('d-inst-provisioning', 'instance_status', 'provisioning', '创建中', 1, TRUE),
  ('d-inst-running', 'instance_status', 'running', '运行中', 2, TRUE),
  ('d-inst-releasing', 'instance_status', 'releasing', '回收中', 3, TRUE),
  ('d-inst-released', 'instance_status', 'released', '已释放', 4, TRUE),
  ('d-inst-failed', 'instance_status', 'failed', '失败', 5, TRUE),
  ('d-node-master', 'node_role', 'master', '控制节点', 1, TRUE),
  ('d-node-agent', 'node_role', 'agent', '计算节点', 2, TRUE);

INSERT INTO audit_logs (actor, action, target, detail, created_at) VALUES
  ('admin', 'NODE_CORDON', 'gpu-node-2', '维护窗口前手动封禁调度', '2026-11-02 16:20:00'),
  ('ops', 'ALERT_RESOLVE', 'a3', '节点心跳恢复，告警自动关闭', '2026-11-02 18:05:00'),
  ('admin', 'TENANT_QUOTA', 't1', '配额由 4 卡调整为 8 卡', '2026-11-02 19:10:00');
