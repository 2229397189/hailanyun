package com.example.rental;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 资源对象的示例数据用 Java 播种，不写在 data.sql 里：
 * 多行 YAML 在各数据库的字符串字面量差异太大（H2 / PostgreSQL 的换行与拼接语法不同），
 * 放在 Java 里用文本块最稳，也不会因为方言差异导致启动失败。
 */
@Component
public class SeedResources implements ApplicationRunner {

    private final Db db;

    public SeedResources(Db db) {
        this.db = db;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (db.resourceByKey("Deployment", "tenant-t1", "qwen-infer") != null) {
            return;
        }

        db.upsertResource("rs1", "apps/v1", "Deployment", "tenant-t1", "qwen-infer", """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: qwen-infer
                  namespace: tenant-t1
                spec:
                  replicas: 1
                  selector:
                    matchLabels:
                      app: qwen-infer
                  template:
                    metadata:
                      labels:
                        app: qwen-infer
                    spec:
                      schedulerName: volcano
                      containers:
                      - name: engine
                        image: registry.local/ai/vllm:0.6.3
                        resources:
                          limits:
                            nvidia.com/gpu: 1
                """, false);

        db.upsertResource("rs2", "v1", "Namespace", "-", "tenant-t1", """
                apiVersion: v1
                kind: Namespace
                metadata:
                  name: tenant-t1
                  labels:
                    tenant: demo-a
                """, false);
    }
}
