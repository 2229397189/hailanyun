package com.example.rental;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 真连 K8s：既实现实例运行时（建/查/删 Pod），也实现 ClusterOps（集群实测与真下发）。
 *
 * 打开方式：rental.k8s.mode=real，并指定 rental.k8s.kubeconfig。
 * 集群里探不到的东西一律报 NotFound / false，不做任何"看起来正常"的兜底——
 * 交付现场被客户戳穿一次，比如实标注"未部署"代价大得多。
 */
@Component
@ConditionalOnProperty(name = "rental.k8s.mode", havingValue = "real")
public class RealK8s implements K8sClient, ClusterOps {

    private static final Logger log = LoggerFactory.getLogger(RealK8s.class);
    private static final String MANAGED_LABEL = "managed-by";
    private static final String MANAGED_BY = "gpu-rental-platform";

    private final KubernetesClient client;
    private final Db db;
    private final String image;
    /** NodePort 轮转游标：kube-proxy 删规则有延迟，避免刚释放的端口被下一个实例立刻复用 */
    private int nextNodePort = 30081;
    /** GPU 实例必须用带 glibc 的 CUDA 镜像：busybox 跑不了 nvidia-smi */
    private final String gpuImage;
    private final String storageClass;
    private final long deleteWaitMs;
    private final String clusterVersion;
    private final String masterUrl;

    public RealK8s(@Value("${rental.k8s.kubeconfig:/kubeconfig}") String kubeconfig,
                   Db db,
                   @Value("${rental.k8s.image:busybox:1.36}") String image,
                   @Value("${rental.k8s.gpuImage:nvidia/cuda:12.4.0-base-ubuntu22.04}") String gpuImage,
                   @Value("${rental.k8s.storageClass:local-path}") String storageClass,
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
        this.db = db;
        this.image = image;
        this.gpuImage = gpuImage;
        this.storageClass = storageClass;
        this.deleteWaitMs = deleteWaitMs;
        String version = "unknown";
        try {
            version = client.getKubernetesVersion().getGitVersion();
        } catch (Exception e) {
            log.warn("读取集群版本失败：{}", e.getMessage());
        }
        this.clusterVersion = version;
        this.masterUrl = String.valueOf(cfg.getMasterUrl());
        log.info("RealK8s 已连接 {}（{}）", masterUrl, clusterVersion);
    }

    // ================================================================= K8sClient

    @Override
    public String create(String namespace, String podName, int gpuCount, String imageRef, int dataDiskGb) {
        return create(namespace, podName, gpuCount, imageRef, dataDiskGb, true);
    }

    @Override
    public String create(String namespace, String podName, int gpuCount, String imageRef,
                         int dataDiskGb, boolean realGpuNode) {
        ensureNamespace(namespace);
        String q = String.valueOf(gpuCount);
        List<io.fabric8.kubernetes.api.model.Volume> volumes = new ArrayList<>();
        // GPU 分两件事：
        //  ① 挂载驱动库 / nvidia-smi：只要集群里真有 GPU，GPU 实例就应能看到卡（用户要能操控显卡）
        //  ② 申请 nvidia.com/gpu 资源：只有登记在真实节点上的卡才申请，避免台账虚拟卡超卖导致 Pod Pending
        boolean hasGpu = gpuCount > 0;
        boolean clusterHasGpu = false;
        try {
            clusterHasGpu = client.nodes().list().getItems().stream().anyMatch(n ->
                    n.getStatus() != null && n.getStatus().getAllocatable() != null
                            && n.getStatus().getAllocatable().containsKey("nvidia.com/gpu"));
        } catch (Exception ignored) {
            // 查不到节点能力时退回原逻辑，不影响建实例
        }
        boolean needGpu = hasGpu && (realGpuNode || clusterHasGpu);
        boolean requestGpu = hasGpu && realGpuNode;
        // 用户选的镜像要真的跑起来：按 image.map 映射成可拉取地址，没配映射才用默认镜像
        String podImage = resolveImage(imageRef, hasGpu ? gpuImage : image);
        ContainerBuilder container = new ContainerBuilder()
                .withName("gpu")
                // GPU 实例默认用 CUDA 基础镜像：busybox 没有 glibc，nvidia-smi 根本起不来
                .withImage(podImage)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("sleep", "3600")
                // 用户选的镜像写进容器环境，exec 进 Pod 就能核对「我选的是哪个」，不是只存在数据库里
                .addToEnv(new EnvVarBuilder().withName("TOKENARK_IMAGE")
                        .withValue(imageRef == null ? "" : imageRef).build());
        if (requestGpu) {
            container.withNewResources()
                      .addToRequests("nvidia.com/gpu", new Quantity(q))
                      .addToLimits("nvidia.com/gpu", new Quantity(q))
                    .endResources();
        }
        if (needGpu) {
            // 本机 WSL2 直通配方：设备节点 + Windows 驱动库 + nvidia-smi + DXCore 桥接库。
            // 生产集群由 nvidia-device-plugin 注入，不需要这一段；这里是因为 k3s 跑在 Docker 里，
            // 拿不到 Docker Desktop 的 runtime 注入，只能显式挂。
            container.withNewSecurityContext().withPrivileged(true).endSecurityContext();
            container.addToEnv(new EnvVarBuilder().withName("LD_LIBRARY_PATH")
                    .withValue("/opt/gpulib:/usr/lib/x86_64-linux-gnu").build());
            container.addToEnv(new EnvVarBuilder().withName("NVIDIA_VISIBLE_DEVICES").withValue("all").build());
            container.addToVolumeMounts(new VolumeMountBuilder().withName("dxg")
                    .withMountPath("/dev/dxg").build());
            container.addToVolumeMounts(new VolumeMountBuilder().withName("wsllib")
                    .withMountPath("/usr/lib/wsl").withReadOnly(true).build());
            container.addToVolumeMounts(new VolumeMountBuilder().withName("smi")
                    .withMountPath("/usr/bin/nvidia-smi").build());
            container.addToVolumeMounts(new VolumeMountBuilder().withName("gpulib")
                    .withMountPath("/opt/gpulib").withReadOnly(true).build());
            volumes.add(new VolumeBuilder().withName("dxg")
                    .withNewHostPath().withPath("/dev/dxg").endHostPath().build());
            volumes.add(new VolumeBuilder().withName("wsllib")
                    .withNewHostPath().withPath("/usr/lib/wsl").endHostPath().build());
            volumes.add(new VolumeBuilder().withName("smi")
                    .withNewHostPath().withPath("/usr/bin/nvidia-smi").endHostPath().build());
            volumes.add(new VolumeBuilder().withName("gpulib")
                    .withNewHostPath().withPath("/opt/gpulib").endHostPath().build());
        }
        if (dataDiskGb > 0) {
            String claim = dataClaim(podName);
            ensureDataClaim(namespace, claim, dataDiskGb);
            container.addToVolumeMounts(new VolumeMountBuilder().withName("data").withMountPath("/data").build());
            volumes.add(new VolumeBuilder().withName("data")
                    .withNewPersistentVolumeClaim().withClaimName(claim).endPersistentVolumeClaim().build());
        }
        Pod pod = new PodBuilder()
                .withNewMetadata()
                  .withName(podName)
                  .withNamespace(namespace)
                  .addToLabels("app", "gpu-rental")
                  // Service 只能按标签选 Pod，加一个与 Pod 同名的唯一标签
                  .addToLabels("podname", podName)
                  .addToLabels(MANAGED_LABEL, MANAGED_BY)
                  .addToAnnotations("rental.tokenark/image", imageRef == null ? image : imageRef)
                  .addToAnnotations("rental.tokenark/data-disk-gb", String.valueOf(dataDiskGb))
                  // 标注这个 Pod 到底有没有真卡：passthrough=真卡直通，simulated=台账模拟，none=无卡
                  .addToAnnotations("rental.tokenark/gpu-mode",
                          !hasGpu ? "none" : (needGpu ? "passthrough" : "simulated"))
                .endMetadata()
                .withNewSpec()
                  .withRestartPolicy("Always")
                  .withContainers(container.build())
                  .withVolumes(volumes)
                .endSpec()
                .build();
        client.pods().inNamespace(namespace).resource(pod).create();
        log.info("已创建 Pod {}/{}，GPU {} 张（{}），镜像 {}，数据盘 {}G",
                namespace, podName, gpuCount,
                !hasGpu ? "无卡" : (needGpu ? "真卡直通" : "台账模拟-不申请 GPU"),
                imageRef == null ? "-" : imageRef, dataDiskGb);
        return namespace + "/" + podName;
    }

    /**
     * 镜像映射：settings.image.map 存 JSON {"目录里的ref": "可拉取镜像地址"}。
     * 没配映射就回落默认镜像——不做「看起来选了镜像、其实跑默认」的假动作。
     */
    private String resolveImage(String imageRef, String fallback) {
        if (imageRef == null || imageRef.isBlank()) {
            return fallback;
        }
        try {
            String map = db.setting("image.map");
            if (map == null || map.isBlank()) {
                return fallback;
            }
            Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(map, Map.class);
            Object hit = m.get(imageRef);
            if (hit != null && !String.valueOf(hit).isBlank()) {
                return String.valueOf(hit);
            }
            log.warn("镜像 {} 没有在 image.map 里配映射，回落到 {}（选了不生效等于骗人，这里显式告警）", imageRef, fallback);
            return fallback;
        } catch (Exception e) {
            log.warn("解析 image.map 失败（{}），回落默认镜像", e.getMessage());
            return fallback;
        }
    }

    /** 数据盘：真实 PVC，local-path 供给，Pod 删掉后 claim 还在（关机保留数据），只有释放实例才删 */
    private void ensureDataClaim(String namespace, String claim, int gb) {
        if (client.persistentVolumeClaims().inNamespace(namespace).withName(claim).get() != null) {
            return;
        }
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(claim).withNamespace(namespace)
                  .addToLabels(MANAGED_LABEL, MANAGED_BY).endMetadata()
                .withNewSpec()
                  .withStorageClassName(storageClass)
                  .withAccessModes("ReadWriteOnce")
                  .withNewResources().addToRequests("storage", new Quantity(gb + "Gi")).endResources()
                .endSpec()
                .build();
        client.persistentVolumeClaims().inNamespace(namespace).resource(pvc).create();
        log.info("已创建数据盘 PVC {}/{}（{}Gi，StorageClass {}）", namespace, claim, gb, storageClass);
    }

    @Override
    public void deleteData(String namespace, String podName) {
        String claim = dataClaim(podName);
        try {
            client.persistentVolumeClaims().inNamespace(namespace).withName(claim).delete();
        } catch (Exception e) {
            log.warn("删除数据盘 PVC {}/{} 失败：{}", namespace, claim, e.getMessage());
        }
    }

    private static String dataClaim(String podName) {
        return "data-" + podName;
    }

    /**
     * 运行中扩容数据盘：直接 patch PVC 的 storage request。
     * 前提是 StorageClass 支持 allowVolumeExpansion（k3s 默认 local-path 支持）；
     * 不支持时 API 会拒绝，这里返回 false 让上层不改台账、不扣钱。
     */
    @Override
    public boolean expandData(String namespace, String podName, int fromGb, int toGb) {
        String claim = dataClaim(podName);
        try {
            PersistentVolumeClaim pvc = client.persistentVolumeClaims()
                    .inNamespace(namespace).withName(claim).get();
            if (pvc == null) {
                log.warn("扩容失败：PVC {}/{} 不存在", namespace, claim);
                return false;
            }
            client.persistentVolumeClaims().inNamespace(namespace).withName(claim)
                    .edit(p -> new PersistentVolumeClaimBuilder(p)
                            .editSpec()
                            .editResources()
                            .addToRequests("storage", new Quantity(toGb + "Gi"))
                            .endResources()
                            .endSpec()
                            .build());
            log.info("数据盘 PVC {}/{} 已由 {}Gi 扩至 {}Gi", namespace, claim, fromGb, toGb);
            return true;
        } catch (Exception e) {
            log.warn("数据盘扩容失败 {}/{}：{}", namespace, claim, e.getMessage());
            return false;
        }
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
        syncResourceQuota(namespace, gpuQuota);
        ensureNetworkIsolation(namespace);
    }

    /**
     * 租户网络隔离（D-5 的第三层）：
     *   - 默认拒绝所有跨命名空间的进出流量
     *   - 允许同命名空间内互通（同租户的实例/服务）
     *   - 放行 DNS（UDP/TCP 53），否则集群内域名解析全废
     * 幂等：已存在同名策略就跳过。
     */
    private void ensureNetworkIsolation(String namespace) {
        String name = "tenant-isolation";
        try {
            if (client.network().v1().networkPolicies().inNamespace(namespace).withName(name).get() != null) {
                return;
            }
            io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy np =
                    new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder()
                    .withNewMetadata()
                      .withName(name)
                      .withNamespace(namespace)
                      .addToLabels(MANAGED_LABEL, MANAGED_BY)
                    .endMetadata()
                    .withNewSpec()
                      .withNewPodSelector().endPodSelector()
                      .addToPolicyTypes("Ingress", "Egress")
                      .addNewIngress()
                        .addNewFrom().withNewPodSelector().endPodSelector().endFrom()
                      .endIngress()
                      .addNewEgress()
                        .addNewTo().withNewPodSelector().endPodSelector().endTo()
                      .endEgress()
                      .addNewEgress()
                        .addNewPort().withProtocol("UDP").withPort(new io.fabric8.kubernetes.api.model.IntOrString(53)).endPort()
                        .addNewPort().withProtocol("TCP").withPort(new io.fabric8.kubernetes.api.model.IntOrString(53)).endPort()
                      .endEgress()
                    .endSpec()
                    .build();
            client.network().v1().networkPolicies().inNamespace(namespace).resource(np).create();
            log.info("已下发租户网络隔离策略 {}/{}", namespace, name);
        } catch (Exception e) {
            // 集群没装网络插件（如 k3s 默认 flannel 不支持 NetworkPolicy）时不能把建租户搞失败
            log.warn("下发 NetworkPolicy 失败（{}）：{}", namespace, e.getMessage());
        }
    }

    @Override
    public java.util.List<String> listManagedPods() {
        return client.pods().inAnyNamespace()
                .withLabel(MANAGED_LABEL, MANAGED_BY)
                .list().getItems().stream()
                .map(p -> p.getMetadata().getNamespace() + "/" + p.getMetadata().getName())
                .toList();
    }

    @Override
    public String mode() {
        return "real";
    }


    // ================================================================= 执行与端口暴露

    /**
     * 在 Pod 内执行命令并收集输出。
     * 用于「守护进程」：nohup 起进程后回显 PID，进程随 Pod 存活，不依赖 SSH 会话。
     */
    @Override
    public String exec(String namespace, String podName, String... command) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        io.fabric8.kubernetes.client.dsl.ExecWatch watch = null;
        try {
            watch = client.pods().inNamespace(namespace).withName(podName)
                    .writingOutput(out)
                    .writingError(out)
                    .usingListener(new io.fabric8.kubernetes.client.dsl.ExecListener() {
                        @Override
                        public void onClose(int code, String reason) {
                            latch.countDown();
                        }

                    })
                    .exec(command);
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("exec {}/{} 失败：{}", namespace, podName, e.getMessage());
            return "";
        } finally {
            if (watch != null) {
                try {
                    watch.close();
                } catch (Exception ignored) {
                    // 关流失败不影响结果
                }
            }
        }
    }

    /**
     * 把 Pod 端口以 NodePort 暴露：端口号固定在 30081-30099（交付时 k3s 容器映射 30080-30099，
     * 但宿主环境 30080 会被本机代理占用/劫持，跳过它）。
     * 返回分配到的 NodePort，0 表示无空闲端口或创建失败。
     */
    @Override
    public int exposePort(String namespace, String podName, int port) {
        String svcName = "dev-" + podName + "-" + port;
        try {
            io.fabric8.kubernetes.api.model.Service old =
                    client.services().inNamespace(namespace).withName(svcName).get();
            if (old != null && !old.getSpec().getPorts().isEmpty()
                    && old.getSpec().getPorts().get(0).getNodePort() != null) {
                return old.getSpec().getPorts().get(0).getNodePort();
            }
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (io.fabric8.kubernetes.api.model.Service svc : client.services().inAnyNamespace().list().getItems()) {
                if (svc.getSpec() == null || svc.getSpec().getPorts() == null) {
                    continue;
                }
                for (io.fabric8.kubernetes.api.model.ServicePort sp : svc.getSpec().getPorts()) {
                    if (sp.getNodePort() != null) {
                        used.add(sp.getNodePort());
                    }
                }
            }
            int chosen = 0;
            synchronized (this) {
                for (int k = 0; k < 19; k++) {
                    int np = 30081 + ((nextNodePort - 30081 + k) % 19);
                    if (!used.contains(np)) {
                        chosen = np;
                        nextNodePort = 30081 + ((np - 30081 + 1) % 19);
                        break;
                    }
                }
            }
            for (int np = chosen > 0 ? chosen : -1; np > 0; np = -1) {
                if (np < 0) {
                    break;
                }
                io.fabric8.kubernetes.api.model.Service svc = new io.fabric8.kubernetes.api.model.ServiceBuilder()
                        .withNewMetadata()
                          .withName(svcName)
                          .withNamespace(namespace)
                          .addToLabels(MANAGED_LABEL, MANAGED_BY)
                        .endMetadata()
                        .withNewSpec()
                          .withType("NodePort")
                          .addToSelector("podname", podName)
                          .addNewPort()
                            .withName("dev")
                            .withProtocol("TCP")
                            .withPort(port)
                            .withTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(port))
                            .withNodePort(np)
                          .endPort()
                        .endSpec()
                        .build();
                client.services().inNamespace(namespace).resource(svc).create();
                log.info("已暴露 {}/{}:{} → NodePort {}", namespace, podName, port, np);
                return np;
            }
            log.warn("NodePort 30081-30099 已用尽，无法暴露 {}/{}:{}", namespace, podName, port);
            return 0;
        } catch (Exception e) {
            log.warn("暴露端口失败 {}/{}:{}：{}", namespace, podName, port, e.getMessage());
            return 0;
        }
    }

    @Override
    public void removePort(String namespace, String podName, int port) {
        try {
            client.services().inNamespace(namespace).withName("dev-" + podName + "-" + port).delete();
        } catch (Exception e) {
            log.warn("撤销端口失败：{}", e.getMessage());
        }
    }

    /**
     * 克隆数据盘：起一个临时 Pod 同时挂源/目标 PVC，cp -a 完整拷贝后销毁。
     * 两个 PVC 都是 local-path（同一节点），可以挂进同一个 Pod。
     */
    @Override
    public boolean cloneData(String namespace, String srcPodName, String dstPodName) {
        String src = dataClaim(srcPodName);
        String dst = dataClaim(dstPodName);
        try {
            if (client.persistentVolumeClaims().inNamespace(namespace).withName(src).get() == null
                    || client.persistentVolumeClaims().inNamespace(namespace).withName(dst).get() == null) {
                log.warn("克隆数据盘失败：源或目标 PVC 不存在 {}/{}", src, dst);
                return false;
            }
            String job = "clone-" + dstPodName;
            client.pods().inNamespace(namespace).withName(job).delete();
            io.fabric8.kubernetes.api.model.Pod pod = new io.fabric8.kubernetes.api.model.PodBuilder()
                    .withNewMetadata().withName(job).withNamespace(namespace)
                      .addToLabels(MANAGED_LABEL, MANAGED_BY).endMetadata()
                    .withNewSpec().withRestartPolicy("Never")
                      .addNewContainer().withName("copy").withImage(image)
                        .withCommand("sh", "-c", "cp -a /src/. /dst/ && echo CLONE_DONE")
                        .addNewVolumeMount().withName("src").withMountPath("/src").endVolumeMount()
                        .addNewVolumeMount().withName("dst").withMountPath("/dst").endVolumeMount()
                      .endContainer()
                      .addNewVolume().withName("src").withNewPersistentVolumeClaim()
                        .withClaimName(src).endPersistentVolumeClaim().endVolume()
                      .addNewVolume().withName("dst").withNewPersistentVolumeClaim()
                        .withClaimName(dst).endPersistentVolumeClaim().endVolume()
                    .endSpec().build();
            client.pods().inNamespace(namespace).resource(pod).create();
            for (int i = 0; i < 90; i++) {
                io.fabric8.kubernetes.api.model.Pod cur =
                        client.pods().inNamespace(namespace).withName(job).get();
                if (cur != null && cur.getStatus() != null) {
                    String phase = String.valueOf(cur.getStatus().getPhase());
                    if ("Succeeded".equals(phase)) {
                        client.pods().inNamespace(namespace).withName(job).delete();
                        log.info("克隆数据盘完成 {} → {}", src, dst);
                        return true;
                    }
                    if ("Failed".equals(phase)) {
                        break;
                    }
                }
                Thread.sleep(1000);
            }
            client.pods().inNamespace(namespace).withName(job).delete();
            log.warn("克隆数据盘超时或失败 {} → {}", src, dst);
            return false;
        } catch (Exception e) {
            log.warn("克隆数据盘异常：{}", e.getMessage());
            return false;
        }
    }

    // ================================================================= 集群实测

    @Override
    public Map<String, Object> clusterInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Node> nodes = client.nodes().list().getItems();
        int podCount = client.pods().inAnyNamespace().list().getItems().size();

        long cpuTotal = 0;
        long memGiTotal = 0;
        long gpuTotal = 0;
        long gpuAllocatable = 0;
        long vramGiTotal = 0;
        for (Node n : nodes) {
            cpuTotal += longQty(n.getStatus().getCapacity(), "cpu");
            memGiTotal += bytesToGi(longQty(n.getStatus().getCapacity(), "memory"));
            gpuTotal += longQty(n.getStatus().getCapacity(), "nvidia.com/gpu");
            gpuAllocatable += longQty(n.getStatus().getAllocatable(), "nvidia.com/gpu");
            String perCard = label(n, "nvidia.com/gpu.memory");
            if (perCard != null && longQty(n.getStatus().getCapacity(), "nvidia.com/gpu") > 0) {
                vramGiTotal += longQty(n.getStatus().getCapacity(), "nvidia.com/gpu") * parseNumber(perCard) / 1024;
            }
        }

        out.put("masterUrl", masterUrl);
        out.put("k8sVersion", clusterVersion);
        out.put("nodeCount", nodes.size());
        out.put("podCount", podCount);
        out.put("cpuTotal", cpuTotal);
        out.put("memGiTotal", memGiTotal);
        out.put("gpuTotal", gpuTotal);
        out.put("gpuAllocatable", gpuAllocatable);
        out.put("vramGiTotal", vramGiTotal);
        out.put("schedulerName", client.pods().inNamespace("volcano-system").list().getItems().isEmpty()
                ? "default-scheduler" : "volcano");
        out.put("sharedResource", sharedResourceName());
        return out;
    }

    @Override
    public List<Map<String, Object>> clusterNodes() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Pod> all = client.pods().inAnyNamespace().list().getItems();
        for (Node n : client.nodes().list().getItems()) {
            String name = n.getMetadata().getName();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("hostname", name);
            m.put("ip", firstAddress(n));
            m.put("status", nodeStatus(n));
            m.put("unschedulable", n.getSpec() != null && Boolean.TRUE.equals(n.getSpec().getUnschedulable()));
            m.put("k8sVersion", n.getStatus().getNodeInfo() == null
                    ? clusterVersion : n.getStatus().getNodeInfo().getKubeletVersion());
            m.put("runtime", n.getStatus().getNodeInfo() == null
                    ? "unknown" : n.getStatus().getNodeInfo().getContainerRuntimeVersion());
            m.put("osImage", n.getStatus().getNodeInfo() == null
                    ? "unknown" : n.getStatus().getNodeInfo().getOsImage());
            m.put("cpuCores", longQty(n.getStatus().getCapacity(), "cpu"));
            m.put("memGb", bytesToGi(longQty(n.getStatus().getCapacity(), "memory")));
            m.put("diskGb", bytesToGi(longQty(n.getStatus().getCapacity(), "ephemeral-storage")));
            m.put("gpuCount", longQty(n.getStatus().getCapacity(), "nvidia.com/gpu"));
            m.put("gpuAllocatable", longQty(n.getStatus().getAllocatable(), "nvidia.com/gpu"));
            m.put("gpuProduct", label(n, "nvidia.com/gpu.product"));
            long pods = all.stream().filter(p -> name.equals(p.getSpec().getNodeName())).count();
            m.put("podCount", pods);
            m.put("labels", flatten(n.getMetadata().getLabels()));
            m.put("taints", taints(n));
            m.put("gpuDriver", label(n, "nvidia.com/gpu.driver-version"));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> discoverGpus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Node n : client.nodes().list().getItems()) {
            long gpus = longQty(n.getStatus().getCapacity(), "nvidia.com/gpu");
            if (gpus <= 0) {
                continue;
            }
            String model = label(n, "nvidia.com/gpu.product");
            if (model == null) {
                model = "未知型号（节点未打 GFD 标签）";
            }
            String memLabel = label(n, "nvidia.com/gpu.memory");
            int memGi = memLabel == null ? 0 : (int) (parseNumber(memLabel) / 1024);
            for (int i = 1; i <= gpus; i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", n.getMetadata().getName());
                m.put("idx", i);
                m.put("model", model);
                m.put("memGb", memGi);
                out.add(m);
            }
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> clusterNamespaces() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var ns : client.namespaces().list().getItems()) {
            String name = ns.getMetadata().getName();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("phase", ns.getStatus() == null ? "Unknown" : ns.getStatus().getPhase());
            m.put("pods", client.pods().inNamespace(name).list().getItems().size());
            ResourceQuota rq = client.resourceQuotas().inNamespace(name).withName("gpu-quota").get();
            if (rq != null) {
                Map<String, Quantity> hard = rq.getSpec() == null ? Map.of() : rq.getSpec().getHard();
                Map<String, Quantity> used = rq.getStatus() == null ? Map.of() : rq.getStatus().getUsed();
                m.put("gpuQuota", parseNumber(qtyText(hard, "requests.nvidia.com/gpu")));
                m.put("gpuUsed", parseNumber(qtyText(used, "requests.nvidia.com/gpu")));
                m.put("podQuota", parseNumber(qtyText(hard, "pods")));
            }
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> componentHealth() {
        List<Map<String, Object>> out = new ArrayList<>();

        // 控制面：apiserver 用一次真实的 /version 往返判定；k3s/kubeadm 的其余组件按 kube-system 里的 Pod 名探测
        long apiStart = System.currentTimeMillis();
        boolean apiserverUp = true;
        try {
            client.getKubernetesVersion();
        } catch (Exception e) {
            apiserverUp = false;
        }
        out.add(probe("kube-apiserver", "control-plane", apiserverUp ? "Healthy" : "Down",
                "6443", apiserverUp ? 1 : 0, 1,
                "直连 " + masterUrl + " 成功，耗时 " + (System.currentTimeMillis() - apiStart) + "ms"));
        boolean k3s = clusterVersion != null && clusterVersion.contains("k3s");
        out.add(k3s
                ? embedded("etcd", "control-plane", "2379")
                : probeNamespacePods("etcd", "control-plane", "kube-system", "component=etcd",
                        nameContains("etcd"), "2379"));
        out.add(k3s
                ? embedded("kube-scheduler", "control-plane", "10259")
                : probeNamespacePods("kube-scheduler", "control-plane", "kube-system", "component=kube-scheduler",
                        nameContains("scheduler"), "10259"));
        out.add(k3s
                ? embedded("controller-manager", "control-plane", "10257")
                : probeNamespacePods("controller-manager", "control-plane", "kube-system",
                        "component=kube-controller-manager", nameContains("controller-manager"), "10257"));
        out.add(probeNamespacePods("coredns", "addon", "kube-system", "k8s-app=kube-dns",
                nameContains("coredns"), "53"));
        out.add(probeNamespacePods("metrics-server", "addon", "kube-system", "k8s-app=metrics-server",
                nameContains("metrics-server"), "4443"));
        out.add(probeNamespacePods("nvidia-gpu-operator", "gpu", "gpu-operator", "app.kubernetes.io/name=gpu-operator",
                nameContains("gpu-operator"), "—"));
        out.add(probeNamespacePods("dcgm-exporter", "gpu", "gpu-operator", "app.kubernetes.io/name=dcgm-exporter",
                nameContains("dcgm"), "9400"));
        out.add(probeNamespacePods("volcano-scheduler", "scheduler", "volcano-system", "app=volcano-scheduler",
                nameContains("volcano"), "—"));
        return out;
    }

    /** k3s 控制面组件内嵌在 server 进程里，没有独立 Pod；如实报 Embedded 而不是 NotFound */
    private Map<String, Object> embedded(String comp, String group, String port) {
        return probe(comp, group, "Embedded", port, 1, 1, "k3s 内嵌于 server 进程，无独立 Pod（apiserver 直连正常视为可用）");
    }

    /** 命名空间存在则按标签取实例数，标签取不到再按名字兜底；命名空间不存在直接报 NotFound */
    private Map<String, Object> probeNamespacePods(String comp, String group, String ns,
                                                   String selector, String nameHint, String port) {
        int ready;
        int desired;
        String status;
        String detail;
        try {
            if (client.namespaces().withName(ns).get() == null) {
                status = "NotFound";
                ready = 0;
                desired = 0;
                detail = "命名空间 " + ns + " 不存在，组件未部署";
            } else {
                List<Pod> byLabel = client.pods().inNamespace(ns).withLabelSelector(selector).list().getItems();
                List<Pod> pods = byLabel;
                if (pods.isEmpty()) {
                    pods = client.pods().inNamespace(ns).list().getItems().stream()
                            .filter(p -> p.getMetadata().getName().contains(nameHint))
                            .toList();
                }
                desired = pods.size();
                ready = (int) pods.stream().filter(this::podReady).count();
                if (desired == 0) {
                    status = "NotFound";
                    detail = ns + " 里找不到匹配的 Pod（选择器 " + selector + "）";
                } else if (ready == desired) {
                    status = "Healthy";
                    detail = ns + " 内 " + ready + "/" + desired + " 实例 Ready";
                } else {
                    status = "Degraded";
                    detail = ns + " 内仅 " + ready + "/" + desired + " 实例 Ready";
                }
            }
        } catch (Exception e) {
            status = "Unknown";
            ready = 0;
            desired = 0;
            detail = "探测失败：" + e.getMessage();
        }
        return probe(comp, group, status, port, ready, desired, detail);
    }

    private static String nameContains(String hint) {
        return hint;
    }

    private static Map<String, Object> probe(String name, String group, String status, String port,
                                             int ready, int desired, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("group", group);
        m.put("status", status);
        m.put("port", port);
        m.put("ready", ready);
        m.put("desired", desired);
        m.put("detail", detail);
        return m;
    }

    private boolean podReady(Pod p) {
        if (p == null || p.getStatus() == null || p.getStatus().getConditions() == null) {
            return false;
        }
        return p.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    // ================================================================= 真下发

    @Override
    public boolean setCordon(String nodeName, boolean unschedulable) {
        try {
            client.nodes().withName(nodeName).edit(n -> new NodeBuilder(n)
                    .editSpec().withUnschedulable(unschedulable).endSpec().build());
            return client.nodes().withName(nodeName).get() != null;
        } catch (Exception e) {
            log.warn("Cordon 节点 {} 失败：{}", nodeName, e.getMessage());
            throw new RentalService.BizException("集群操作失败：" + e.getMessage());
        }
    }

    @Override
    public int evictManagedPods(String nodeName) {
        try {
            List<Pod> pods = client.pods().inAnyNamespace()
                    .withLabel(MANAGED_LABEL, MANAGED_BY)
                    .withField("spec.nodeName", nodeName)
                    .list().getItems();
            if (pods.isEmpty()) {
                return 0;
            }
            for (Pod p : pods) {
                client.pods().inNamespace(p.getMetadata().getNamespace())
                        .withName(p.getMetadata().getName()).delete();
            }
            return pods.size();
        } catch (Exception e) {
            log.warn("驱逐节点 {} 上的托管 Pod 失败：{}", nodeName, e.getMessage());
            throw new RentalService.BizException("驱逐失败：" + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> applyYaml(String yaml) {
        HasMetadata obj;
        try {
            obj = Serialization.unmarshal(yaml, HasMetadata.class);
        } catch (Exception e) {
            throw new RentalService.BizException("YAML 解析失败：" + rootCause(e));
        }
        if (obj == null || obj.getKind() == null) {
            throw new RentalService.BizException("YAML 解析失败：未识别到 kind");
        }
        String ns = obj.getMetadata().getNamespace() == null ? "-" : obj.getMetadata().getNamespace();
        boolean exists;
        try {
            exists = client.resource(obj).get() != null;
        } catch (Exception e) {
            exists = false;
        }
        try {
            client.resource(obj).createOrReplace();
        } catch (Exception e) {
            throw new RentalService.BizException("集群拒绝该对象：" + rootCause(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", obj.getKind());
        out.put("name", obj.getMetadata().getName());
        out.put("namespace", ns);
        out.put("created", !exists);
        out.put("appliedToCluster", true);
        return out;
    }

    @Override
    public boolean deleteObject(String kind, String namespace, String name) {
        try {
            var removed = named(kind, namespace, name).delete();
            return removed != null && !removed.isEmpty();
        } catch (Exception e) {
            log.warn("删除 {}/{}/{} 失败：{}", kind, namespace, name, e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> objectState(String kind, String namespace, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("namespace", namespace);
        out.put("name", name);
        try {
            HasMetadata hm = named(kind, namespace, name).get();
            out.put("exists", hm != null);
            if (hm != null) {
                out.put("createdAt", hm.getMetadata().getCreationTimestamp());
                out.put("phase", phaseOf(kind, namespace, name));
            }
        } catch (Exception e) {
            out.put("exists", false);
            out.put("error", rootCause(e));
        }
        return out;
    }

    /** 返回该对象关联 Pod 的阶段汇总，作为"真实状态" */
    private String phaseOf(String kind, String ns, String name) {
        try {
            List<Pod> pods = "Pod".equals(kind)
                    ? List.of(client.pods().inNamespace(ns).withName(name).get())
                    : client.pods().inNamespace(ns).withLabel("app", name).list().getItems();
            if (pods.isEmpty()) {
                return "NoPods";
            }
            long ready = pods.stream().filter(this::podReady).count();
            return pods.size() + " pod / " + ready + " ready";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** kind → 泛型资源客户端（apiVersion 按常见资源映射，未知资源回退 v1） */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private io.fabric8.kubernetes.client.dsl.MixedOperation<
            io.fabric8.kubernetes.api.model.GenericKubernetesResource,
            io.fabric8.kubernetes.api.model.GenericKubernetesResourceList,
            io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.GenericKubernetesResource>> generic(String kind) {
        String apiVersion = switch (kind == null ? "" : kind) {
            case "Deployment", "StatefulSet", "DaemonSet", "ReplicaSet" -> "apps/v1";
            case "Job", "CronJob" -> "batch/v1";
            case "PodGroup" -> "scheduling.volcano.sh/v1beta1";
            case "Ingress", "NetworkPolicy" -> "networking.k8s.io/v1";
            case "Role", "RoleBinding", "ClusterRole", "ClusterRoleBinding" -> "rbac.authorization.k8s.io/v1";
            case "HorizontalPodAutoscaler" -> "autoscaling/v2";
            default -> "v1";
        };
        return client.genericKubernetesResources(apiVersion, kind);
    }

    /** 集群级对象没有 namespace，直接 withName；命名空间级对象必须 inNamespace */
    private io.fabric8.kubernetes.client.dsl.Resource<io.fabric8.kubernetes.api.model.GenericKubernetesResource>
            named(String kind, String ns, String name) {
        var op = generic(kind);
        return ns == null || ns.isBlank() || ns.equals("-")
                ? op.withName(name)
                : op.inNamespace(ns).withName(name);
    }

    @Override
    public Map<String, Object> createWorkload(Map<String, Object> spec) {
        String kind = String.valueOf(spec.getOrDefault("kind", "Deployment"));
        String ns = String.valueOf(spec.getOrDefault("namespace", "default"));
        String name = String.valueOf(spec.get("name"));
        String img = String.valueOf(spec.getOrDefault("image", image));
        int gpu = ((Number) spec.getOrDefault("gpuCount", 1)).intValue();
        int replicas = ((Number) spec.getOrDefault("replicas", 1)).intValue();
        String policy = String.valueOf(spec.getOrDefault("policy", "binpack"));
        String memLimit = String.valueOf(spec.getOrDefault("memLimit", "whole"));

        ensureNamespace(ns);

        // 调度器：只有集群里真的有 volcano 才写 schedulerName，否则写了也只会让 Pod 永远 Pending
        boolean volcano = !client.pods().inNamespace("volcano-system").list().getItems().isEmpty();
        String scheduler = volcano ? "volcano" : null;

        var res = new LinkedHashMap<String, Quantity>();
        res.put("nvidia.com/gpu", new Quantity(String.valueOf(gpu)));
        String shared = sharedResourceName();
        boolean sharedApplied = false;
        if (!"whole".equals(memLimit) && shared != null) {
            // 1/2 → 一半显存，1/4 → 四分之一；单位由共享插件定义（HAMi 为 MiB）
            res.put(shared, new Quantity(String.valueOf(shareMibOf(memLimit))));
            sharedApplied = true;
        }

        PodBuilder podTemplate = new PodBuilder()
                .withNewMetadata()
                  .withName(name)
                  .withNamespace(ns)
                  .addToLabels("app", name)
                  .addToLabels(MANAGED_LABEL, MANAGED_BY)
                  .addToLabels("rental.policy", policy)
                  .addToAnnotations("scheduling.k8s.io/group-name", name)
                .endMetadata()
                .withNewSpec()
                  .withRestartPolicy("Job".equals(kind) ? "Never" : "Always")
                  .addNewContainer()
                    .withName("gpu")
                    .withImage(img)
                    .withCommand("sleep", "3600")
                    .withNewResources()
                      .withRequests(res)
                      .withLimits(res)
                    .endResources()
                  .endContainer()
                .endSpec();
        if (scheduler != null) {
            podTemplate.editSpec().withSchedulerName(scheduler).endSpec();
        }

        // Pod 模板：控制器模板里不能带 name/namespace，否则集群校验会拒
        Pod template = podTemplate.build();
        template.getMetadata().setName(null);
        template.getMetadata().setNamespace(null);
        io.fabric8.kubernetes.api.model.PodTemplateSpec podTemplateSpec =
                new io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder()
                        .withMetadata(template.getMetadata())
                        .withSpec(template.getSpec())
                        .build();

        HasMetadata obj;
        switch (kind) {
            case "Job" -> obj = new io.fabric8.kubernetes.api.model.batch.v1.JobBuilder()
                    .withNewMetadata().withName(name).withNamespace(ns)
                      .addToLabels("app", name).addToLabels(MANAGED_LABEL, MANAGED_BY).endMetadata()
                    .withNewSpec().withBackoffLimit(0).withTemplate(podTemplateSpec).endSpec()
                    .build();
            case "StatefulSet" -> obj = new io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder()
                    .withNewMetadata().withName(name).withNamespace(ns)
                      .addToLabels("app", name).addToLabels(MANAGED_LABEL, MANAGED_BY).endMetadata()
                    .withNewSpec().withReplicas(replicas).withServiceName(name)
                      .withNewSelector().addToMatchLabels("app", name).endSelector()
                      .withTemplate(podTemplateSpec)
                    .endSpec().build();
            default -> obj = new io.fabric8.kubernetes.api.model.apps.DeploymentBuilder()
                    .withNewMetadata().withName(name).withNamespace(ns)
                      .addToLabels("app", name).addToLabels(MANAGED_LABEL, MANAGED_BY).endMetadata()
                    .withNewSpec().withReplicas(replicas)
                      .withNewSelector().addToMatchLabels("app", name).endSelector()
                      .withTemplate(podTemplateSpec)
                    .endSpec().build();
        }

        try {
            client.resource(obj).inNamespace(ns).createOrReplace();
        } catch (Exception e) {
            throw new RentalService.BizException("集群拒绝创建 " + kind + "：" + rootCause(e));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("namespace", ns);
        out.put("name", name);
        out.put("schedulerName", scheduler == null ? "default-scheduler" : scheduler);
        out.put("gangEnforced", volcano && "gang".equals(policy));
        out.put("sharedEnforced", sharedApplied);
        out.put("requestedGpu", gpu);
        out.put("message", volcano
                ? "已下发到 volcano，gang 成组调度生效"
                : "已下发到集群（default-scheduler）；集群未部署 volcano，Gang/队列/抢占不生效");
        return out;
    }

    @Override
    public boolean deleteWorkload(String kind, String namespace, String name) {
        return deleteObject(kind, namespace, name);
    }

    @Override
    public int restartWorkload(String kind, String namespace, String name) {
        try {
            List<Pod> pods = client.pods().inNamespace(namespace).withLabel("app", name).list().getItems();
            for (Pod p : pods) {
                client.pods().inNamespace(namespace).withName(p.getMetadata().getName()).delete();
            }
            return pods.size();
        } catch (Exception e) {
            throw new RentalService.BizException("重启失败：" + rootCause(e));
        }
    }

    @Override
    public int scaleWorkload(String kind, String namespace, String name, int replicas) {
        try {
            if ("StatefulSet".equals(kind)) {
                client.apps().statefulSets().inNamespace(namespace).withName(name).scale(replicas);
            } else if ("Job".equals(kind)) {
                return 0;
            } else {
                client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas);
            }
            return replicas;
        } catch (Exception e) {
            throw new RentalService.BizException("扩缩容失败：" + rootCause(e));
        }
    }

    @Override
    public String podLogs(String namespace, String podName, int tailLines) {
        try {
            String logText = client.pods().inNamespace(namespace).withName(podName).getLog();
            if (logText == null) {
                return "";
            }
            String[] lines = logText.split("\n");
            int from = Math.max(0, lines.length - Math.max(1, tailLines));
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RentalService.BizException("读取日志失败：" + rootCause(e));
        }
    }

    @Override
    public List<String> podsOf(String namespace, String appLabel) {
        try {
            return client.pods().inNamespace(namespace).withLabel("app", appLabel).list().getItems().stream()
                    .map(p -> p.getMetadata().getName())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void syncResourceQuota(String namespace, int gpuQuota) {
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

    /**
     * 真 join 命令：往 kube-system 写一个 bootstrap token Secret，
     * CA hash 从集群自身的 CA 证书算出来（kubeadm 用 cluster-info，k3s 用 k3s-cluster-i）。
     */
    @Override
    public Map<String, Object> joinCommand() {
        Map<String, Object> out = new LinkedHashMap<>();
        String tokenId = randomHex(6);
        String tokenSecret = randomHex(16);
        try {
            Secret secret = new SecretBuilder()
                    .withNewMetadata().withName("bootstrap-token-" + tokenId).withNamespace("kube-system")
                      .addToLabels("kubernetes.io/bootstrapping", "rbac-defaults").endMetadata()
                    .withType("bootstrap.kubernetes.io/token")
                    .addToStringData("token-id", tokenId)
                    .addToStringData("token-secret", tokenSecret)
                    .addToStringData("description", "gpu-rental 节点接入 " + java.time.LocalDate.now())
                    .addToStringData("expiration-seconds", "86400")
                    .addToStringData("usage-bootstrap-signing", "false")
                    .addToStringData("usage-bootstrap-authentication", "true")
                    .addToData("token-id", base64(tokenId))
                    .addToData("token-secret", base64(tokenSecret))
                    .addToData("usage-bootstrap-authentication", base64("true"))
                    .build();
            client.secrets().inNamespace("kube-system").resource(secret).createOrReplace();

            String host = masterUrl.replaceFirst("^https?://", "").replaceAll("/+$", "");
            String hash = discoveryHash();
            String cmd = "kubeadm join " + host + " \\\n  --token " + tokenId + "." + tokenSecret
                    + (hash == null ? "" : " \\\n  --discovery-token-ca-cert-hash " + hash);
            out.put("command", cmd);
            out.put("token", tokenId + "." + tokenSecret);
            out.put("expiresIn", "24h");
            out.put("note", hash == null
                    ? "bootstrap token 已写入集群 kube-system；CA hash 需在有集群权限的机器上执行 "
                      + "kubeadm token create --print-join-command 获取"
                    : "token 与 CA hash 均由集群实际内容生成，24 小时后过期");
        } catch (Exception e) {
            out.put("command", "kubeadm join " + masterUrl.replaceFirst("^https?://", "")
                    + " --token <在集群上执行 kubeadm token create --print-join-command 获取>");
            out.put("note", "生成真实 token 失败：" + rootCause(e));
        }
        return out;
    }

    private String discoveryHash() {
        try {
            var cm = client.configMaps().inNamespace("kube-public").withName("cluster-info").get();
            if (cm == null) {
                cm = client.configMaps().inNamespace("kube-public").withName("k3s-cluster-i").get();
            }
            if (cm == null) {
                // 1.28+ 的 kube-public/kube-root-ca.crt 里有 CA 证书，可直接算 hash
                cm = client.configMaps().inNamespace("kube-public").withName("kube-root-ca.crt").get();
            }
            if (cm == null || cm.getData() == null) {
                return null;
            }
            byte[] der = null;
            for (String v : cm.getData().values()) {
                if (v == null) {
                    continue;
                }
                der = caDer(v);
                if (der != null) {
                    break;
                }
            }
            if (der == null) {
                return null;
            }
            return "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (Exception e) {
            log.warn("计算 discovery CA hash 失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 cluster-info 内容里取出 CA 证书的 DER 字节。
     * 兼容两种写法：kubeconfig 里的 certificate-authority-data（base64 DER），
     * 以及直接内嵌的 PEM 证书块。都取不到就返回 null，让上层说明"拿不到真实 hash"。
     */
    private static byte[] caDer(String text) {
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("certificate-authority-data:")) {
                try {
                    return Base64.getMimeDecoder().decode(line.substring("certificate-authority-data:".length()).trim());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        int b = text.indexOf("-----BEGIN CERTIFICATE-----");
        int e = text.indexOf("-----END CERTIFICATE-----");
        if (b < 0 || e <= b) {
            return null;
        }
        String body = text.substring(b + "-----BEGIN CERTIFICATE-----".length(), e)
                .replace("\r", "").replace("\n", "").replace(" ", "");
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (Exception ex) {
            return null;
        }
    }

    // ================================================================= 工具

    /** 共享显存资源名：探测集群上真的存在哪种共享插件上报的资源 */
    private String sharedResourceName() {
        for (Node n : client.nodes().list().getItems()) {
            Map<String, Quantity> cap = n.getStatus().getCapacity();
            if (cap == null) {
                continue;
            }
            for (String key : cap.keySet()) {
                String k = key.toLowerCase(Locale.ROOT);
                if (k.contains("vgpu") || k.endsWith("gpumem") || k.contains("gpu-memory")) {
                    return key;
                }
            }
        }
        return null;
    }

    private long shareMibOf(String memLimit) {
        long perCard = 0;
        for (Node n : client.nodes().list().getItems()) {
            String memLabel = label(n, "nvidia.com/gpu.memory");
            if (memLabel != null) {
                perCard = parseNumber(memLabel);
                break;
            }
        }
        if (perCard <= 0) {
            perCard = 32 * 1024;
        }
        return "half".equals(memLimit) ? perCard / 2 : perCard / 4;
    }

    private static String firstAddress(Node n) {
        if (n.getStatus().getAddresses() == null) {
            return "";
        }
        String internal = null;
        for (var a : n.getStatus().getAddresses()) {
            if ("InternalIP".equals(a.getType())) {
                return a.getAddress();
            }
            if ("ExternalIP".equals(a.getType())) {
                internal = a.getAddress();
            }
        }
        return internal == null ? "" : internal;
    }

    private static String nodeStatus(Node n) {
        if (n.getStatus() == null || n.getStatus().getConditions() == null) {
            return "Unknown";
        }
        boolean ready = n.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
        if (!ready) {
            return "NotReady";
        }
        return n.getSpec() != null && Boolean.TRUE.equals(n.getSpec().getUnschedulable())
                ? "Cordoned" : "Ready";
    }

    private static String taints(Node n) {
        if (n.getSpec() == null || n.getSpec().getTaints() == null || n.getSpec().getTaints().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        n.getSpec().getTaints().forEach(t -> sb.append(t.getKey()).append('=')
                .append(t.getValue()).append(':').append(t.getEffect()).append('；'));
        return sb.toString().replaceAll("；$", "");
    }

    private static String flatten(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        labels.forEach((k, v) -> sb.append(k).append('=').append(v).append('；'));
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    private static String label(Node n, String key) {
        if (n.getMetadata().getLabels() == null) {
            return null;
        }
        String v = n.getMetadata().getLabels().get(key);
        return v == null || v.isBlank() ? null : v;
    }

    private static String qtyText(Map<String, Quantity> map, String key) {
        if (map == null) {
            return "0";
        }
        Quantity q = map.get(key);
        return q == null || q.getAmount() == null ? "0" : q.getAmount();
    }

    private static long longQty(Map<String, Quantity> cap, String key) {
        if (cap == null) {
            return 0;
        }
        Quantity q = cap.get(key);
        if (q == null) {
            return 0;
        }
        try {
            return Quantity.getAmountInBytes(q).longValue();
        } catch (Exception e) {
            return parseNumber(q.getAmount());
        }
    }

    private static long bytesToGi(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static long parseNumber(String s) {
        if (s == null || s.isBlank() || s.equals("null")) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
    }

    private static String randomHex(int len) {
        byte[] b = new byte[len / 2 + 1];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(hex(b));
        return sb.length() > len ? sb.substring(0, len) : sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        if (m == null) {
            m = t.getClass().getSimpleName();
        }
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
