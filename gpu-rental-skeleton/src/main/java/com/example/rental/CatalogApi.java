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
    private final GroupCatalog groupCatalog;

    /** 显卡型号 → 单卡显存（GB），用于商品卡标题「型号 / 显存」 */
    private static final Map<String, Integer> VRAM = Map.ofEntries(
            Map.entry("RTX 2080 Ti", 22), Map.entry("Tesla T4", 16),
            Map.entry("RTX 3090", 24), Map.entry("RTX 3090 Lite", 24),
            Map.entry("RTX 4090", 24), Map.entry("RTX 4090 Lite", 24),
            Map.entry("RTX 4090D", 24), Map.entry("RTX 5090", 32),
            Map.entry("A100-PCIE-40GB", 40), Map.entry("A100-SXM4-40GB", 40),
            Map.entry("RTX 4090-48G", 48), Map.entry("L40S", 48), Map.entry("L20", 48),
            Map.entry("RTX 6000D", 84), Map.entry("A800-NVLINK-80GB", 80),
            Map.entry("H800", 80), Map.entry("RTX 4080", 32),
            Map.entry("RTX 4060 Laptop", 8));

    public CatalogApi(Db db, GroupCatalog groupCatalog) {
        this.db = db;
        this.groupCatalog = groupCatalog;
    }

    /** 首页展示价与下单价同源：参数改了首页立刻跟着变 */
    private double liveUnitPrice() {
        try {
            return Double.parseDouble(db.setting("rental.unit_price").trim());
        } catch (Exception e) {
            return 2.80;
        }
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
        // 5090 是真实可售机型：首页单价与计费同源（rental.unit_price 参数），其余机型为静态市场预览
        models.add(preview("RTX 5090", 32, liveUnitPrice(), "32 GB GDDR7", "16 核",
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

    /** 容器云商品目录（公开）：字段与页面卡片一一对应 */
    /**
     * 创建实例页的地区目录 + 地区能力。
     * 地区取自算力市场在售机型，能力开关走系统参数（系统管理里可改），
     * 不在前端写死「不支持」——客户加了一个区就得改代码。
     */
    @GetMapping("/zones")
    public Map<String, Object> zones() {
        List<Map<String, Object>> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map<String, Object> c : market()) {
            String z = String.valueOf(c.getOrDefault("zone", ""));
            if (!z.isBlank()) {
                seen.add(z);
            }
        }
        for (String z : seen) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zone", z);
            m.put("fileStorage", bool("zone." + z + ".file_storage", true));
            m.put("privateImage", bool("zone." + z + ".private_image", true));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("defaultZone", items.isEmpty() ? "" : items.get(0).get("zone"));
        return out;
    }

    private boolean bool(String key, boolean fallback) {
        try {
            return Boolean.parseBoolean(db.setting(key).trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 创建实例页的基础镜像矩阵：发行版 × CUDA × Python × OS。
     * 社区镜像和我的镜像分别走 /api/images/community 与 /api/images，前端三个 tab 拼一起。
     */
    @GetMapping("/images")
    public List<Map<String, Object>> images() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(family("PyTorch", "#ee4c2c", new String[][]{
                {"2.12.1", "13.0", "3.12", "Ubuntu22", "Devel"},
                {"2.5.1", "12.4", "3.11", "Ubuntu22", "Devel"},
                {"2.4.0", "12.1", "3.10", "Ubuntu22", "Runtime"},
                {"2.1.2", "11.8", "3.9", "Ubuntu20", "Runtime"}}));
        out.add(family("TensorFlow", "#ff6f00", new String[][]{
                {"2.17.0", "12.3", "3.11", "Ubuntu22", "Devel"},
                {"2.13.1", "11.8", "3.10", "Ubuntu20", "Runtime"}}));
        out.add(family("PaddlePaddle", "#06b0e6", new String[][]{
                {"2.6.1", "12.0", "3.11", "Ubuntu22", "Devel"},
                {"2.4.2", "11.6", "3.9", "Ubuntu20", "Runtime"}}));
        out.add(family("ComfyUI", "#d946ef", new String[][]{
                {"0.3.17", "12.4", "3.11", "Ubuntu22", "Devel"}}));
        out.add(family("vLLM", "#6366f1", new String[][]{
                {"0.6.3", "12.4", "3.11", "Ubuntu22", "Devel"},
                {"0.5.4", "12.1", "3.10", "Ubuntu22", "Runtime"}}));
        out.add(family("Miniconda", "#44a434", new String[][]{
                {"24.9", "无", "3.12", "Ubuntu22", "Base"}}));
        out.add(family("Ubuntu", "#e95420", new String[][]{
                {"22.04", "无", "3.10", "Ubuntu22", "Base"},
                {"20.04", "无", "3.8", "Ubuntu20", "Base"}}));
        out.add(family("GROMACS", "#f97316", new String[][]{
                {"2023.2", "无", "3.10", "Ubuntu22", "Base"}}));
        return out;
    }

    private Map<String, Object> family(String name, String color, String[][] versions) {
        List<Map<String, Object>> vs = new ArrayList<>();
        for (String[] v : versions) {
            String cuda = v[1];
            boolean gpuless = "无".equals(cuda);
            String ref = name.toLowerCase() + ":" + v[0]
                    + (gpuless ? "" : "-cuda" + cuda) + "-py" + v[2] + "-" + v[4].toLowerCase();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ref", ref);
            m.put("label", String.join("-", v[0], gpuless ? "" : "CUDA" + cuda, v[4], "Py" + v[2], v[3]).replaceAll("-{2,}", "-"));
            m.put("framework", v[0]);
            m.put("cuda", cuda);
            m.put("python", v[2]);
            m.put("os", v[3]);
            m.put("variant", v[4]);
            m.put("gpuRequired", !gpuless);
            vs.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("color", color);
        out.put("versions", vs);
        return out;
    }

    @GetMapping("/market")
    public List<Map<String, Object>> market() {
        List<Map<String, Object>> out = new ArrayList<>();
        // 本机真实显卡：整机就是这台笔记本，规格取自 agent 实采
        out.add(card("RTX 4060 Laptop", "nvidia", "本机", "GPU", 16, 16, 299, "—", "",
                12, 84, 300, 0.50, false));
        out.add(card("CLOUD CPU2", "amd", "山西1区", "CPU", 30, 80, 30, "50 GB", "",
                11, 70, 300, 0.60, false));
        out.add(card("CLOUD CPU4", "amd", "杭州1区", "CPU", 30, 120, 30, "50 GB", "",
                14.5, 95, 360, 0.62, true));
        out.add(card("CLOUD CPU3", "intel", "杭州1区", "CPU", 20, 80, 30, "50 GB", "2787",
                13, 86, 340, 0.60, false));
        out.add(card("CLOUD CPU", "intel", "海南", "CPU", 12, 42, 30, "50 GB", "1719",
                11, 70, 300, 0.50, false));
        out.add(card("CPU", "amd", "四川", "CPU", 40, 200, 30, "50 GB", "2102",
                16, 120, 390, 0.70, false));
        out.add(card("CPU", "intel", "杭州1区", "CPU", 18, 70, 30, "50 GB", "",
                11, 75, 290, 0.50, true));
        out.add(card("RTX 2080 Ti", "nvidia", "杭州1区", "GPU", 12, 58, 30, "50 GB", "",
                16, 105, 410, 0.70, true));
        out.add(card("Tesla T4", "nvidia", "上海2区", "GPU", 10, 58, 30, "50 GB", "1176",
                11, 75, 308, 0.46, false));
        out.add(card("RTX 3090", "nvidia", "四川", "GPU", 20, 100, 30, "50 GB", "",
                23, 150, 580, 0.99, true));
        out.add(card("RTX 3090", "nvidia", "浙江", "GPU", 18, 60, 30, "50 GB", "",
                23, 150, 580, 0.99, true));
        out.add(card("RTX 3090", "nvidia", "杭州1区", "GPU", 20, 80, 30, "50 GB", "",
                23, 150, 580, 0.99, true));
        out.add(card("RTX 3090", "nvidia", "山西1区", "GPU", 10, 28, 30, "50 GB", "",
                21, 145, 570, 0.88, true));
        out.add(card("RTX 3090 Lite", "nvidia", "浙江", "GPU", 12, 25, 30, "50 GB", "",
                20, 120, 500, 0.88, true));
        out.add(card("RTX 4090", "nvidia", "新疆", "GPU", 15, 100, 30, "50 GB", "",
                35, 230, 880, 1.46, true));
        out.add(card("RTX 4090", "nvidia", "海南", "GPU", 15, 100, 30, "50 GB", "",
                35, 240, 930, 1.46, false));
        out.add(card("RTX 4090", "nvidia", "福建", "GPU", 23, 58, 30, "50 GB", "",
                33, 225, 850, 1.38, true));
        out.add(card("RTX 4090", "nvidia", "山西1区", "GPU", 22, 60, 30, "50 GB", "",
                33, 228, 908, 1.46, true));
        out.add(card("RTX 4090 Lite", "nvidia", "新疆", "GPU", 13, 60, 30, "50 GB", "",
                30, 210, 800, 1.28, false));
        out.add(card("RTX 4090D", "nvidia", "河北1区", "GPU", 22, 100, 30, "50 GB", "",
                29, 200, 800, 1.25, true));
        out.add(card("RTX 4090D", "nvidia", "上海3区", "GPU", 20, 100, 30, "50 GB", "",
                29, 200, 800, 1.25, true));
        out.add(card("RTX 5090", "nvidia", "辽宁", "GPU", 18, 60, 30, "50 GB", "",
                50, 350, 1350, 2.18, true));
        out.add(card("RTX 5090", "nvidia", "安徽", "GPU", 18, 60, 30, "50 GB", "",
                50, 350, 1350, 2.18, true));
        out.add(card("RTX 5090", "nvidia", "四川2区", "GPU", 14, 60, 30, "50 GB", "",
                57, 389, 1536, 2.38, true));
        out.add(card("RTX 5090", "nvidia", "杭州1区", "GPU", 22, 58, 30, "50 GB", "",
                57, 389, 1536, 2.38, true));
        out.add(card("A100-PCIE-40GB", "nvidia", "福建2区", "GPU", 11, 90, 30, "50 GB", "",
                46, 320, 1250, 1.98, true));
        out.add(card("A100-SXM4-40GB", "nvidia", "江苏", "GPU", 30, 100, 30, "50 GB", "",
                55, 370, 1400, 2.39, true));
        out.add(card("RTX 4090-48G", "nvidia", "新疆", "GPU", 14, 90, 30, "50 GB", "",
                47, 320, 1280, 2.08, true));
        out.add(card("RTX 4090-48G", "nvidia", "杭州1区", "GPU", 22, 58, 30, "50 GB", "",
                47, 320, 1280, 2.08, true));
        out.add(card("RTX 4090-48G", "nvidia", "四川2区", "GPU", 14, 100, 30, "50 GB", "",
                50, 350, 1350, 2.18, true));
        out.add(card("RTX 4090-48G", "nvidia", "山西1区", "GPU", 22, 60, 30, "50 GB", "",
                47, 320, 1280, 2.08, true));
        out.add(card("L40S", "nvidia", "上海3区", "GPU", 20, 120, 30, "50 GB", "3121",
                55, 378, 1500, 2.30, false));
        out.add(card("L20", "nvidia", "上海3区", "GPU", 20, 100, 30, "50 GB", "",
                51, 350, 1400, 2.18, true));
        out.add(card("L20", "nvidia", "上海4区", "GPU", 14, 120, 30, "50 GB", "6186",
                51, 350, 1400, 2.18, false));
        out.add(card("RTX 6000D", "nvidia", "杭州1区", "GPU", 26, 110, 30, "50 GB", "",
                135, 935, 3700, 5.80, true));
        out.add(card("A800-NVLINK-80GB", "nvidia", "江苏", "GPU", 30, 120, 30, "50 GB", "",
                160, 1100, 4388, 6.80, true));
        out.add(card("H800", "nvidia", "浙江2区", "GPU", 22, 110, 30, "50 GB", "3545",
                230, 1588, 6300, 9.80, false));
        out.add(card("RTX 4080", "nvidia", "杭州1区", "GPU", 12, 68, 30, "50 GB", "",
                31, 210, 800, 1.38, true));
        return out;
    }

    /**
     * 拼团目录：静态市场数据 + 实时成团进度。
     * done 是「已占坑」总数（演示基数 + 真实拼单），left 是还能拼几张卡，
     * 前端进度条和「再拼 N 张卡即可起飞」都读这里，不再自己算。
     */
    @GetMapping("/group")
    public List<Map<String, Object>> group() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : groupCatalog.cards()) {
            int total = ((Number) c.get("total")).intValue();
            int base = ((Number) c.get("done")).intValue();
            int joined = db.activeJoinsOfCard((String) c.get("cardKey")).stream()
                    .mapToInt(r -> ((Number) r.get("qty")).intValue()).sum();
            c.put("joined", joined);
            c.put("done", Math.min(total, base + joined));
            c.put("left", Math.max(0, total - base - joined));
            out.add(c);
        }
        return out;
    }

    @GetMapping("/baremetal")
    public List<Map<String, Object>> baremetal() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(metal("RTX3090", "重庆", 4200, "24*8 GB", "1 TB", "480G SSD", "48核", "1* 7.68TB NVMe", "AMD EPYC 7513*2"));
        out.add(metal("RTX4090", "浙江", 6300, "24*8 GB", "512 GB", "960 GB SSD *2", "64核", "3.84 TB SATA SSD *2", "Intel Xeon Gold 6530*2"));
        out.add(metal("RTX5090", "新疆", 10000, "32*8 GB", "512 GB", "480 GB SSD *2", "64核", "3.84 TB NVMe SSD *2", "Intel Xeon Gold 6530 *2"));
        out.add(metal("A100", "浙江", 10000, "40*8 GB", "768 GB", "480 GB SSD *2", "48核", "7.68 TB SSD (1.92TB*4)", "Intel Xeon 6248R*2"));
        out.add(metal("A100", "成都", 25000, "80*8 GB", "768 GB", "960 GB SSD *2", "112核", "15.36 TB SSD (7.68TB*2)", "Intel Xeon 8558P *2"));
        out.add(metal("H20", "广东", 34000, "141*8 GB", "2 TB", "960 GB SSD *2", "64核", "15.36 TB NVMe SSD (3.84TB*4)", "Intel Xeon 8358P *2"));
        out.add(metal("A800", "成都", 20000, "80*8 GB", "512 GB", "960 GB SSD *2", "48核", "15.36 TB NVMe SSD (3.84TB*4)", "Intel Xeon 6442Y *2"));
        out.add(metal("H100", "浙江", 55000, "80*8 GB", "2 TB", "960 GB SSD *2", "96核", "15.36 TB NVMe SSD (7.68TB*2)", "Intel Xeon 8468 *2"));
        out.add(metal("H200", "成都", 60000, "150*8 GB", "2 TB", "960 GB SSD *2", "112核", "7.68 TB SSD (3.84TB*2)", "Intel Xeon 8558 *2"));
        out.add(metal("B200", "新疆", 95000, "192*8 GB", "2.25 TB", "1.92 TB SSD *2", "144核", "15.36 TB NVMe SSD (7.68TB*2)", "Intel Xeon 6960P*2"));
        out.add(metal("Ascend 910B", "成都", 22000, "32*8 GB", "2 TB", "480 GB SATA SSD *2", "192核", "7.68 TB SSD (3.84TB*2)", "鲲鹏920-5250*4"));
        return out;
    }

    private Map<String, Object> card(String model, String brand, String zone, String kind, int vcpu, int ram,
                                     int sysDisk, String dataDisk, String dataExpand,
                                     double day, double week, double month, double hourly, boolean soldOut) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("brand", brand);
        m.put("zone", zone);
        m.put("kind", kind);
        m.put("alloc", "1 x " + ("CPU".equals(kind) ? "CPU" : "GPU") + " 分配");
        m.put("vcpu", vcpu);
        m.put("ram", ram);
        m.put("sysDisk", sysDisk);
        m.put("dataDisk", dataDisk);
        m.put("dataExpand", dataExpand == null || dataExpand.isBlank() ? "" : "可扩容 " + dataExpand + " GB");
        m.put("vram", VRAM.getOrDefault(model, 0));
        m.put("cpuBrand", "GPU".equals(kind) ? "intel" : "");
        m.put("day", day);
        m.put("week", week);
        m.put("month", month);
        m.put("hourly", hourly);
        m.put("soldOut", soldOut);
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
