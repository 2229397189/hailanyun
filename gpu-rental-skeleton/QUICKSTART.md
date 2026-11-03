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

> K8s 模式默认 `real`，需要 `../k8s-demo/kubeconfig`。没有集群时把
> `docker-compose.yml` 里的 `RENTAL_K8S_MODE` 改成 `fake`。

---

## 方式三：部署到 K8s（客户环境形态）

适合：客户机房交付。

```bash
# 1. 导入镜像
docker save gpu-rental-skeleton-app:latest | \
  docker exec -i <k3s容器名> ctr -n k8s.io images import -

# 2. 部署
kubectl apply -f deploy/k8s/tokenark.yaml

# 3. 访问
kubectl port-forward -n tokenark svc/tokenark-app 8099:8099
```

平台会自动检测集群内 ServiceAccount，用 in-cluster 认证，不需要挂 kubeconfig。

---

## 默认账号

| 账号 | 密码 | 角色 | 能进 |
|---|---|---|---|
| `admin` | `Hly@2026` | 平台管理员 | 管理端 + 大屏 |
| `ops` | `Hly@2026` | 值班运维 | 管理端（无系统管理） |
| `tenant_a` | `Hly@2026` | 租户用户 | 用户端 |
| `tenant_b` | `Hly@2026` | 租户用户 | 用户端 |
| `guest` | — | 已停用 | 登录被拒 |

> 连续 5 次密码错误锁定 10 分钟。初始密码由 `SeedUsers` 启动时写入 BCrypt。

---

## 页面清单

| 路径 | 页面 |
|---|---|
| `/login.html` | 登录 |
| `/admin.html` | 管理端（10 个模块） |
| `/portal.html` | 用户端（算力市场 / 我的实例 / 我的账单） |
| `/screen.html` | NOC 监控大屏 |

---

## 常见问题

**Q：启动报 `nvidia-smi` 相关错误？**
A：当前版本不依赖 nvidia-smi，GPU 数据是模拟的。有真卡后另接。

**Q：`real` 模式启动失败？**
A：检查 `kubeconfig` 路径，或改成 `RENTAL_K8S_MODE=fake`。

**Q：Grafana 打不开？**
A：等 20 秒，Prometheus 要先抓一轮数据。首次登录 `admin / TokenArk@2026`。

**Q：端口冲突？**
A：改 `docker-compose.yml` 里的 `ports` 映射，注意 80/443/3000/5433/8099/9090/9100/5000。

**Q：数据被清空了？**
A：用 compose 启动时数据在 `pgdata` 卷里，不会清空；用 `java -jar` 时是 H2 内存库，重启即清空。
