package com.example.rental;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GPU 算力租赁平台 · 业务层骨架
 *
 * 设计意图：业务层不依赖 K8s。K8s 只是一个"实例运行时"的接口实现，
 * 现在用 FakeK8s 跑通全链路，将来换成真实 client-go / fabric8 即可，业务代码不动。
 */
@SpringBootApplication
@EnableScheduling
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
