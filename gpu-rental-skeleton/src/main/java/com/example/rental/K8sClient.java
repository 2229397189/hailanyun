package com.example.rental;

/**
 * 实例运行时的抽象。
 *
 * 两种实现：
 *   FakeK8s  —— 模拟 Pod 生命周期（rental.k8s.mode=fake，默认）
 *   RealK8s  —— 真连 K8s apiserver（rental.k8s.mode=real，用 fabric8）
 *
 * 业务层只依赖这个接口，切换实现不需要改任何业务代码。
 */
public interface K8sClient {

    /**
     * @param imageRef   用户在创建页选的镜像（目录里的 ref，不是可直接拉取的地址）
     * @param dataDiskGb 数据盘容量，>0 时挂一块真实 PVC 到 /data
     */
    String create(String namespace, String podName, int gpuCount, String imageRef, int dataDiskGb);

    boolean ready(String namespace, String podName);

    /**
     * @return true 表示 Pod 已经真的不在了；false 表示还在删除中（Terminating 卡住）
     */
    boolean delete(String namespace, String podName);

    /** 释放实例时才删数据盘；关机/重启只删 Pod，PVC 留着，开机挂回同一个盘 */
    void deleteData(String namespace, String podName);

    /** 当前运行时模式：fake / real */
    String mode();

    /**
     * 列出平台托管的所有 Pod，格式 "namespace/podName"。
     * 用于资源对账：找出数据库里已经没有记录的孤儿 Pod。
     * fake 模式返回空列表。
     */
    java.util.List<String> listManagedPods();

    /**
     * 为租户创建命名空间与 GPU 配额（ResourceQuota）。
     * real 模式真正下发到集群；fake 模式只记录日志。
     */
    void createTenantNamespace(String namespace, int gpuQuota);
}
