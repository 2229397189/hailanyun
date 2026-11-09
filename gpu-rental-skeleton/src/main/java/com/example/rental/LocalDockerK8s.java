package com.example.rental;

import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本机 Docker 运行时：让平台「下单 / 开机 / 关机 / 重启 / 释放」真实作用到本机的 GPU 容器。
 *
 * <p>业界轻量算力平台（控制平面 + 数据平面）的做法：后端通过 Docker Socket / Docker SDK
 * 创建并管理租户容器，用 --gpus / --memory / --cpus 限定资源、端口映射做网络隔离。
 * 这里不引入 docker-java 依赖、也不在镜像里装 docker CLI —— Java 21 原生支持 Unix Domain
 * Socket（JEP 380），直接通过 /var/run/docker.sock 调 Docker Engine HTTP API 即可。
 *
 * <p>为什么不用 real/k3s 模式：在 Docker Desktop(WSL2) 里让 k3s 特权容器再套 containerd、
 * 再由 Pod 二次挂 /dev/dxg，会把 WSL2 引擎搞崩（实测引擎返回 500）。独立容器 --gpus all
 * 则是 Docker 官方支持的 GPU-PV 直通路径，实测 nvidia-smi -L 能识别本机卡。
 *
 * <p>仅在 rental.k8s.mode=local 时装配；默认仍是 fake，不影响既有演示。
 *
 * <p>安全边界：挂载 docker.sock 等价于把宿主机 Docker 控制权交给 app 容器。仅限本地演示，
 * 生产环境应改用独立物理机 + K8s + device-plugin，且不要把平台暴露给不可信用户。
 */
@Component
@ConditionalOnProperty(name = "rental.k8s.mode", havingValue = "local")
public class LocalDockerK8s implements K8sClient {

    private static final Logger log = LoggerFactory.getLogger(LocalDockerK8s.class);

    private static final String LABEL_MANAGED = "gpu-rental.managed";
    private static final String LABEL_NS = "gpu-rental.ns";
    private static final String LABEL_POD = "gpu-rental.pod";
    private static final String VOLUME_PREFIX = "gpu-rental-data-";

    private final ObjectMapper json = new ObjectMapper();
    private final Db db;
    private final String socketPath;
    private final String defaultImage;
    private final String gpuImage;
    private final String wslLibPath;
    private final List<Integer> exposedPorts;
    private final long memoryBytes;
    private final long nanoCpus;
    private final long deleteWaitMs;
    private final DockerHttp http;

    /** exposePort 分配的宿主端口缓存：key= podName + ":" + containerPort */
    private final Map<String, Integer> portCache = new ConcurrentHashMap<>();

    public LocalDockerK8s(Db db,
                          @Value("${rental.k8s.local.socket:/var/run/docker.sock}") String socketPath,
                          @Value("${rental.k8s.local.image:nvidia/cuda:12.4.0-base-ubuntu22.04}") String defaultImage,
                          @Value("${rental.k8s.local.gpu-image:nvidia/cuda:12.4.0-base-ubuntu22.04}") String gpuImage,
                          @Value("${rental.k8s.local.wsl-lib:/usr/lib/wsl}") String wslLibPath,
                          @Value("${rental.k8s.local.expose-ports:22,8888,8080,6006,7860,3000,6080}") String exposePortsCsv,
                          @Value("${rental.k8s.local.memory-mb:4096}") long memoryMb,
                          @Value("${rental.k8s.local.cpus:4}") long cpus,
                          @Value("${rental.k8s.deleteWaitMs:6000}") long deleteWaitMs) {
        this.db = db;
        this.socketPath = socketPath;
        this.defaultImage = defaultImage;
        this.gpuImage = gpuImage;
        this.wslLibPath = wslLibPath;
        this.deleteWaitMs = deleteWaitMs;
        this.memoryBytes = memoryMb * 1024L * 1024L;
        this.nanoCpus = cpus * 1_000_000_000L;
        this.exposedPorts = new ArrayList<>();
        for (String s : exposePortsCsv.split(",")) {
            try {
                int p = Integer.parseInt(s.trim());
                if (p > 0 && p < 65536) exposedPorts.add(p);
            } catch (NumberFormatException ignored) {
                // 配置里写错一个端口不影响其它端口
            }
        }
        this.http = new DockerHttp(socketPath, json);
        log.info("LocalDockerK8s 已启用：socket={} gpuImage={} wslLib={} exposePorts={}",
                socketPath, gpuImage, wslLibPath, exposedPorts);
    }

    // ================================================================ K8sClient

    @Override
    public String create(String namespace, String podName, int gpuCount, String imageRef, int dataDiskGb) {
        return create(namespace, podName, gpuCount, imageRef, dataDiskGb, false);
    }

    @Override
    public String create(String namespace, String podName, int gpuCount, String imageRef,
                         int dataDiskGb, boolean realGpuNode) {
        // 开机 / 重启 / 重置都是「先 delete 再 create」，但为幂等再兜一层：
        // 同名容器残留时直接重建，避免 409 Conflict 把实例卡在 provisioning。
        if (exists(podName)) {
            log.info("容器 {} 已存在，先删除再重建", podName);
            delete(namespace, podName);
        }

        boolean hasGpu = gpuCount > 0;
        String image = resolveImage(imageRef, hasGpu ? gpuImage : defaultImage);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("Image", image);
        spec.put("Cmd", List.of("sleep", "3600"));
        spec.put("Env", List.of(
                "LD_LIBRARY_PATH=/usr/lib/wsl/lib:/usr/lib/x86_64-linux-gnu",
                "NVIDIA_VISIBLE_DEVICES=all",
                "NVIDIA_DRIVER_CAPABILITIES=compute,utility",
                "TOKENARK_IMAGE=" + (imageRef == null ? "" : imageRef)));
        spec.put("Labels", Map.of(
                LABEL_MANAGED, "true",
                LABEL_NS, namespace == null ? "" : namespace,
                LABEL_POD, podName));

        Map<String, Object> host = new LinkedHashMap<>();
        // 与 RealK8s 保持同一语义：只有登记在「真实硬件节点」上的卡才申请 GPU 设备。
        // 台账里的模拟 5090 在 local 模式下仍会起一个真实容器（生命周期是真的），
        // 但不占用本机唯一那张 4060，避免演示时一机多实例把显存抢光。
        boolean requestGpu = hasGpu && realGpuNode;
        boolean needGpuLib = hasGpu;
        if (requestGpu) {
            // 等价 docker run --gpus all：DeviceRequests 由 Docker Desktop 的 GPU-PV 处理。
            // 注意 k3s 那条路是 Pod 二次挂 /dev/dxg，会崩引擎；这里由 Engine 原生注入，安全。
            host.put("DeviceRequests", List.of(Map.of(
                    "Driver", "",
                    "Count", -1,
                    "Capabilities", List.of(List.of("gpu")))));
        }
        List<String> binds = new ArrayList<>();
        if (needGpuLib && wslLibPath != null && !wslLibPath.isBlank()) {
            // WSL2 的 CUDA 驱动库在 Windows 驱动包里，由 Docker Desktop 挂到 /usr/lib/wsl。
            // 容器内 nvidia-smi / CUDA 需要这些 .so，实测缺了就报 "no devices were found"。
            binds.add(wslLibPath + ":" + wslLibPath + ":ro");
        }
        if (dataDiskGb > 0) {
            ensureVolume(volumeName(podName));
            binds.add(volumeName(podName) + ":/data");
        }
        if (!binds.isEmpty()) host.put("Binds", binds);
        host.put("Memory", memoryBytes);
        host.put("NanoCpus", nanoCpus);
        host.put("RestartPolicy", Map.of("Name", "no"));

        // Docker 不支持给运行中的容器追加端口映射，所以创建时就把常用端口预映射好，
        // HostPort 留空由 Engine 自动分配；exposePort() 再回查实际分到的宿主端口。
        Map<String, Object> exposed = new LinkedHashMap<>();
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (int p : exposedPorts) {
            exposed.put(p + "/tcp", Map.of());
            bindings.put(p + "/tcp", List.of(Map.of("HostPort", "")));
        }
        spec.put("ExposedPorts", exposed);
        host.put("PortBindings", bindings);
        spec.put("HostConfig", host);

        try {
            String path = "/containers/create?name=" + enc(podName);
            Resp r = http.call("POST", path, json.writeValueAsString(spec));
            if (r.status >= 300) {
                throw new IllegalStateException("创建容器失败(" + r.status + ")：" + r.body);
            }
            String id = (String) json.readValue(r.body, Map.class).get("Id");
            Resp started = http.call("POST", "/containers/" + enc(id) + "/start", "");
            // 204 = 正常启动；304 = 已经启动过
            if (started.status >= 300 && started.status != 304) {
                throw new IllegalStateException("启动容器失败(" + started.status + ")：" + started.body);
            }
            portCache.entrySet().removeIf(e -> e.getKey().startsWith(podName + ":"));
            log.info("已创建本机 GPU 容器 {}（id={} gpu={} image={} dataDisk={}GB）",
                    podName, id == null ? "?" : id.substring(0, Math.min(12, id.length())),
                    gpuCount, image, dataDiskGb);
            return namespace + "/" + podName;
        } catch (Exception e) {
            // 让 RentalService 的补偿逻辑（退款 + 还卡 + 订单作废）正常触发
            throw new IllegalStateException("本机容器创建失败：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean ready(String namespace, String podName) {
        try {
            Resp r = http.call("GET", "/containers/" + enc(podName) + "/json", null);
            if (r.status != 200) return false;
            Map<?, ?> inspect = json.readValue(r.body, Map.class);
            Object state = inspect.get("State");
            return state instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("Running"));
        } catch (Exception e) {
            log.debug("检查容器 {} 就绪失败：{}", podName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean delete(String namespace, String podName) {
        if (!exists(podName)) return true;
        try {
            // force=true 相当于 docker rm -f，不等 10 秒优雅退出
            http.call("DELETE", "/containers/" + enc(podName) + "?force=true", null);
        } catch (Exception e) {
            log.warn("删除容器 {} 失败：{}", podName, e.getMessage());
        }
        long deadline = System.currentTimeMillis() + deleteWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (!exists(podName)) {
                portCache.entrySet().removeIf(x -> x.getKey().startsWith(podName + ":"));
                log.info("已删除本机容器 {}", podName);
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("容器 {} 删除超时（仍在），返回 false 让上层重试", podName);
        return false;
    }

    @Override
    public void deleteData(String namespace, String podName) {
        try {
            http.call("DELETE", "/volumes/" + enc(volumeName(podName)), null);
            log.info("已删除数据盘 {}", volumeName(podName));
        } catch (Exception e) {
            // 数据盘可能本来就没创建（dataDiskGb=0），不算错误
            log.debug("删除数据盘 {} 失败（可能不存在）：{}", podName, e.getMessage());
        }
    }

    @Override
    public String mode() {
        return "local";
    }

    @Override
    public List<String> listManagedPods() {
        List<String> out = new ArrayList<>();
        try {
            Resp r = http.call("GET", "/containers/json?all=1", null);
            if (r.status != 200) return out;
            List<?> containers = json.readValue(r.body, List.class);
            for (Object o : containers) {
                if (!(o instanceof Map<?, ?> c)) continue;
                Object names = c.get("Names");
                if (!(names instanceof List<?> ns)) continue;
                boolean managed = false;
                for (Object n : ns) {
                    if (String.valueOf(n).replaceFirst("^/", "").startsWith("gpu-pod-")) {
                        managed = true;
                        break;
                    }
                }
                if (!managed) continue;
                Object labels = c.get("Labels");
                String namespace = "";
                String pod = "";
                if (labels instanceof Map<?, ?> l) {
                    Object nsVal = l.get(LABEL_NS);
                    Object podVal = l.get(LABEL_POD);
                    namespace = nsVal == null ? "" : String.valueOf(nsVal);
                    pod = podVal == null ? "" : String.valueOf(podVal);
                }
                if (pod.isBlank() && !ns.isEmpty()) {
                    pod = String.valueOf(ns.get(0)).replaceFirst("^/", "");
                }
                out.add(namespace + "/" + pod);
            }
        } catch (Exception e) {
            log.warn("列出本机托管容器失败：{}", e.getMessage());
        }
        return out;
    }

    @Override
    public void createTenantNamespace(String namespace, int gpuQuota) {
        // 本机模式没有 K8s Namespace 概念；租户隔离靠容器 label + 端口映射，
        // 配额仍由业务层 Db.occupiedGpusOfTenant 强制校验，这里不下发任何东西。
        log.info("本机模式：租户 {} 配额 {} 卡由业务层校验，无需下发 Namespace", namespace, gpuQuota);
    }

    @Override
    public String exec(String namespace, String podName, String... command) {
        if (!exists(podName)) return "";
        try {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("AttachStdout", true);
            create.put("AttachStderr", true);
            // Tty=true 让输出是裸 stdout，避免 Docker 的 8 字节多路复用帧头污染返回值
            create.put("Tty", true);
            create.put("Cmd", command == null || command.length == 0
                    ? List.of("sh", "-c", "echo ok") : Arrays.asList(command));
            Resp c = http.call("POST", "/containers/" + enc(podName) + "/exec",
                    json.writeValueAsString(create));
            if (c.status >= 300) return "";
            String execId = String.valueOf(json.readValue(c.body, Map.class).get("Id"));
            Resp started = http.call("POST", "/exec/" + enc(execId) + "/start",
                    "{\"Detach\":false,\"Tty\":true}");
            return started.status < 300 ? started.body : "";
        } catch (Exception e) {
            log.warn("在容器 {} 执行命令失败：{}", podName, e.getMessage());
            return "";
        }
    }

    @Override
    public void execDetached(String namespace, String podName, String... command) {
        if (!exists(podName)) return;
        try {
            Map<String, Object> create = new LinkedHashMap<>();
            create.put("AttachStdout", false);
            create.put("AttachStderr", false);
            create.put("Cmd", command == null || command.length == 0
                    ? List.of("sh", "-c", "echo ok") : Arrays.asList(command));
            Resp c = http.call("POST", "/containers/" + enc(podName) + "/exec",
                    json.writeValueAsString(create));
            if (c.status >= 300) return;
            String execId = String.valueOf(json.readValue(c.body, Map.class).get("Id"));
            // Detach:true 让进程在容器内独立存活，不被 exec 会话回收
            http.call("POST", "/exec/" + enc(execId) + "/start", "{\"Detach\":true}");
        } catch (Exception e) {
            log.warn("在容器 {} 后台启动命令失败：{}", podName, e.getMessage());
        }
    }

    @Override
    public int exposePort(String namespace, String podName, int port) {
        String key = podName + ":" + port;
        Integer cached = portCache.get(key);
        if (cached != null) return cached;
        if (!exposedPorts.contains(port)) {
            log.warn("端口 {} 不在预映射列表 {} 中，无法暴露", port, exposedPorts);
            return 0;
        }
        try {
            Resp r = http.call("GET", "/containers/" + enc(podName) + "/json", null);
            if (r.status != 200) return 0;
            Map<?, ?> inspect = json.readValue(r.body, Map.class);
            Object net = inspect.get("NetworkSettings");
            if (!(net instanceof Map<?, ?> n)) return 0;
            Object ports = n.get("Ports");
            if (!(ports instanceof Map<?, ?> p)) return 0;
            Object binding = p.get(port + "/tcp");
            if (binding instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> m && m.get("HostPort") != null) {
                int hostPort = Integer.parseInt(String.valueOf(m.get("HostPort")));
                portCache.put(key, hostPort);
                return hostPort;
            }
        } catch (Exception e) {
            log.warn("查询容器 {} 端口映射失败：{}", podName, e.getMessage());
        }
        return 0;
    }

    @Override
    public void removePort(String namespace, String podName, int port) {
        // Docker 无法撤销运行中容器的端口绑定；只清缓存，容器删除时映射随之消失。
        portCache.remove(podName + ":" + port);
    }

    /**
     * 本机 Docker 模式扩容数据盘。
     * Docker local volume 本身没有硬容量配额（与 K8s PVC 不同），所以这里做两件真实可验证的事：
     * ① 确认卷存在且容器在运行；② 在容器内 /data 写入容量标记文件，供用户核对。
     * 返回 true 只代表「运行时确认卷可用、容量已登记」，不谎称做了块设备级配额。
     */
    @Override
    public boolean expandData(String namespace, String podName, int fromGb, int toGb) {
        if (!exists(podName)) return false;
        String vol = volumeName(podName);
        try {
            Resp r = http.call("GET", "/volumes/" + enc(vol), null);
            if (r.status != 200) return false;
            // 容器内写入容量标记：用户 exec 进容器 cat 能核对，不是只在数据库里改个数
            String out = exec(namespace, podName, "sh", "-c",
                    "mkdir -p /data && printf '%s' '" + toGb
                            + "' > /data/.tokenark_capacity_gb && cat /data/.tokenark_capacity_gb");
            boolean confirmed = out != null && out.trim().contains(String.valueOf(toGb));
            if (confirmed) {
                log.info("本机数据盘 {} 已登记 {}GB -> {}GB（Docker 卷无硬配额，容量以台账计费）",
                        vol, fromGb, toGb);
            }
            return confirmed;
        } catch (Exception e) {
            log.warn("本机数据盘扩容失败 {}：{}", podName, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean cloneData(String namespace, String srcPodName, String dstPodName) {
        String srcVol = volumeName(srcPodName);
        String dstVol = volumeName(dstPodName);
        ensureVolume(dstVol);
        String tmp = "gpu-clone-" + Long.toHexString(System.nanoTime());
        try {
            Map<String, Object> host = new LinkedHashMap<>();
            host.put("Binds", List.of(srcVol + ":/src:ro", dstVol + ":/dst"));
            host.put("AutoRemove", false);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("Image", defaultImage);
            spec.put("Cmd", List.of("sh", "-c", "cp -a /src/. /dst/ 2>/dev/null; exit 0"));
            spec.put("HostConfig", host);
            Resp c = http.call("POST", "/containers/create?name=" + enc(tmp),
                    json.writeValueAsString(spec));
            if (c.status >= 300) return false;
            String id = String.valueOf(json.readValue(c.body, Map.class).get("Id"));
            http.call("POST", "/containers/" + enc(id) + "/start", "");
            Resp waited = http.call("POST", "/containers/" + enc(id) + "/wait", "");
            int exit = 0;
            if (waited.status == 200) {
                Object code = json.readValue(waited.body, Map.class).get("StatusCode");
                exit = code instanceof Number n ? n.intValue() : 1;
            } else {
                exit = 1;
            }
            return exit == 0;
        } catch (Exception e) {
            log.warn("克隆数据盘 {} -> {} 失败：{}", srcPodName, dstPodName, e.getMessage());
            return false;
        } finally {
            try {
                http.call("DELETE", "/containers/" + enc(tmp) + "?force=true", null);
            } catch (Exception ignored) {
                // 临时容器清理失败不影响主流程
            }
        }
    }

    /**
     * 回收前存档：把容器文件系统提交为镜像 repo:auto（A-7 checkpoint）。
     * 注意 Docker commit 不包含挂载卷内容，调用方需先把 /data 打包进容器再提交。
     */
    @Override
    public String snapshot(String namespace, String podName, String repo) {
        if (!exists(podName)) return "";
        try {
            String path = "/commit?container=" + enc(podName) + "&repo=" + enc(repo) + "&tag=auto";
            Resp r = http.call("POST", path, "");
            if (r.status >= 300) {
                log.warn("容器 {} 存档失败({})：{}", podName, r.status, r.body);
                return "";
            }
            String ref = repo + ":auto";
            log.info("已把容器 {} 存档为镜像 {}", podName, ref);
            return ref;
        } catch (Exception e) {
            log.warn("容器 {} 存档异常：{}", podName, e.getMessage());
            return "";
        }
    }

    /**
     * 保存镜像：commit 容器为 repo:tag，再 push 到本地私有仓库，返回镜像引用。
     * 这样保存的镜像能被后续实例直接拉起（A-16 save-image → registry → 跨实例）。
     */
    @Override
    public String commitAndPush(String namespace, String podName, String repo, String tag) {
        if (!exists(podName)) return "";
        try {
            Resp c = http.call("POST", "/commit?container=" + enc(podName)
                    + "&repo=" + enc(repo) + "&tag=" + enc(tag), "");
            if (c.status >= 300) {
                log.warn("镜像 commit 失败({})：{}", c.status, c.body);
                return "";
            }
            String ref = repo + ":" + tag;
            // 本地 insecure 仓库：空账号 auth 头（Docker Engine push 必须带 X-Registry-Auth）
            String auth = java.util.Base64.getEncoder().encodeToString(
                    "{\"username\":\"\",\"password\":\"\",\"auth\":\"\"}"
                            .getBytes(StandardCharsets.UTF_8));
            Resp p = http.call("POST", "/images/" + repo + "/push?tag=" + enc(tag), "",
                    Map.of("X-Registry-Auth", auth));
            if (p.status >= 300) {
                log.warn("镜像 push 失败({})：{}", p.status, p.body);
                return "";
            }
            log.info("已保存并推送镜像 {}", ref);
            return ref;
        } catch (Exception e) {
            log.warn("保存镜像失败：{}", e.getMessage());
            return "";
        }
    }

    // ================================================================ 内部辅助

    private String resolveImage(String imageRef, String fallback) {
        if (imageRef == null || imageRef.isBlank()) return fallback;
        // 已保存镜像的完整引用（如 localhost:5000/tk/txxx:img-yyy）直接使用，
        // 不再走 image.map 回落，否则「存镜像后拉起」会悄悄用回基础镜像（A-16）
        if (imageRef.contains("/") || imageRef.contains(":")) {
            return imageRef;
        }
        try {
            String map = db.setting("image.map");
            if (map != null && !map.isBlank()) {
                Map<?, ?> m = json.readValue(map, Map.class);
                Object hit = m.get(imageRef);
                if (hit != null && !String.valueOf(hit).isBlank()) return String.valueOf(hit);
            }
        } catch (Exception e) {
            log.warn("解析 image.map 失败（{}），回落默认镜像", e.getMessage());
        }
        // 和 RealK8s 保持同一语义：没配映射就不假装「选的镜像生效」，显式告警后回落
        log.warn("镜像 {} 未在 image.map 配置映射，回落 {}", imageRef, fallback);
        return fallback;
    }

    private boolean exists(String name) {
        try {
            Resp r = http.call("GET", "/containers/" + enc(name) + "/json", null);
            return r.status == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureVolume(String name) {
        try {
            Resp r = http.call("POST", "/volumes/create",
                    json.writeValueAsString(Map.of("Name", name,
                            "Labels", Map.of(LABEL_MANAGED, "true"))));
            // 同名卷已存在时 Engine 返回 500 或 409，都视为已就绪
            if (r.status >= 300 && r.status != 409) {
                log.debug("创建数据卷 {} 返回 {}（可能已存在）：{}", name, r.status, r.body);
            }
        } catch (Exception e) {
            log.debug("创建数据卷 {} 异常（可能已存在）：{}", name, e.getMessage());
        }
    }

    private static String volumeName(String podName) {
        return VOLUME_PREFIX + podName;
    }

    private static String enc(String s) {
        // 容器名/卷名都是平台自己生成的 ASCII 标识（gpu-pod-xxx / tenant-tX），
        // 只需把空格等少数字符编码，避免拼出非法 URI
        return s == null ? "" : s.replace(" ", "%20").replace("#", "%23").replace("?", "%3F");
    }

    // ================================================================ 极简 Docker Engine HTTP 客户端

    /** HTTP 响应：状态码 + 正文（JSON 或裸 stdout） */
    private static final class Resp {
        final int status;
        final String body;

        Resp(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 通过 Unix Domain Socket 发 HTTP/1.1 请求。
     * Docker Engine API 就是跑在 /var/run/docker.sock 上的 HTTP 服务，用 Connection: close
     * 让服务端发完就断，读流到 EOF 即可，不必维护连接池。
     */
    private static final class DockerHttp {
        private final String socketPath;
        private final ObjectMapper json;

        DockerHttp(String socketPath, ObjectMapper json) {
            this.socketPath = socketPath;
            this.json = json;
        }

        Resp call(String method, String path, String body) throws Exception {
            return call(method, path, body, null);
        }

        Resp call(String method, String path, String body, Map<String, String> extraHeaders) throws Exception {
            byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                ch.connect(UnixDomainSocketAddress.of(socketPath));
                StringBuilder req = new StringBuilder();
                req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                        .append("Host: localhost\r\n")
                        .append("Content-Type: application/json\r\n")
                        .append("Content-Length: ").append(payload.length).append("\r\n");
                if (extraHeaders != null) {
                    for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                        req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                    }
                }
                req.append("Connection: close\r\n\r\n");
                writeFully(ch, req.toString().getBytes(StandardCharsets.US_ASCII));
                if (payload.length > 0) writeFully(ch, payload);

                byte[] raw = readAll(ch);
                int sep = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                if (sep < 0) throw new IllegalStateException("Docker Engine 响应无 HTTP 头");
                String head = new String(raw, 0, sep, StandardCharsets.US_ASCII);
                int status = parseStatus(head);
                byte[] data = Arrays.copyOfRange(raw, sep + 4, raw.length);
                boolean chunked = head.toLowerCase().contains("transfer-encoding: chunked");
                String text = new String(chunked ? decodeChunked(data) : data, StandardCharsets.UTF_8);
                return new Resp(status, text);
            }
        }

        private int parseStatus(String head) {
            String first = head.split("\r\n")[0];
            String[] parts = first.split(" ");
            return parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
        }

        private static void writeFully(SocketChannel ch, byte[] bytes) throws Exception {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) ch.write(buf);
        }

        private static byte[] readAll(SocketChannel ch) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteBuffer buf = ByteBuffer.allocate(8192);
            int n;
            while ((n = ch.read(buf)) != -1) {
                if (n == 0) continue;
                buf.flip();
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                out.write(chunk);
                buf.clear();
            }
            return out.toByteArray();
        }

        /** 解析 HTTP chunked 编码：每行是十六进制长度，随后是该长度的正文，以 0 结束 */
        private static byte[] decodeChunked(byte[] src) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int pos = 0;
            while (pos < src.length) {
                int eol = indexOf(src, "\r\n".getBytes(StandardCharsets.US_ASCII), pos);
                if (eol < 0) break;
                String line = new String(src, pos, eol - pos, StandardCharsets.US_ASCII).trim();
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                int len;
                try {
                    len = Integer.parseInt(line.trim(), 16);
                } catch (NumberFormatException e) {
                    break;
                }
                if (len == 0) break;
                int start = eol + 2;
                if (start + len > src.length) break;
                out.write(src, start, len);
                pos = start + len + 2; // 跳过块尾 \r\n
            }
            return out.toByteArray();
        }

        private static int indexOf(byte[] haystack, byte[] needle) {
            return indexOf(haystack, needle, 0);
        }

        private static int indexOf(byte[] haystack, byte[] needle, int from) {
            outer:
            for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
                for (int j = 0; j < needle.length; j++) {
                    if (haystack[i + j] != needle[j]) continue outer;
                }
                return i;
            }
            return -1;
        }
    }
}
