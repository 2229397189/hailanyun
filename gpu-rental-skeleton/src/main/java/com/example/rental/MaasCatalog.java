package com.example.rental;

import java.math.BigDecimal;
import java.util.List;

/** MaaS 模型目录：模型名 + 每百万 Token 单价（元）。演示环境为静态目录，真实接入可换成上游模型清单。 */
public final class MaasCatalog {

    public record Model(String id, String display, BigDecimal inPerM, BigDecimal outPerM) {
    }

    public static final List<Model> MODELS = List.of(
            new Model("gpt-4o-mini", "GPT-4o mini（兼容）", new BigDecimal("1.20"), new BigDecimal("4.80")),
            new Model("deepseek-chat", "DeepSeek-V3（兼容）", new BigDecimal("2.00"), new BigDecimal("8.00")),
            new Model("qwen-turbo", "通义千问 Turbo（兼容）", new BigDecimal("2.00"), new BigDecimal("6.00")),
            new Model("glm-4-flash", "智谱 GLM-4-Flash（兼容，限免）", BigDecimal.ZERO, BigDecimal.ZERO));

    public static Model byId(String id) {
        return MODELS.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    private MaasCatalog() {
    }
}
