package com.example.rental;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库初始化：只在“表不存在”时建表、只在“空库”时灌演示数据。
 *
 * 为什么要自己做而不是用 spring.sql.init：
 * 那个每次启动都执行脚本，配合 DROP TABLE 会把生产数据清空——
 * 对演示项目无所谓，对企业项目是事故。这里做成幂等：
 *   有表 → 跳过建表；有数据 → 跳过种子。
 */
@Component
public class DbBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbBootstrap.class);

    private final DataSource ds;
    private final JdbcTemplate jdbc;

    public DbBootstrap(DataSource ds, JdbcTemplate jdbc) {
        this.ds = ds;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection c = ds.getConnection()) {
            if (!tableExists(c)) {
                log.info("首次启动：初始化表结构（schema.sql）");
                ScriptUtils.executeSqlScript(c, new ClassPathResource("schema.sql"));
            }

            Integer users = count("users");
            Integer tenants = count("tenants");
            if (users == 0 && tenants == 0) {
                log.info("空库：写入演示数据（data.sql）");
                ScriptUtils.executeSqlScript(c, new ClassPathResource("data.sql"));
            } else {
                log.info("已有数据，跳过种子（users={}，tenants={}）", users, tenants);
            }
        }
    }

    private boolean tableExists(Connection c) {
        try (Statement s = c.createStatement()) {
            s.execute("SELECT 1 FROM users WHERE 1=0");
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private Integer count(String table) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        } catch (Exception e) {
            return 0;
        }
    }
}
