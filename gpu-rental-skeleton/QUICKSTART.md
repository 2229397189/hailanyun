# 快速开始

三种启动方式，按场景选一种。

---

## 方式一：本地直跑（最快，30 秒）

适合：看代码、改代码、没有 Docker。

```bash
java -jar dist/gpu-rental-0.1.0.jar
```

打开 http://localhost:8099

- 数据库：内置 H2（内存库，重启数据清空）
- K8s 模式：`fake`（模拟 Pod 生命周期，不需要集群）

---

## 方式二：Docker Compose（推荐，完整栈）

适合：演示、验收、要完整环境。

```bash
docker compose up -d --build
```

打开 https://localhost （自签证书，浏览器点"继续访问"）

启动的 8 个容器：

| 容器 | 端口 | 说明 |
|---|---|---|
| nginx | 80 / 443 | TLS 终止，HTTP 自动跳 HTTPS |
| app | 8099 | 平台应用 |
| postgres | 5433 | 数据库（数据持久化） |
| prometheus | 9090 | 指标存储 |
| grafana | 3000 | 监控面板（admin / TokenArk@2026） |
| node-exporter | 9100 | 主机指标 |
| registry | 5000 | 私有镜像仓库 |
| backup | — | 每日 pg_dump，保留 7 天 |

> K8s 模式默认 `fake`（模拟 Pod 生命周期，不需要集群）。接真集群时：
> `docker compose -f docker-compose.yml -f docker-compose.real.yml up -d --build app`
> （`KUBECONFIG_PATH` 默认 `../k8s-demo/kubeconfig`）。

---

## 方式三：部署到 K8s（客户环境形态）

适合：客户机房交付。

```bash
# 1. 导入镜像
docker save gpu-rental-skeleton-app:latest | \
  docker exec -i <k3s容器名> ctr -n k8s.io images import -

# 2. 部署（Helm 推荐；或裸清单 kubectl apply -f deploy/k8s/tokenark.yaml）
helm install tokenark deploy/helm/tokenark -n tokenark --create-namespace

# 3. 访问
kubectl port-forward -n tokenark svc/tokenark-app 8099:8099
```

平台会自动检测集群内 ServiceAccount，用 in-cluster 认证，不需要挂 kubeconfig。
Helm values 里 `app.k8sMode=real` 同时下发平台所需的 RBAC；`fake` 演示可改 values 或加 `--set app.k8sMode=fake`。

---

## 默认账号

| 账号 | 密码 | 角色 | 能进 |
|---|---|---|---|
| `admin` | `Hly@2026` | 平台管理员 | 管理端 + 大屏 |
| `ops` | `Hly@2026` | 值班运维 | 管理端（无系统管理） |
| `tenant_a` | `Hly@2026` | 星辰智能科技（t1） | 用户端 |
| `tenant_b` | `Hly@2026` | 凌云机器人（t2） | 用户端 |
| `tenant_c` | `Hly@2026` | 幻核互动娱乐（t4） | 用户端 |
| `tenant_d` | `Hly@2026` | 极光量化科技（t5） | 用户端 |
| `tenant_e` | `Hly@2026` | 蓝湾医疗影像（t6） | 用户端 |
| `guest` | — | 已停用 | 登录被拒 |

> 连续 5 次密码错误锁定 10 分钟。初始密码由 `SeedUsers` 启动时写入 BCrypt。

---

## 页面清单

| 路径 | 页面 |
|---|---|
| `/login` | 登录（演示账号表 + 反挖矿协议条款） |
| `/admin` | 管理端（集群总览 / 节点 / 工作负载 / 资源池 / 租户 / 认证审核 / 监控告警 / 巡检 / 工单 / 经营报表 / 系统管理） |
| `/portal` | 用户端（算力市场 / 我的实例 / 我的账单） |
| `/instances` | 用户端 · 实例详情（全部生命周期操作按钮） |
| `/market` | 用户端 · 算力市场（含闲时 5 折 / 无卡调试规格卡） |
| `/llm-api` | 用户端 · 大模型 API（密钥 / 模型目录 / 调用计量） |
| `/images` | 用户端 · 镜像（我的 / 社区广场 / 分享 / 安全扫描） |
| `/storage` | 用户端 · 文件存储 + 公共数据集库 |
| `/account` | 用户端 · 账户（余额 / 优惠券 / 实名 / 学生认证 / 发票） |
| `/tickets`、`/notifications`、`/subaccounts`、`/recharge`、`/wallet`、`/orders` | 用户端 · 工单 / 消息 / 子账号 / 充值 / 钱包 / 订单 |
| `/about`、`/solutions`、`/group`、`/baremetal` | 品牌站（关于我们含用户服务协议、解决方案、拼租卡、裸金属） |
| `/screen` | NOC 监控大屏 |

> 控制台右上角点**头像**打开账号面板：身份 / 账户总余额（充值入口）/ 外观（浅色·深色·跟随系统）/ 退出登录。

## 命令行客户端 ark-cli（可选）

纯 Python 标准库，与 Web 端同一套后端，适合脚本化演示：

```bash
python deploy/cli/ark-cli.py login                       # 交互式登录（或 ARK_USER/ARK_PASSWORD 环境变量）
python deploy/cli/ark-cli.py skus                        # SKU 与单价（含闲时/无卡）
python deploy/cli/ark-cli.py balance                     # 余额与代金券
python deploy/cli/ark-cli.py instances                   # 实例列表
python deploy/cli/ark-cli.py poweroff <实例ID>            # 关机（poweron 开机 / restart 重启）
python deploy/cli/ark-cli.py renew <实例ID> <小时数>       # 续费
python deploy/cli/ark-cli.py release <实例ID>             # 释放：输出使用秒数与退款金额
```

Token 缓存于 `~/.ark-cli.json`；平台地址默认 `http://localhost:8099`（`ARK_URL` 可改，`ARK_TOKEN` 可直接注入）。

## 接入本机真实 GPU 数据（可选）

有 NVIDIA 显卡的机器跑一个脚本，监控曲线就是真实数据（每 10 秒上报）：

```bash
python deploy/agent/win-agent.py http://localhost:8099 hly-local-agent-2026
```

管理端「节点管理」出现「本机实采」只读节点卡、监控页曲线标注「本机真实 GPU 实采」即接入成功。令牌可在 系统管理→系统参数 `agent.token` 修改。不跑 agent 则回退 Prometheus/DCGM，再回退到标注「合成」的演示曲线。

---

## 常见问题

**Q：启动报 `nvidia-smi` 相关错误？**
A：当前版本不依赖 nvidia-smi。`fake` 模式 GPU 数据来自台账模拟；`real` 模式接真集群后能读的（节点 / Pod / 配额）读真的，卡级 DCGM 指标需 GPU Operator 就绪后接入（界面会标注"未接入"）。想在本机展示真实 GPU 曲线，跑 `deploy/agent/win-agent.py`（见上）。

**Q：`real` 模式启动失败？**
A：检查 `kubeconfig` 路径，或改成 `RENTAL_K8S_MODE=fake`。

**Q：Grafana 打不开？**
A：等 20 秒，Prometheus 要先抓一轮数据。首次登录 `admin / TokenArk@2026`。

**Q：端口冲突？**
A：改 `docker-compose.yml` 里的 `ports` 映射，注意 80/443/3000/5433/8099/9090/9100/5000。

**Q：数据被清空了？**
A：用 compose 启动时数据在 `pgdata` 卷里，不会清空；用 `java -jar` 时是 H2 内存库，重启即清空。
