package com.example.rental;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 市场商品目录（拼团 / 裸金属）。
 *
 * 为什么单独放一个 Controller：这两类商品目前没有落库，但也不该写死在 HTML 里。
 * 先把目录收敛到服务端一处，后续接真实库存只改这里，前端不动。
 * 路径放在 /api/catalog/**，在 WebConfig 里放行，官网未登录也能看。
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogApi {

    private final Db db;

    public CatalogApi(Db db) {
        this.db = db;
    }

    /**
     * 官网首页数据：模型镜像 + 机型预览 + 平台实时统计。
     * 放在 catalog 下是因为它属于「未登录也能看的公开目录」，
     * 首页不再写死任何业务数字。
     */
    @GetMapping("/home")
    public Map<String, Object> home() {
        Map<String, Object> out = new LinkedHashMap<>();

        List<Map<String, Object>> mirrors = new ArrayList<>();
        mirrors.add(mirror("ComfyUI: Wan2.2", "v8.9 · 视频生成", "#f59e0b", "▣"));
        mirrors.add(mirror("PyTorch Devel", "2.10 + CUDA 12.9 + Py 3.12", "#22c55e", "◈"));
        mirrors.add(mirror("GROMACS_2023.2", "2026 · 分子动力学", "#f97316", "✳"));
        mirrors.add(mirror("ComfyUI: Z-Image", "Z-Image · 图像生成", "#d946ef", "✦"));
        mirrors.add(mirror("DeepSeek:32B", "32B · Ollama · 推理", "#3b82f6", "◎"));
        mirrors.add(mirror("ComfyUI: Qwen 2512", "图像 · 文本生成", "#14b8a6", "▤"));
        mirrors.add(mirror("Isaac Sim + Lab", "5.11 · 2.3.0 · 机器人仿真", "#10b981", "◉"));
        mirrors.add(mirror("Unsloth: Qwen3.6", "模型微调 · 低显存", "#a855f7", "◐"));
        mirrors.add(mirror("vLLM", "0.6 · 推理加速", "#6366f1", "⚡"));
        mirrors.add(mirror("Ollama", "0.3 · 本地推理", "#0ea5e9", "◍"));
        mirrors.add(mirror("Llama 3.1", "8B / 70B · 微调", "#2563eb", "▦"));
        mirrors.add(mirror("Qwen2.5", "72B · 通义千问", "#8b5cf6", "◇"));
        mirrors.add(mirror("Stable Diffusion", "WebUI · 出图", "#ec4899", "✿"));
        mirrors.add(mirror("JupyterLab", "4.2 · 交互开发", "#f43f5e", "▥"));
        mirrors.add(mirror("PaddlePaddle", "2.6 · 飞桨", "#0891b2", "▧"));
        mirrors.add(mirror("ModelScope", "魔搭 · 模型库", "#7c3aed", "◈"));
        out.put("mirrors", mirrors);

        List<Map<String, Object>> models = new ArrayList<>();
        models.add(preview("RTX 5090", 32, 2.80, "32 GB GDDR7", "16 核",
                "AMD EPYC 7513*2", "64 GB", "50G SSD", "200G NVMe", "可用区 A", 4));
        models.add(preview("RTX 4090", 24, 2.20, "24 GB GDDR6X", "12 核",
                "Intel Xeon Gold 6530*2", "48 GB", "480G SSD", "1TB NVMe", "可用区 B", 6));
        models.add(preview("A100", 80, 9.80, "80 GB HBM2e", "24 核",
                "AMD EPYC 7543*2", "128 GB", "480G SSD", "1TB NVMe", "可用区 A", 2));
        out.put("models", models);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalGpus", db.q("SELECT COUNT(*) AS c FROM gpus").get(0).get("c"));
        stats.put("freeGpus", db.q("SELECT COUNT(*) AS c FROM gpus WHERE status='free'").get(0).get("c"));
        stats.put("nodes", db.q("SELECT COUNT(*) AS c FROM nodes").get(0).get("c"));
        stats.put("runningInstances", db.q("SELECT COUNT(*) AS c FROM instances WHERE status='running'")
                .get(0).get("c"));
        stats.put("serverTime", java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        out.put("stats", stats);
        return out;
    }

    private Map<String, Object> mirror(String name, String tag, String color, String icon) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("tag", tag);
        m.put("color", color);
        m.put("icon", icon);
        return m;
    }

    private Map<String, Object> preview(String model, int vram, double hourly, String vramText, String cores,
                                        String cpu, String ram, String sysDisk, String dataDisk,
                                        String zone, int stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("vram", vram);
        m.put("vramText", vramText);
        m.put("hourly", hourly);
        m.put("cores", cores);
        m.put("cpu", cpu);
        m.put("ram", ram);
        m.put("sysDisk", sysDisk);
        m.put("dataDisk", dataDisk);
        m.put("zone", zone);
        m.put("stock", stock);
        return m;
    }

    @GetMapping("/group")
    public List<Map<String, Object>> group() {
        List<Map<String, Object>> out = new ArrayList<>();
        String epyc = "AMD EPYC 7513*2", xeon = "Intel Xeon Gold 6530*2";
        out.add(groupItem("RTX5090", 32, 1280, "NVIDIA", "可用区 A", 8, 2, 16, 64, 480, epyc, "1TB NVMe"));
        out.add(groupItem("RTX5090", 32, 1280, "NVIDIA", "可用区 B", 8, 5, 16, 64, 480, epyc, "1TB NVMe"));
        out.add(groupItem("RTX4090", 24, 880, "NVIDIA", "可用区 A", 8, 3, 12, 48, 480, xeon, "1TB NVMe"));
        out.add(groupItem("RTX4090", 24, 880, "NVIDIA", "可用区 B", 8, 6, 12, 48, 480, xeon, "1TB NVMe"));
        out.add(groupItem("A100", 80, 3200, "NVIDIA", "可用区 B", 8, 1, 24, 128, 960, "AMD EPYC 7543*2", "1.92TB NVMe"));
        out.add(groupItem("A100", 40, 2600, "NVIDIA", "可用区 A", 8, 4, 24, 128, 960, "AMD EPYC 7543*2", "1.92TB NVMe"));
        out.add(groupItem("H800", 80, 3800, "NVIDIA", "可用区 B", 8, 2, 32, 192, 1920, xeon, "3.84TB NVMe"));
        out.add(groupItem("L40S", 48, 1600, "NVIDIA", "可用区 A", 8, 7, 16, 96, 960, epyc, "960G NVMe"));
        return out;
    }

    @GetMapping("/baremetal")
    public List<Map<String, Object>> baremetal() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(metal("RTX5090", "可用区 A", 10000, "32 GB GDDR7", "512 GB", "480 GB SSD *2",
                "64 核", "3.84 TB NVMe SSD *2", "Intel Xeon Gold 6530 *2"));
        out.add(metal("RTX5090", "可用区 B", 10000, "32 GB GDDR7", "512 GB", "480 GB SSD *2",
                "64 核", "3.84 TB NVMe SSD *2", "Intel Xeon Gold 6530 *2"));
        out.add(metal("A100", "可用区 B", 10000, "40 GB HBM2", "768 GB", "480 GB SSD *2",
                "48 核", "7.68 TB SSD (1.92TB*4)", "Intel Xeon Gold 6248R *2"));
        out.add(metal("RTX4090", "可用区 A", 6300, "24 GB GDDR6X", "512 GB", "960 GB SSD *2",
                "64 核", "3.84 TB SATA SSD *2", "Intel Xeon Gold 6530 *2"));
        return out;
    }

    private Map<String, Object> groupItem(String model, int vram, int price, String brand, String zone,
                                          int total, int done, int vcpu, int ram, int disk,
                                          String cpu, String dataDisk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("vram", vram);
        m.put("price", price);
        m.put("brand", brand);
        m.put("zone", zone);
        m.put("total", total);
        m.put("done", done);
        m.put("vcpu", vcpu);
        m.put("ram", ram);
        m.put("disk", disk);
        m.put("cpu", cpu);
        m.put("dataDisk", dataDisk);
        return m;
    }

    private Map<String, Object> metal(String model, String zone, int price, String vram, String ram,
                                      String sysDisk, String cores, String dataDisk, String cpu) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("zone", zone);
        m.put("price", price);
        m.put("vram", vram);
        m.put("ram", ram);
        m.put("sysDisk", sysDisk);
        m.put("cores", cores);
        m.put("dataDisk", dataDisk);
        m.put("cpu", cpu);
        return m;
    }
}
