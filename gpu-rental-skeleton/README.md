# TokenArk 创元方舟 · 算力运营平台（POC）

2 台 × 8 卡 RTX 5090 的算力租赁平台演示：**用户端 + 管理端 + 监控大屏**三套界面，全部接真实后端，数据来自 PostgreSQL。

## 三个入口

| 地址 | 说明 |
|---|---|
| `https://localhost/` | **正式入口**（nginx 终止 TLS，HTTP 自动跳 HTTPS） |
| `https://localhost/login.html` | **登录页**：账号 + 密码（BCrypt 校验，5 次失败锁 10 分钟） |
| `https://localhost/portal.html` | **用户端**：算力市场、我的实例、我的账单 |
| `https://localhost/admin.html` | **管理端**：集群总览、节点、工作负载、资源对象、资源池、租户、告警、巡检、系统管理 |
| `https://localhost/screen.html` | **监控大屏**：卡池热力、利用率、告警、健康度（投屏用，新窗口打开） |
| `http://localhost:3000` | **Grafana**（admin / TokenArk@2026）：JVM、QPS、主机 CPU/内存、业务指标 |
| `http://localhost:9090` | **Prometheus**：指标查询与抓取目标 |
| `http://localhost:5000/v2/_catalog` | **私有镜像仓库**（registry:2，离线交付用） |
| `http://localhost:8099` | 应用直连（调试用，生产应关闭外网暴露） |

## 部署拓扑（7 个容器）

```
                      ┌──────────────┐
   浏览器 ──443──▶    │  nginx:1.27  │  TLS 终止 + 安全响应头
                      └──────┬───────┘
                             │ 8099
                      ┌──────▼───────┐        ┌──────────────┐
                      │  app:8099    │◀──抓取─│ prometheus   │──▶ grafana:3000
                      │ Spring Boot  │ metrics└──────────────┘
                      └──┬────────┬──┘        ┌──────────────┐
                         │        │           │ node-exporter│
                  ┌──────▼──┐  ┌──▼────────┐  └──────────────┘
                  │ postgres│  │ k3s (外部)│
                  └────┬────┘  └───────────┘
                       │ 每日 pg_dump
                  ┌────▼─────┐   ┌──────────────┐
                  │  backup  │   │ registry:2   │
                  └──────────┘   └──────────────┘
```

## K8s 部署（客户环境形态）

除 docker compose（开发/演示用）外，**平台自身也可以作为 K8s 工作负载部署**，这是客户机房的交付形态。

```bash
kubectl apply -f deploy/k8s/tokenark.yaml
```

清单包含：

| 对象 | 作用 |
|---|---|
| `Namespace tokenark` | 平台自身所在的命名空间 |
| `Deployment postgres` + `PVC` | 数据库（local-path 存储类） |
| `Deployment tokenark-app` | 平台应用 |
| `ServiceAccount tokenark-app` | 平台身份 |
| `ClusterRole/Binding` | 建命名空间、建删 Pod、管 ResourceQuota、读节点 |
| `Service tokenark-app`（NodePort 30080） | 对外暴露 |

**平台跑在集群内时自动切换认证方式**：检测到 `/var/run/secrets/kubernetes.io/serviceaccount/token` 就用 in-cluster 配置，否则读 kubeconfig 文件——所以同一份镜像，集群内外都能跑。

实测（2026-11-03）：

```
$ kubectl get pods -n tokenark
postgres-xxx       1/1  Running
tokenark-app-xxx   1/1  Running

$ kubectl logs -n tokenark deploy/tokenark-app | grep in-cluster
检测到集群内 ServiceAccount，使用 in-cluster 配置
RealK8s 已连接 https://10.43.0.1:443/（v1.31.4+k3s1）

$ kubectl get pods -n tenant-t1
gpu-pod-ia12cfd11ab   1/1  Running     # 由集群内的平台创建
```

即：**平台在 K8s 里跑，它管理的租户实例也在 K8s 里跑**，两层都在集群内。

> 镜像需先导入集群：`docker save gpu-rental-skeleton-app:latest | docker exec -i <k3s容器> ctr -n k8s.io images import -`

## 证书

`deploy/nginx/certs/` 下是自签证书（CN=localhost，SAN 含 127.0.0.1，有效期 825 天）。
生产替换为正式证书后 `docker compose restart nginx` 即可，无需改配置。

```bash
# 换正式证书
cp your.crt deploy/nginx/certs/server.crt
cp your.key deploy/nginx/certs/server.key
docker compose restart nginx
```

## 备份与恢复

`backup` 容器每 24 小时执行一次 `pg_dump -Fc`，保留 7 天，文件在 `backupdata` 卷内。

```bash
# 查看备份
docker exec gpu-rental-backup ls -lh /backup

# 恢复（会覆盖现有数据，先在测试库验证）
docker exec gpu-rental-backup pg_restore -h postgres -U rental -d rental \
  --clean --if-exists /backup/rental-20261103-1135.dump
```

## 监控

Prometheus 抓三类指标：

| 来源 | 指标 |
|---|---|
| 应用 `/actuator/prometheus` | JVM 堆、GC、HTTP 请求数与耗时、连接池 |
| 应用自定义 Gauge | 运行实例数、下发中实例、空闲/占用/故障卡数、服务中租户 |
| node-exporter | 主机 CPU、内存、磁盘、网络 |

Grafana 仪表盘「TokenArk · 算力平台运行态」随容器启动自动加载，无需手工导入。

### 角色与可见范围

| 角色 | 能进的页面 | 侧栏入口 |
|---|---|---|
| `admin` 平台管理员 | 管理端 + 大屏 | 全部 10 项（含系统管理、监控大屏） |
| `ops` 值班运维 | 管理端 + 大屏 | 9 项（**无系统管理**） |
| `tenant` 租户用户 | 仅用户端 | 3 项（算力市场 / 我的实例 / 我的账单） |

> 租户用户**没有**管理端与监控大屏入口；直接输 URL 访问 `/admin.html`、`/screen.html` 会被送回用户端。
> 管理员访问 `/portal.html` 同样会被送回管理端。前端收敛入口，后端 `AuthInterceptor` 再做接口级拦截，两层都拦。

> 用户端与管理端都做了会话守卫：未登录会跳回 `/login.html`（登录态存 `localStorage.gu_token`）。

## 演示账号

| 账号 | 密码 | 角色 | 能做什么 |
|---|---|---|---|
| `admin` | `Hly@2026` | 平台管理员 | 全部功能，含系统管理（账号 / 会话 / 参数） |
| `ops` | `Hly@2026` | 值班运维 | 集群与告警相关，**无系统管理** |
| `tenant_a` | `Hly@2026` | 租户用户 | 只能进用户端，只能看自己租户 |
| `tenant_b` | `Hly@2026` | 租户用户 | 同上（租户 B） |
| `guest` | — | 已停用 | 登录被拒，用于演示停用状态 |

> 连续 5 次密码错误锁定 10 分钟；管理员可在「系统管理 → 平台账号」解锁。
> 初始密码由 `SeedUsers` 启动时写入 BCrypt 哈希，库里没有明文。

## 认证与权限设计

| 能力 | 实现 |
|---|---|
| 密码存储 | BCrypt（`spring-security-crypto`），库被拖走也拿不到明文 |
| 会话 | 落 `sessions` 表，Bearer token；支持服务端强制下线、在线会话列表 |
| 会话时长 | 默认 8 小时，「7 天内免登录」勾选后 7 天 |
| 防暴力破解 | 5 次失败锁定 10 分钟，失败次数与锁定时间落库 |
| 接口鉴权 | `AuthInterceptor` 拦截 `/api/**`，仅放行 `/api/auth/login` |
| 防身份伪造 | 请求头 `X-User` 必须与会话用户一致，否则 403 |
| 接口级 RBAC | `/api/admin/**` 仅 admin/ops；`/api/admin/settings\|users\|sessions` 仅 admin |
| 账号停用 | 停用后已签发会话立即失效（每次校验都查用户状态） |
| 审计 | 登录成功/失败/锁定、改密、停用、强制下线全部写 `audit_logs` |
| 密码策略 | 至少 8 位，含大写、小写、数字、符号；改密后踢掉其它会话 |
| 会话上限 | 同一账号最多 5 个活跃会话，超出按最后活动时间踢最旧 |

## 运维与治理

| 能力 | 说明 |
|---|---|
| **数据持久化** | 建表用 `CREATE TABLE IF NOT EXISTS`，种子只在空库时写；**重启不清库**（`DbBootstrap` 判断，日志会打印「已有数据，跳过种子」） |
| **健康检查** | `GET /api/health` 免登录，返回 `status/db/k8sMode/uptime/version`；容器 `healthcheck` 用它，`docker ps` 显示 `(healthy)` |
| **自愈** | 两个容器都配 `restart: unless-stopped`，宿主机重启后自动拉起 |
| **资源对账** | `GET /api/admin/orphans` 比对集群 Pod 与数据库实例，找出「实例已释放但 Pod 残留」的孤儿资源，可一键清理（写审计） |
| **审计筛选** | 关键字（操作人/动作/对象/详情）+ 动作下拉 + 分页，服务端过滤 |
| **审计导出** | 导出当前筛选结果为 CSV，带 BOM，Excel 直接打开不乱码 |
| **在线会话** | 列出所有活跃会话（用户/IP/登录时间/最后活动），支持强制下线 |
| **账号治理** | 停用/启用、解锁（清零失败次数），停用后会话立即失效 |

### 实测（2026-11-03）

```
重启前：gpuTotal=16  订单=3  审计=9
docker restart gpu-rental-app
重启后：gpuTotal=16  订单=3  审计=10（多了一条本次登录）
日志：已有数据，跳过种子（users=5，tenants=3）

资源对账：发现 4 个孤儿 Pod → 逐个清理 → total=0，集群 tenant-t1 已空
审计搜索 ORPHAN → 4 条；按动作筛选 LOGIN_SUCCESS → 12 条；CSV 导出正常
容器状态：gpu-rental-app Up (healthy) / gpu-rental-pg Up (healthy)
```

## 启动

### 方式一：Docker（推荐，带 PostgreSQL）

```bash
mvn -DskipTests package            # 先打 jar
docker compose up -d --build       # 起 postgres + app
```

打开 <http://localhost:8099>

- 应用容器：`gpu-rental-app`，端口 8099
- 数据库：`gpu-rental-pg`（PostgreSQL 16），端口映射 5433
- 首次启动自动建表并灌入演示数据
- ⚠️ **每次重启会重建表并重置演示数据**（`spring.sql.init.mode=always`）。要保留数据，把它改成 `never`。

```bash
docker compose logs -f app     # 看日志
docker compose down            # 停止（加 -v 会删除数据库卷）
```

### 方式二：本地 JRE（H2 内存库，无需 Docker）

```bash
java -jar target/gpu-rental-0.1.0.jar
```

## 功能清单

### 用户端
- **算力市场**：两种规格（单卡 / 整机 8 卡），实时库存、按小时选时长、预扣金额随选择变化
- **下单**：幂等单号，重复提交不产生第二个实例；余额/配额不足直接拦截
- **我的实例**：状态、已用时长、到期时间、连接命令、释放结算
- **我的账单**：已结算 / 进行中估算 / 明细分条

### 管理端
- **集群总览**：顶部**控制面组件健康条**（apiserver/etcd/scheduler/controller/coredns/metrics-server/gpu-operator/dcgm-exporter/volcano 共 9 个）、6 个 KPI、利用率折线、卡池环形图、节点健康、活跃告警、租户用量、资源池、**K8s 原生五层架构图**
- **节点管理**：节点卡片 + 8 格显卡视图、标签、Cordon / Drain / 恢复调度、点卡标记故障或修复、**YAML 抽屉**（标准 Node 对象 + device plugin 上报字段）
- **工作负载**：
  - **部署向导**：名称 / 类型（Deployment / StatefulSet / Job）/ 租户 / 命名空间 / 镜像 / GPU 数 / 显存上限（整卡 / 1/2 / 1/4）/ 调度策略（Bin-pack / Spread / Gang），提交后 3 秒 Pending → Running
  - **工作负载对象表**：可删除回收
  - **算力实例表**：租户下单产生，含节点、Pod、用量、强制释放
  - **命名空间与配额**：系统空间 + 每个租户的 ResourceQuota 与已用量
- **资源池与调度**：池容量、**机架热力视图**（每卡温度）、队列配额、**资源池新建 / 改策略 / 删除**（池下有节点时拒绝删除）、调度策略说明
- **资源对象（YAML）**：**在线编辑并应用** Deployment / StatefulSet / Job / Service / Namespace / ConfigMap；后端校验 apiVersion、kind、metadata.name、namespace；同 kind+ns+name 再次应用视为更新；支持查看与删除
- **系统管理**：集群信息（版本 / 运行时 / CNI / 节点数）、平台账号与 RBAC 角色、**系统参数在线编辑**（单价、预扣开关、抢占开关等）、**操作审计**（所有写操作留痕）
- **租户管理**：新建租户、调配额、冻结解冻、余额与消费
- **监控告警**：P0/P1/P2 分级、确认 / 解决、利用率与功耗曲线、**告警规则 CRUD**（新建 / 停用 / 删除，带通知渠道）
- **智能巡检**：一键巡检，**按当前集群真实状态**生成问题项与健康分（不是写死的假数据）

### 监控大屏
卡池热力（每卡温度）、总利用率、运行实例、今日收入、告警滚动、健康度仪表盘、功耗曲线、租户用量 Top，8 秒自动刷新。

## 六个核心设计

| 设计 | 位置 | 说明 |
|---|---|---|
| 下单原子性 | `RentalService.createOrder` | 占卡 + 建订单 + 建实例 + 扣余额，同一事务 |
| 幂等 | `orders.order_no` 唯一约束 | 同一单号重复提交返回原订单 |
| 并发安全占卡 | `Db.occupy` | `UPDATE ... WHERE status='free'`，条件更新，不会一卡两卖 |
| 整机同节点 | `Db.pickWholeNode` | `GROUP BY node_id HAVING COUNT(*) >= 8` |
| bin-packing | `Db.pickSingleGpuIds` | 单卡优先塞进已占用节点，留整台空机给整机订单 |
| 回收兜底 | `RentalService.release` + `Reconciler` | **先删 K8s 资源，成功后才改 DB**；卡住则置 `releasing` 并重试 |
| 计费 | `RentalService.amountOf` | 预扣全时长，按秒结算，差额退回 |

## 接真实 K8s 只改一个类

`K8sClient` 是接口，两种实现按 `rental.k8s.mode` 切换：

| 模式 | 实现 | 说明 |
|---|---|---|
| `fake`（默认） | `FakeK8s` | 模拟 Pod 生命周期（3 秒就绪，可模拟 Terminating 卡住），无需集群 |
| `real` | `RealK8s` | 用 fabric8 连真 apiserver，创建/查询/删除真 Pod |

```bash
# 用真集群启动（需要 ../k8s-demo/kubeconfig）
docker compose up -d --build        # docker-compose 里已设 RENTAL_K8S_MODE=real
curl http://localhost:8099/api/runtime
# {"k8sMode":"real","real":true}
```

业务层、计费、回收逻辑一行不用改。Pod 模板关键字段：

```yaml
resources:
  limits:
    nvidia.com/gpu: 1     # 整机就是 8
schedulerName: volcano    # 要走 Volcano 队列/配额/gang，必须写
```

> 实测（2026-11-02）：平台下单 → 集群里出现 `gpu-pod-xxx`，请求 `nvidia.com/gpu: 1`，
> 10 秒内 Running，平台侧实例状态自动变 `running`；释放后 Pod 消失、命名空间保留。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/overview` | 卡池总览 |
| GET | `/api/pool` | 节点与每张卡 |
| GET | `/api/skus` | 售卖规格与实时库存 |
| GET | `/api/tenants` | 租户列表 |
| POST | `/api/orders` | 下单 `{tenantId, sku, hours, orderNo}` |
| GET | `/api/instances?tenantId=` | 我的实例 |
| POST | `/api/instances/{id}/release` | 释放并结算 |
| GET | `/api/billing?tenantId=` | 账单 |
| GET | `/api/admin/overview` | 管理端总览 |
| GET | `/api/admin/nodes` | 节点（含显卡） |
| POST | `/api/admin/nodes/{id}/cordon\|drain\|uncordon` | 节点调度控制 |
| GET | `/api/admin/workloads` | 工作负载 |
| GET | `/api/admin/pools` | 资源池与队列 |
| GET/POST | `/api/admin/tenants` | 租户查询 / 新建 |
| POST | `/api/admin/tenants/{id}/quota\|status` | 调配额 / 冻结 |
| GET | `/api/admin/alerts` | 告警列表 |
| POST | `/api/admin/alerts/{id}/ack\|resolve` | 确认 / 解决 |
| GET | `/api/admin/inspections` | 巡检记录 |
| POST | `/api/admin/inspections/run` | 立即巡检 |
| GET | `/api/admin/metrics?minutes=30` | 时序数据 |
| POST | `/api/admin/gpus/{id}/fault\|repair` | 摘卡 / 修复 |
| POST | `/api/admin/instances/{id}/stuck\|unstick` | 模拟 / 解除 Pod 卡住 |
| GET/POST | `/api/admin/resources` | 资源对象列表 / 应用 YAML |
| GET/DELETE | `/api/admin/resources/{id}` | 查看 / 删除资源 |
| GET/POST | `/api/admin/alert-rules` | 告警规则列表 / 新建 |
| POST/DELETE | `/api/admin/alert-rules/{id}/toggle`、`/{id}` | 启停 / 删除规则 |
| GET/POST | `/api/admin/settings` | 系统参数（含集群信息、账号） |
| GET | `/api/admin/audit` | 操作审计 |
| POST/DELETE | `/api/admin/nodes`、`/api/admin/nodes/{id}` | 接入 / 下线节点（下线前检查运行实例） |
| POST | `/api/admin/nodes/{id}/pool` | 节点划入资源池 |
| POST/DELETE | `/api/admin/pools`、`/api/admin/pools/{id}` | 新建 / 删除资源池 |
| POST | `/api/admin/pools/{id}/policy` | 修改池调度策略 |
| GET/POST | `/api/admin/deployments` | 工作负载对象列表 / 部署向导 |
| POST | `/api/admin/deployments/{id}/restart\|stop` | 重启 / 停止 |
| GET | `/api/admin/deployments/{id}/logs` | Pod 日志（确定性生成，可重复查看） |
| GET | `/api/admin/instances/{id}/access` | SSH / Jupyter 接入信息与公钥 |
| POST | `/api/admin/instances/{id}/reset-password` | 重置实例 root 密码 |
| GET | `/api/admin/components` | 控制面 9 个组件健康 |
| GET | `/api/admin/namespaces` | 命名空间与 ResourceQuota |

## 本地验证过的流程

1. 下单 → 3 秒就绪；同单号重复提交只出一个实例
2. 配额不足、余额不足被拦截
3. 整机 8 卡必须落在同一节点
4. 释放按秒结算、差额退回
5. Pod 卡在 Terminating 时释放失败、实例停在「回收中」、**卡不释放也不重复计费**
6. 解除卡住后巡检 5 秒内补删并结算
7. 故障卡从可售池摘除，修复后回池

## 已知限制

- `FakeK8s` 是模拟运行时，没有接真集群（本机无 GPU）
- 监控指标是按真实状态生成的模拟曲线，非 DCGM 采集
- 无登录鉴权（演示用租户切换代替）
- 无在线支付，账单为预扣 + 结算模型
- 巡检只读，不自动修复
