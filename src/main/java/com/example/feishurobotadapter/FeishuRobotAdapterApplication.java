package com.example.feishurobotadapter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FeishuRobotAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(FeishuRobotAdapterApplication.class, args);
    }
}
