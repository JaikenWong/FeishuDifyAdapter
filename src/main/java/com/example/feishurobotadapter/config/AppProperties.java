package com.example.feishurobotadapter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AppProperties(
        String sessionKey,
        String defaultAdminUsername,
        String defaultAdminPassword
) {
}
