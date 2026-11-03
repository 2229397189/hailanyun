package com.example.rental;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 真连 K8s：用 kubeconfig 建 Pod、查状态、删 Pod。
 *
 * 打开方式：rental.k8s.mode=real，并指定 rental.k8s.kubeconfig。
 * Pod 请求 nvidia.com/gpu，调度器按节点的 allocatable 分配——
 * 因此“分配”这一段与生产完全一致（前提是节点上有 device plugin 上报 GPU）。
 */
@Component
@ConditionalOnProperty(name = "rental.k8s.mode", havingValue = "real")
public class RealK8s implements K8sClient {

    private static final Logger log = LoggerFactory.getLogger(RealK8s.class);

    private final KubernetesClient client;
    private final String image;
    private final long deleteWaitMs;

    public RealK8s(@Value("${rental.k8s.kubeconfig:/kubeconfig}") String kubeconfig,
                   @Value("${rental.k8s.image:busybox:1.36}") String image,
                   @Value("${rental.k8s.deleteWaitMs:6000}") long deleteWaitMs) throws Exception {
        Config cfg;
        // 跑在集群里时优先用 ServiceAccount，跑在集群外才读 kubeconfig 文件
        Path saToken = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/token");
        if (Files.exists(saToken)) {
            cfg = Config.autoConfigure(null);
            log.info("检测到集群内 ServiceAccount，使用 in-cluster 配置");
        } else {
            cfg = Config.fromKubeconfig(Files.readString(Path.of(kubeconfig)));
        }
        this.client = new KubernetesClientBuilder().withConfig(cfg).build();
        this.image = image;
        this.deleteWaitMs = deleteWaitMs;
        String version = "unknown";
        try {
            version = client.getKubernetesVersion().getGitVersion();
        } catch (Exception e) {
            log.warn("读取集群版本失败：{}", e.getMessage());
        }
        log.info("RealK8s 已连接 {}（{}）", cfg.getMasterUrl(), version);
    }

    @Override
    public String create(String namespace, String podName, int gpuCount) {
        ensureNamespace(namespace);
        String q = String.valueOf(gpuCount);
        Pod pod = new PodBuilder()
                .withNewMetadata()
                  .withName(podName)
                  .withNamespace(namespace)
                  .addToLabels("app", "gpu-rental")
                  .addToLabels("managed-by", "gpu-rental-platform")
                .endMetadata()
                .withNewSpec()
                  .withRestartPolicy("Always")
                  .addNewContainer()
                    .withName("gpu")
                    .withImage(image)
                    .withCommand("sleep", "3600")
                    .withNewResources()
                      .addToRequests("nvidia.com/gpu", new Quantity(q))
                      .addToLimits("nvidia.com/gpu", new Quantity(q))
                    .endResources()
                  .endContainer()
                .endSpec()
                .build();
        client.pods().inNamespace(namespace).resource(pod).create();
        log.info("已创建 Pod {}/{}，请求 {} 张 GPU", namespace, podName, gpuCount);
        return namespace + "/" + podName;
    }

    @Override
    public boolean ready(String namespace, String podName) {
        Pod p = client.pods().inNamespace(namespace).withName(podName).get();
        if (p == null || p.getStatus() == null) {
            return false;
        }
        if (!"Running".equals(p.getStatus().getPhase())) {
            return false;
        }
        if (p.getStatus().getConditions() == null || p.getStatus().getConditions().isEmpty()) {
            return true;
        }
        return p.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    @Override
    public boolean delete(String namespace, String podName) {
        Pod exist = client.pods().inNamespace(namespace).withName(podName).get();
        if (exist == null) {
            return true;
        }
        try {
            // gracePeriod 0 = 直接删，不等 30 秒优雅退出；演示里能更快看到“卡住”的分支
            client.pods().inNamespace(namespace).withName(podName).withGracePeriod(0).delete();
        } catch (Exception e) {
            log.warn("删除 Pod {}/{} 失败：{}", namespace, podName, e.getMessage());
        }
        long deadline = System.currentTimeMillis() + deleteWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.pods().inNamespace(namespace).withName(podName).get() == null) {
                return true;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        boolean gone = client.pods().inNamespace(namespace).withName(podName).get() == null;
        if (!gone) {
            log.warn("Pod {}/{} 仍在删除中（可能卡在 Terminating），交给巡检重试", namespace, podName);
        }
        return gone;
    }

    private void ensureNamespace(String ns) {
        if (client.namespaces().withName(ns).get() == null) {
            client.namespaces()
                    .resource(new NamespaceBuilder().withNewMetadata().withName(ns).endMetadata().build())
                    .create();
            log.info("已创建命名空间 {}", ns);
        }
    }

    @Override
    public void createTenantNamespace(String namespace, int gpuQuota) {
        ensureNamespace(namespace);
        ResourceQuota rq = new ResourceQuotaBuilder()
                .withNewMetadata().withName("gpu-quota").withNamespace(namespace).endMetadata()
                .withNewSpec()
                  .addToHard("requests.nvidia.com/gpu", new Quantity(String.valueOf(gpuQuota)))
                  .addToHard("pods", new Quantity("20"))
                .endSpec()
                .build();
        client.resourceQuotas().inNamespace(namespace).resource(rq).createOrReplace();
        log.info("已为命名空间 {} 下发配额：{} 张 GPU / 20 Pod", namespace, gpuQuota);
    }

    @Override
    public java.util.List<String> listManagedPods() {
        return client.pods().inAnyNamespace()
                .withLabel("managed-by", "gpu-rental-platform")
                .list().getItems().stream()
                .map(p -> p.getMetadata().getNamespace() + "/" + p.getMetadata().getName())
                .toList();
    }

    @Override
    public String mode() {
        return "real";
    }
}
