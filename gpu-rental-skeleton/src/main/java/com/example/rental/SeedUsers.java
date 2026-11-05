package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 演示账号初始化：给还没有密码的用户写入 BCrypt 哈希。
 *
 * 不把哈希硬编码进 data.sql，原因有两个：
 * 1. BCrypt 每次盐不同，写死反而看不出是怎么来的；
 * 2. 真实项目里初始密码由运维在交付时下发，不该出现在 SQL 里。
 */
@Component
@Order(2) // 在 DbBootstrap 补齐演示账号之后再填密码
public class SeedUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUsers.class);

    /** 演示环境统一初始密码（符合 8 位 + 大小写 + 数字 + 符号策略） */
    public static final String DEFAULT_PASSWORD = "Hly@2026";

    private final Db db;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public SeedUsers(Db db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        int filled = 0;
        for (Map<String, Object> u : db.users()) {
            if (u.get("password_hash") == null) {
                db.setPasswordHash((String) u.get("id"), encoder.encode(DEFAULT_PASSWORD));
                filled++;
            }
        }
        if (filled > 0) {
            log.info("已为 {} 个演示账号初始化密码（初始密码见 README）", filled);
        }
    }
}
