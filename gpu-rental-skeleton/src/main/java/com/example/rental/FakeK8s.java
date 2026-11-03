package com.example.rental;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假的 K8s 运行时：模拟 Pod 的创建、就绪、删除。
 *
 * - 创建后约 3 秒变 Ready
 * - 删除默认立即成功
 * - 可把某个 Pod 标记为“卡在 Terminating”，用来演示回收兜底
 *
 * 仅在 rental.k8s.mode=fake（或不配置）时生效。
 */
@Component
@ConditionalOnProperty(name = "rental.k8s.mode", havingValue = "fake", matchIfMissing = true)
public class FakeK8s implements K8sClient {

    private static final long READY_DELAY_MS = 3000;

    private final Map<String, Long> createdAt = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Set<String> stuck = ConcurrentHashMap.newKeySet();

    @Override
    public String create(String namespace, String podName, int gpuCount) {
        createdAt.put(podName, System.currentTimeMillis());
        running.add(podName);
        return namespace + "/" + podName;
    }

    @Override
    public boolean ready(String namespace, String podName) {
        Long t = createdAt.get(podName);
        return t != null && System.currentTimeMillis() - t >= READY_DELAY_MS;
    }

    @Override
    public boolean delete(String namespace, String podName) {
        if (stuck.contains(podName)) {
            return false;
        }
        running.remove(podName);
        createdAt.remove(podName);
        return true;
    }

    @Override
    public String mode() {
        return "fake";
    }

    @Override
    public java.util.List<String> listManagedPods() {
        // 模拟运行时没有真实的 Pod 台账，对账结果为空
        return java.util.List.of();
    }

    @Override
    public void createTenantNamespace(String namespace, int gpuQuota) {
        // 模拟模式不做实际下发
    }

    /** 演示用：让某个 Pod 卡在 Terminating */
    public void makeStuck(String podName) {
        stuck.add(podName);
    }

    public void unstick(String podName) {
        stuck.remove(podName);
    }

    public int runningCount() {
        return running.size();
    }
}
