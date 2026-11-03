# 服务器演示部署（北京 115.190.208.96）

> 部署位置：`/opt/tokenark-demo`
> 演示地址：`http://115.190.208.96:8099/login.html`（需先在火山引擎安全组放通 8099/tcp）
> 首次部署：2026-11-03 15:45 · 账号 `admin / Hly@2026`

## 一、为什么单独写一份 compose

这台机器是**生产机**，跑着 futurewaytoken.com（new-api）、生产 PostgreSQL、geo-web、gpu-web。宿主机 80/443/3000/5432 全被占用。

直接上传项目根目录那份 `docker-compose.yml` 会出两个结果：

- nginx 抢 80/443 → `port is already allocated`，容器起不来
- grafana 抢 3000 → 同样失败

生产不会坏，但演示是黑的。所以这里用一份**隔离版**：

| 隔离手段 | 做法 |
|---|---|
| 项目名 | `-p tokenark-demo`，与任何现有 compose 项目不共享网络/卷 |
| 容器名 | 全部 `tokenark-*`，不与 `new-api`/`postgres`/`gpu-web-*` 重名 |
| 端口 | 对外只开 **8099**；数据库 5433、Prometheus 9090、Grafana 13000、node-exporter 9100 一律绑 `127.0.0.1` |
| 数据库 | 独立 PostgreSQL 容器 + 独立卷，不碰宿主机 5432 的生产库 |
| K8s | `RENTAL_K8S_MODE=fake`，**不在生产机装 k3s**（避免改 iptables、吃 500M+ 内存） |
| 宿主 nginx | 一行不改 |

## 二、部署步骤（可重复执行）

```bash
# 本地：打镜像 → 压缩（约 156 MB）
docker save gpu-rental-skeleton-app:latest | gzip -1 > tokenark-app.tar.gz

# 上传（镜像 + 两个配置文件）
scp tokenark-app.tar.gz docker-compose.demo.yml prometheus.yml root@115.190.208.96:/opt/tokenark-demo/

# 服务器：导入 + 放行 + 启动
ssh root@115.190.208.96
cd /opt/tokenark-demo
docker load -i tokenark-app.tar.gz && rm -f tokenark-app.tar.gz
firewall-cmd --permanent --add-port=8099/tcp && firewall-cmd --reload
docker compose -p tokenark-demo -f docker-compose.demo.yml up -d
```

> 镜像不在服务器上构建：4 核 7.5G、磁盘只剩 5.4G，Maven 拉依赖 + 构建缓存会把它吃干净。

## 三、日常操作

```bash
cd /opt/tokenark-demo

# 状态
docker compose -p tokenark-demo -f docker-compose.demo.yml ps
docker logs --tail 50 tokenark-app

# 健康
curl -s http://127.0.0.1:8099/api/health
# {"status":"UP","db":"UP","k8sMode":"fake",...}

# 带监控（Prometheus + Grafana + node-exporter）
docker compose -p tokenark-demo -f docker-compose.demo.yml --profile obs up -d

# 看 Grafana（外网不暴露，走隧道）
ssh -L 13000:127.0.0.1:13000 root@115.190.208.96
# 浏览器 http://127.0.0.1:13000   admin / TokenArk@2026

# 看数据库
ssh -L 5433:127.0.0.1:5433 root@115.190.208.96
```

## 四、回滚（一条命令）

```bash
cd /opt/tokenark-demo
docker compose -p tokenark-demo -f docker-compose.demo.yml --profile obs down -v
rm -rf /opt/tokenark-demo
# 可选：收回端口
firewall-cmd --permanent --remove-port=8099/tcp && firewall-cmd --reload
```

`down -v` 会删掉演示数据库卷。生产容器、生产卷、宿主 nginx 一律不受影响。

## 五、部署前后必须核对的四项

| 项 | 期望 | 实际（2026-11-03 15:45） |
|---|---|---|
| `new-api :3000` | 200 | 200 |
| `https://futurewaytoken.com` | 200 | 200 |
| `docker ps` 里原有容器 | 11 个全在 | 全在，未被重启 |
| 磁盘 | 别掉到 5G 以下 | 5.4G（部署前 6.4G，镜像占 1G） |

基线文件留在服务器 `/opt/tokenark-demo/BASELINE.txt`。

## 六、这份部署与原版的差异（演示时别说错）

| 能力 | 本机完整栈 | 服务器版 |
|---|---|---|
| 登录 / 权限 / 审计 | 真 | 真（同一镜像） |
| 下单 / 计费 / 释放 | 真（建真 Pod） | **模拟**（FakeK8s，界面状态变化，集群里没有 Pod） |
| 「切终端看 Pod」这一步 | 可以 | **不行**，没有集群 |
| Grafana 面板 | 有 | 有（走 profile obs，需隧道访问） |
| 私有镜像仓库 registry | 有 | 无（离线交付用，演示不需要） |
| HTTPS | 有（自签） | 无（HTTP 8099） |

**最关键一条**：服务器版是 fake 模式。演示时不要承诺"这是真集群"，也不要去终端敲 `kubectl get pods`——敲了是空的。

要真集群效果，只有两条路：在服务器装 k3s（改 iptables，生产机不建议），或者演示时用本机那套。

## 七、安全提醒

- 8099 是**公网 HTTP**，账号 `admin / Hly@2026` 是弱口令。演示完请 `down` 掉，或至少改密码。
- 不要把 5433/9090/13000 放开到公网，它们绑的是 127.0.0.1，靠 SSH 隧道看。
- 演示数据可随时重置：`down -v` 再 `up -d`，空库会自动重新灌种子。
