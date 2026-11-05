# TokenArk 创元方舟 · 算力运营平台（POC）

2 台 × 8 卡 RTX 5090 的算力租赁平台演示：**用户端 + 管理端 + 监控大屏**三套界面，全部接真实后端，数据来自 PostgreSQL。

## 三个入口

| 地址 | 说明 |
|---|---|
| `https://localhost/` | **正式入口**（nginx 终止 TLS，HTTP 自动跳 HTTPS） |
| `https://localhost/login` | **登录页**：账号 + 密码（BCrypt 校验，5 次失败锁 10 分钟） |
| `https://localhost/portal` | **用户端**：算力市场、我的实例、我的账单 |
| `https://localhost/admin` | **管理端**：集群总览、节点、工作负载、资源对象、资源池、租户、告警、巡检、系统管理 |
| `https://localhost/screen` | **监控大屏**：卡池热力、利用率、告警、健康度（投屏用，新窗口打开） |
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

除 docker compose（开发/演示用）外，**平台自身也可以作为 K8s 工作负载部署**，这是客户机房的交付形态。两种方式二选一：

```bash
# 方式一：Helm（推荐，参数化）
helm install tokenark deploy/helm/tokenark -n tokenark --create-namespace
# 真实集群模式：values.yaml 里 app.k8sMode=real（默认），fake 演示则改成 fake

# 方式二：裸清单
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

### 本机 GPU 实采 agent（真实数据）

平台监控曲线按三级真实度取数：**本机 agent 实采 → Prometheus/DCGM → 演示合成**，每级都在页面上标注来源，绝不把合成数据冒充真实。

任一台带 NVIDIA 显卡的机器（Windows/Linux 均可）跑一个 Python 脚本即可上报真实 GPU 数据：

```bash
# Windows 本机（依赖 nvidia-smi，仅标准库，无需 pip install）
python deploy/agent/win-agent.py [平台地址] [agent令牌]

# 例：python deploy/agent/win-agent.py http://localhost:8099 hly-local-agent-2026
```

- 每 10 秒采集：GPU 利用率 / 显存 / 温度 / 功耗 / CPU / 内存，POST 到 `/api/agent/report`（`X-Agent-Token` 鉴权，令牌在 系统管理→系统参数 `agent.token`，演示默认 `hly-local-agent-2026`，可改）
- 数据保留 48 小时；管理端节点管理会出现一张「本机实采」只读节点卡，监控页曲线标签变为「本机真实 GPU 实采（nvidia-smi agent）」
- 没跑 agent 时回退到 Prometheus/DCGM；DCGM 也没数据则显示诚实空态（标注原因），仍无数据才用标注「合成」的演示曲线

### 命令行客户端 ark-cli（纯标准库，无需安装）

给运维脚本化留口子，与 Web 端同一套后端：

```bash
python deploy/cli/ark-cli.py login           # 交互式登录；或 ARK_USER/ARK_PASSWORD 环境变量
python deploy/cli/ark-cli.py whoami          # 当前身份与租户
python deploy/cli/ark-cli.py plans           # 套餐（含闲时 / 无卡调试）
python deploy/cli/ark-cli.py skus            # SKU 与单价
python deploy/cli/ark-cli.py balance         # 余额与代金券
python deploy/cli/ark-cli.py instances       # 实例列表
python deploy/cli/ark-cli.py poweroff <实例ID>   # 关机（7 天保留期）
python deploy/cli/ark-cli.py poweron  <实例ID>   # 开机
python deploy/cli/ark-cli.py restart  <实例ID>   # 重启
python deploy/cli/ark-cli.py renew    <实例ID> <小时数>   # 续费
python deploy/cli/ark-cli.py release  <实例ID>   # 释放：输出使用秒数与退款金额
```

Token 缓存在 `~/.ark-cli.json`；环境变量 `ARK_URL`（默认 `http://localhost:8099`）、`ARK_TOKEN`（跳过登录直接用 token）、`ARK_USER` / `ARK_PASSWORD`（CI 非交互登录）。

### 角色与可见范围

| 角色 | 能进的页面 | 侧栏入口 |
|---|---|---|
| `admin` 平台管理员 | 管理端 + 大屏 | 全部 10 项（含系统管理、监控大屏） |
| `ops` 值班运维 | 管理端 + 大屏 | 9 项（**无系统管理**） |
| `tenant` 租户用户 | 仅用户端 | 3 项（算力市场 / 我的实例 / 我的账单） |

> 租户用户**没有**管理端与监控大屏入口；直接输 URL 访问 `/admin`、`/screen` 会被送回用户端。
> 管理员访问 `/portal` 同样会被送回管理端。前端收敛入口，后端 `AuthInterceptor` 再做接口级拦截，两层都拦。

> 用户端与管理端都做了会话守卫：未登录会跳回 `/login`（登录态存 `localStorage.gu_token`）。页面统一纯路径（`/login`、`/admin`…），旧 `.html` 地址仍可用。

## 演示账号

| 账号 | 密码 | 角色 | 能做什么 |
|---|---|---|---|
| `admin` | `Hly@2026` | 平台管理员 | 全部功能，含系统管理（账号 / 会话 / 参数） |
| `ops` | `Hly@2026` | 值班运维 | 集群与告警相关，**无系统管理** |
| `tenant_a` | `Hly@2026` | 星辰智能科技（t1） | 只能进用户端，只能看自己租户 |
| `tenant_b` | `Hly@2026` | 凌云机器人（t2） | 同上 |
| `tenant_c` | `Hly@2026` | 幻核互动娱乐（t4） | 同上 |
| `tenant_d` | `Hly@2026` | 极光量化科技（t5） | 同上 |
| `tenant_e` | `Hly@2026` | 蓝湾医疗影像（t6） | 同上 |
| `guest` | — | 已停用 | 登录被拒，用于演示停用状态 |

> 连续 5 次密码错误锁定 10 分钟；管理员可在「系统管理 → 平台账号」解锁。
> 初始密码由 `SeedUsers` 启动时写入 BCrypt 哈希，库里没有明文。
> 演示共 6 个租户（t1~t6，其中 t3 海蓝云自营为冻结状态），全部在数据里标注为演示种子，`DbBootstrap` 按 id 幂等补齐。

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
日志：已有数据，跳过种子（users=8，tenants=6）

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
- 首次启动自动建表并灌入演示种子；**重启不清库**（种子只在空库写入，老库只做幂等升级）

```bash
docker compose logs -f app     # 看日志
docker compose down            # 停止（加 -v 会删除数据库卷）
```

默认 `RENTAL_K8S_MODE=fake`（模拟 Pod 生命周期，无需集群）。接真实集群（k3s）：

```bash
docker compose -f docker-compose.yml -f docker-compose.real.yml up -d --build app
# KUBECONFIG_PATH 默认 ../k8s-demo/kubeconfig
```

### 方式二：本地 JRE（H2 内存库，无需 Docker）

```bash
java -jar target/gpu-rental-0.1.0.jar
```

## 功能清单

### 用户端
- **算力市场**：单卡 / 整机 8 卡 / **闲时 5 折** / **无卡调试（CPU-only ¥0.10/时）** 四种规格，实时库存（fake 台账 / real 集群对账），按小时选时长、套餐折扣（24h 9.5 折 / 168h 9 折 / 720h 8.5 折）、**学生认证 9 折可叠加**、预扣金额随选择变化
- **下单**：幂等单号，重复提交不产生第二个实例；余额/配额不足直接拦截；售罄规格可登记**到货提醒**（补货自动通知）
- **我的实例**：状态、已用时长、到期时间、连接命令，**重启 / 关机 / 开机 / 续费 / 释放 / 克隆 / 升降配 / 重置系统 / 定时关机 / 远程桌面**（续费余额预扣 + 到期顺延 + 预扣池累加，同一事务；关机停计费、数据保留 7 天；定时关机落计划表由平台到点自动执行）
- **我的账单**：已结算 / 进行中估算 / 明细分条
- **自助服务**：镜像管理（保存 / **分享到社区广场** / **安全扫描**）、文件存储 + **公共数据集库**（挂载到实例）、工单提交与进度、消息通知、子账号（最多 20 个）、充值（演示模拟支付回调）、发票（按账期生成）、**优惠券（领券核销）**、**实名认证 / 学生认证（提交审核）**
- **大模型 API（MaaS）**：API 密钥管理（sk- 前缀，库里只存 SHA-256 哈希）、模型目录（4 个模型，单价 / 百万 Token）、调用记录与按 Token 实时扣费

### 管理端
- **集群总览**：顶部**控制面组件健康条**（apiserver/etcd/scheduler/controller/coredns/metrics-server/gpu-operator/dcgm-exporter/volcano 共 9 个）、6 个 KPI、利用率折线、卡池环形图、节点健康、活跃告警、租户用量、资源池、**K8s 原生五层架构图**
- **节点管理**：节点卡片 + 8 格显卡视图、标签、Cordon / Drain / 恢复调度、点卡标记故障或修复、**YAML 抽屉**（标准 Node 对象 + device plugin 上报字段）；本机 agent 实采节点为只读卡
- **工作负载**：
  - **部署向导**：名称 / 类型（Deployment / StatefulSet / Job）/ 租户 / 命名空间 / 镜像 / GPU 数 / 显存上限（整卡 / 1/2 / 1/4）/ 调度策略（Bin-pack / Spread / Gang），提交后 3 秒 Pending → Running
  - **工作负载对象表**：可删除回收
  - **算力实例表**：租户下单产生，含节点、Pod、用量、强制释放
  - **命名空间与配额**：系统空间 + 每个租户的 ResourceQuota 与已用量
- **资源池与调度**：池容量、**机架热力视图**（每卡温度）、队列配额、**资源池新建 / 改策略 / 删除**（池下有节点时拒绝删除）、调度策略说明
- **资源对象（YAML）**：**在线编辑并应用** Deployment / StatefulSet / Job / Service / Namespace / ConfigMap；后端校验 apiVersion、kind、metadata.name、namespace；同 kind+ns+name 再次应用视为更新；支持查看与删除
- **系统管理**：集群信息（版本 / 运行时 / CNI / 节点数）、平台账号与 RBAC 角色、**系统参数在线编辑**（单价、预扣开关、白标、LLM 参数、webhook、agent 令牌等）、**操作审计**（所有写操作留痕）
- **租户管理**：新建租户、调配额（real 模式同步集群 ResourceQuota）、冻结解冻、余额与消费
- **认证审核**：实名认证、学生认证逐条审核（通过 / 驳回留痕，学生认证通过后下单自动 9 折）、**优惠券发放**、**驱动版本矩阵**维护
- **监控告警**：P0/P1/P2 分级、确认 / 解决、利用率与功耗曲线、**告警规则 CRUD + 规则引擎**（每 30 秒评估，支持「持续 X 分钟」窗口、静默期、去重；webhook 真实投递，其余渠道记录模拟日志）
- **告警 AI 诊断**：一键调用 LLM（OpenAI 兼容接口，未配置上游时回退内置规则并标注来源）
- **智能巡检**：一键巡检，按当前集群真实状态生成问题项与健康分（含**反挖矿巡逻**：满负荷卡自动生成 P1 项与处置建议）；**巡检项可 AI 诊断、可一键转工单**（未修复项逐个生成平台工单并回写关联）
- **工单管理**：租户工单 + 巡检转工单统一视图，回复与流转
- **经营报表**：30 天收入曲线（按日聚合）、优惠券发放/核销汇总、租户数与余额、运行实例数与订单量，实时聚合

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
| POST | `/api/instances/{id}/renew` | 实例续费（余额预扣 + 到期顺延） |
| POST | `/api/instances/{id}/restart` | 实例重启（删 Pod 重建） |
| POST | `/api/instances/{id}/poweroff\|poweron` | 关机（停计费、数据保留 7 天）/ 开机 |
| POST | `/api/instances/{id}/clone` | 克隆（按原规格新开实例，原实例不动） |
| POST | `/api/instances/{id}/reset` | 重置系统（系统盘还原，数据盘不受影响） |
| POST | `/api/instances/{id}/resize` | 升降配（换新实例回收旧卡，余额多退少补） |
| POST | `/api/instances/{id}/schedule-shutdown` | 定时关机（到点平台自动执行，Reconciler 5 秒扫描） |
| POST | `/api/instances/{id}/cancel-schedule` | 取消定时关机 |
| GET | `/api/instances/{id}/schedules` | 定时关机计划列表 |
| POST | `/api/instances/{id}/save-image` | 实例存镜像 |
| GET | `/api/images`、`/api/images/community` | 我的镜像 / 社区广场 |
| POST | `/api/images/{id}/share`、`/scan` | 分享到社区 / 安全扫描（写回 scan_status） |
| GET | `/api/datasets` | 公共数据集库 |
| POST | `/api/instances/{id}/mount-dataset` | 数据集挂载到实例 |
| GET | `/api/coupons`、POST `/api/coupons/{id}/redeem` | 我的优惠券 / 领取核销（防重复领） |
| GET/POST | `/api/realname`、`/api/student-cert` | 实名 / 学生认证提交与查询 |
| GET | `/api/admin/realnames`、`/api/admin/student-certs` | 管理端认证审核列表 |
| POST | `/api/admin/realnames/{id}/review`、`/api/admin/student-certs/{id}/review` | 审核通过 / 驳回（学生认证通过 → 下单 9 折） |
| POST | `/api/admin/coupons` | 管理端发券 |
| GET | `/api/admin/driver-matrix` | 驱动版本矩阵 |
| GET | `/api/admin/report` | 经营报表（30 天收入 / 券汇总 / 租户余额 / 订单量） |
| POST | `/api/stock-alerts` | 到货提醒登记（售罄补货自动通知） |
| GET/POST/DELETE | `/api/api-keys`、`/api/api-keys/{id}` | API 密钥（仅一次返回完整 key，库存 SHA-256） |
| GET | `/api/maas/models`、`/api/maas/usage` | 模型目录 / 调用计量（租户维度） |
| GET | `/v1/models` | MaaS 模型目录（Bearer sk- 鉴权） |
| POST | `/v1/chat/completions` | MaaS 网关：OpenAI 兼容补全，按 Token 扣费 |
| POST | `/api/admin/alerts/{id}/diagnose` | 告警 AI 诊断 |
| POST | `/api/admin/inspections/{id}/diagnose\|ticket` | 巡检 AI 诊断 / 转工单 |

## 本地验证过的流程

1. 下单 → 3 秒就绪；同单号重复提交只出一个实例
2. 配额不足、余额不足被拦截
3. 整机 8 卡必须落在同一节点
4. 释放按秒结算、差额退回
5. Pod 卡在 Terminating 时释放失败、实例停在「回收中」、**卡不释放也不重复计费**
6. 解除卡住后巡检 5 秒内补删并结算
7. 故障卡从可售池摘除，修复后回池
8. 开关机 / 克隆 / 重置 / 升降配 / 定时关机（到点由 Reconciler 自动执行，实测 23:29:04 计划 23:29:07 关机完成）全链路
9. 计费口径：闲时 5 折 × 学生 9 折叠加、无卡调试 ¥0.10/时、券核销防重复、实名/学生认证审核生效
10. 镜像分享 → 社区可见、安全扫描写回「风险」、越权删除 403
11. **51 项三角色自动化实测**（admin / ops / tenant + 服务端状态断言）全绿；ark-cli 全生命周期（登录 / 下单 / 开机 / 释放结算）与 Web 端同源验证

## 已知限制（诚实口径）

- **集群 GPU 调度**：GPU Operator / DCGM、Volcano gang 调度、MIG 与时间片共享需要 Linux 集群节点上的 NVIDIA 显卡才能落地；本机 Windows 显卡（如 RTX 4060 Laptop）可提供**真实遥测数据**（见上「本机 GPU 实采 agent」），但不能作为 k8s 设备插件节点参与调度。相关页面会明确标注「未接入 / 需硬件」
- **两种数据模式**：`fake` 模式用台账数据 + 模拟 Pod 生命周期；`real` 模式（当前演示默认）接 k3s 真集群，能读的（节点 / Pod / 配额 / Prometheus）读真的，读不到的仍回台账并标 `dataSource`
- **大模型调用**：未配置 `llm.base_url` 时，MaaS 网关与 AI 诊断返回本地生成的演示回复（回复内容标注为演示），Token 计量与扣费仍然真实；配置后透传真实模型
- **支付**：充值走模拟支付回调，未接真实渠道
- **巡检**：只诊断不自动修复，支持 AI 诊断与转工单人工闭环
- **监控曲线**：三级真实度——本机 agent 实采 / Prometheus 实测 / 合成（均标注来源）；DCGM 卡级指标需 GPU Operator 就绪后接入
