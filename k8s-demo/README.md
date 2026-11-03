# k3s 单节点验证环境（无 GPU）

用 Docker 起一个真 k3s，再伪造 `nvidia.com/gpu` 容量，**在没有显卡的机器上验证 K8s 层的算力分配与配额**。

## 一句话原理

`device plugin` 正常是通过 **Node status 的 capacity/allocatable** 把卡“上报”给 K8s 的。
调度器只读这个字段——所以直接 patch 它，调度行为与真卡完全一致：

```bash
kubectl patch node <node> --subresource=status --type=json -p='[
  {"op":"add","path":"/status/capacity/nvidia.com~1gpu","value":"8"},
  {"op":"add","path":"/status/allocatable/nvidia.com~1gpu","value":"8"}
]'
```

区别只有一点：容器里没有真的 GPU 设备文件（所以不能跑 CUDA），但**调度、配额、抢占、排队全部是真的**。

## 怎么跑

```bash
bash 01-setup.sh          # 起集群 + 伪造 8 卡（约 40 秒）
bash 02-quota-demo.sh     # 租户配额 4 卡，第 5 个被拒
bash 03-capacity-demo.sh  # 放开配额，8 卡占满，第 9 个 Pending
bash cleanup.sh           # 回收
```

## 实测输出（2026-11-02 本机）

**分配真实生效**
```
$ kubectl get pod gpu-test-1 -n tenant-a -o wide
NAME         READY  STATUS   NODE
gpu-test-1   1/1    Running  eea597a9bad8

Events:
  Normal  Scheduled  default-scheduler  Successfully assigned tenant-a/gpu-test-1 to eea597a9bad8
```

**租户配额拦截**
```
$ kubectl apply -f pod-5.yaml
Error from server (Forbidden): pods "gpu-test-5" is forbidden:
exceeded quota: gpu-quota, requested: requests.nvidia.com/gpu=1,
used: requests.nvidia.com/gpu=4, limited: requests.nvidia.com/gpu=4
```

**节点容量耗尽后排队**
```
$ kubectl get pod gpu-test-9 -n tenant-a
gpu-test-9   0/1   Pending

Events:
  Warning  FailedScheduling  0/1 nodes are available: 1 Insufficient nvidia.com/gpu
```

## 对照《K8s 算力分配技术方案（5090 双机）》第 9 节验收标准

| 验收项 | 本环境 | 说明 |
|---|---|---|
| 资源可见性：每节点 `nvidia.com/gpu: 8` | ✅ 通过 | patch status 后 `kubectl describe node` 可见 |
| 配额生效：超配额进不了 Running | ✅ 通过 | 创建阶段即被 ResourceQuota 拒绝，比进不了 Running 更早 |
| 节点容量：卡耗尽后 Pending | ✅ 通过 | `Insufficient nvidia.com/gpu` |
| 借用生效（A 空闲 B 可用超额） | ❌ 未验证 | 需 Kueue，且**对外租赁场景不该开**（客户配额被借走=违约） |
| 抢占生效 | ❌ 未验证 | 需 Volcano/Kueue；对租户实例**不应开启** |
| 切分隔离（HAMi） | ❌ 未验证 | 需 HAMi + 真卡，且 5090 新架构支持未确认 |
| 无泄漏：任务结束后显存归零 | ❌ 未验证 | 需真卡 + DCGM |
| Gang 调度 | ❌ 未验证 | 需 Volcano，本机 GitHub raw 不通装不了 |
| 拓扑感知 / RDMA | ❌ 未验证 | 需多节点 + RDMA 硬件 |

## 接平台（真集群模式）

平台方 `gpu-rental-skeleton` 通过 `RENTAL_K8S_MODE=real` 连这个集群，用本目录的 `kubeconfig`
（其中 server 已改成 `https://host.docker.internal:6443`，供容器内访问）。

实测链路（2026-11-02）：

```
POST /api/orders  →  RealK8s.create()
                  →  集群出现 gpu-pod-xxx，requests.nvidia.com/gpu=1
                  →  Pod Running
                  →  Reconciler 查询 ready() → 平台实例自动变 running
POST /instances/{id}/release
                  →  RealK8s.delete() → Pod 消失（namespace 保留）
                  →  按秒结算并退回差额
```

管理端右上角徽章会显示 **「真集群 · k3s」**（读 `/api/runtime`）。

## 已知限制

- **伪造值会被 kubelet 覆盖**：kubelet 周期性回写 Node status，跑一段时间后 `nvidia.com/gpu` 可能消失。被覆盖后重跑 `01-setup.sh` 的 patch 段即可。
- **容器里没有真 GPU**：`nvidia-smi` 会报错，这是预期行为（本环境只验证调度）。
- **单节点**：拓扑感知、跨节点 Gang 无法验证。
- **Volcano 未安装**：`raw.githubusercontent.com` 在本机不可达（SSL 000），需换成可达的镜像源或离线 yaml。
