package com.example.rental;

import java.util.List;
import java.util.Map;

/**
 * 对真集群的读写能力。只有 RealK8s 实现它——
 * fake 模式下这个 Bean 不存在，上层一律回退到数据库台账，并在返回里标注 dataSource=ledger，
 * 避免「界面显示的是集群实测、实际是种子数据」这种对交付有害的混淆。
 */
public interface ClusterOps {

    /** 集群基本信息：版本、API 地址、节点数、Pod 总数、CPU/内存/GPU 容量 */
    Map<String, Object> clusterInfo();

    /** 真实节点：名称、IP、状态、角色、版本、容量、GPU 可分配数、标签与污点 */
    List<Map<String, Object>> clusterNodes();

    /** 真实命名空间与其 ResourceQuota 已用量 */
    List<Map<String, Object>> clusterNamespaces();

    /** 控制面与 GPU 栈组件的实例数与健康状态（按标签真实探测，探不到就是 NotFound） */
    List<Map<String, Object>> componentHealth();

    /** Cordon / Uncordon：改 node.spec.unschedulable */
    boolean setCordon(String nodeName, boolean unschedulable);

    /** 驱逐该节点上平台托管的 Pod（带 managed-by 标签），返回驱逐数量 */
    int evictManagedPods(String nodeName);

    /** 应用 YAML，真下发；返回 kind/namespace/name/created */
    Map<String, Object> applyYaml(String yaml);

    /** 删除集群对象 */
    boolean deleteObject(String kind, String namespace, String name);

    /** 对象当前状态（是否存在、副本数、Pod 阶段） */
    Map<String, Object> objectState(String kind, String namespace, String name);

    /** 从镜像名/标签/容量自动发现的 GPU 台账行 */
    List<Map<String, Object>> discoverGpus();

    /** 负载真创建：Deployment / StatefulSet / Job，带调度策略与共享切分参数 */
    Map<String, Object> createWorkload(Map<String, Object> spec);

    /** 负载真删除 */
    boolean deleteWorkload(String kind, String namespace, String name);

    /** 负载重启：删除其 Pod 让控制器重建 */
    int restartWorkload(String kind, String namespace, String name);

    /** 负载扩缩容（停止 = 缩到 0），返回目标副本数 */
    int scaleWorkload(String kind, String namespace, String name, int replicas);

    /** Pod 真实日志 */
    String podLogs(String namespace, String podName, int tailLines);

    /** 按 app 标签列出 Pod 名（取日志、看状态用） */
    List<String> podsOf(String namespace, String appLabel);

    /** 命名空间与 GPU 配额同步下发（配额调整时调用） */
    void syncResourceQuota(String namespace, int gpuQuota);

    /** 生成真实可用的 join 命令（bootstrap token 落到集群，CA hash 从集群配置算） */
    Map<String, Object> joinCommand();
}
