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

    @GetMapping("/group")
    public List<Map<String, Object>> group() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(groupItem("RTX5090", 32, 1280, "NVIDIA", "可用区 A", 8, 2, 16, 64, 480));
        out.add(groupItem("RTX5090", 32, 1280, "NVIDIA", "可用区 B", 8, 5, 16, 64, 480));
        out.add(groupItem("RTX4090", 24, 880, "NVIDIA", "可用区 A", 8, 3, 12, 48, 480));
        out.add(groupItem("RTX4090", 24, 880, "NVIDIA", "可用区 B", 8, 6, 12, 48, 480));
        out.add(groupItem("A100", 80, 3200, "NVIDIA", "可用区 B", 8, 1, 24, 128, 960));
        out.add(groupItem("A100", 40, 2600, "NVIDIA", "可用区 A", 8, 4, 24, 128, 960));
        out.add(groupItem("H800", 80, 3800, "NVIDIA", "可用区 B", 8, 2, 32, 192, 1920));
        out.add(groupItem("L40S", 48, 1600, "NVIDIA", "可用区 A", 8, 7, 16, 96, 960));
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
                                          int total, int done, int vcpu, int ram, int disk) {
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
