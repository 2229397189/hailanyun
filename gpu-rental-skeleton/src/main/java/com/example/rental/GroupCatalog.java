package com.example.rental;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 拼团卡目录：机型 / 显存 / 拼团价 / 成团人数这些「市场数据」只在这里定义一次。
 *
 * 为什么要单独抽出来：官网展示（CatalogApi）和下单校验（RentalService）必须同源，
 * 否则页面上写 550 元、后端按别的价扣款。成团进度是动态的，由 group_joins 表叠加，
 * 不放在这里。
 */
@Component
public class GroupCatalog {

    private final List<Map<String, Object>> cards = new ArrayList<>();

    public GroupCatalog() {
        add("RTX3090", 24, 550, "NVIDIA", "AMD 7513", "重庆", 8, 5, 3, 30, 30, "200GB (赠)", "1个月 (固)", "重邮");
        add("RTX4090D", 24, 670, "NVIDIA", "Intel P-8136", "新疆", 8, 2, 7, 60, 30, "200GB (赠)", "1个月 (固)", "复旦");
        add("RTX4090", 24, 800, "NVIDIA", "Intel 6530", "浙江", 8, 6, 7, 60, 30, "800GB (赠)", "1个月 (固)", "浙大");
        add("RTX5090", 32, 1200, "NVIDIA", "Intel 6530", "新疆", 8, 2, 7, 60, 30, "800GB (赠)", "1个月 (固)", "新疆大学");
        add("A100", 40, 1300, "NVIDIA", "Intel 6248R", "浙江", 8, 7, 5, 90, 30, "1500GB (赠)", "1个月 (固)", "浙大");
        add("A100", 80, 3000, "NVIDIA", "Intel 8558P", "成都", 8, 3, 13, 90, 30, "3500GB (赠)", "1个月 (固)", "电子科大");
        add("H20", 141, 3300, "NVIDIA", "Intel 8358P", "广东", 8, 4, 7, 250, 30, "3500GB (赠)", "1个月 (固)", "中山大学");
        add("A800", 80, 2600, "NVIDIA", "Intel 6442Y", "成都", 8, 1, 6, 60, 30, "3500GB (赠)", "1个月 (固)", "电子科大");
        add("H100", 80, 6400, "NVIDIA", "Intel 8468", "浙江", 8, 2, 11, 250, 30, "3500GB (赠)", "1个月 (固)", "浙大");
    }

    /** 卡唯一标识：同型号不同显存 / 不同地区是不同的团（A100 40G 浙江 与 A100 80G 成都） */
    public static String key(String model, int vram, String zone) {
        return model + "|" + vram + "|" + zone;
    }

    /** 每次返回副本：调用方（官网接口）要往上叠加实时成团人数，不能改到目录本身 */
    public List<Map<String, Object>> cards() {
        List<Map<String, Object>> out = new ArrayList<>(cards.size());
        for (Map<String, Object> c : cards) {
            out.add(new LinkedHashMap<>(c));
        }
        return out;
    }

    public Map<String, Object> card(String cardKey) {
        if (cardKey == null) {
            return null;
        }
        for (Map<String, Object> c : cards) {
            if (cardKey.equals(c.get("cardKey"))) {
                return new LinkedHashMap<>(c);
            }
        }
        return null;
    }

    private void add(String model, int vram, int price, String brand, String cpu, String zone,
                     int total, int done, int vcpu, int ram, int disk,
                     String dataDisk, String duration, String school) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cardKey", key(model, vram, zone));
        m.put("model", model);
        m.put("vram", vram);
        m.put("price", price);
        m.put("brand", brand);
        m.put("cpu", cpu);
        m.put("zone", zone);
        m.put("total", total);
        m.put("done", done);
        m.put("vcpu", vcpu);
        m.put("ram", ram);
        m.put("disk", disk);
        m.put("dataDisk", dataDisk);
        m.put("duration", duration);
        m.put("school", school);
        cards.add(m);
    }
}
