package com.example.feishurobotadapter.dto;

public record BotConfigRequest(
        String robotName,
        String appId,
        String appSecret,
        String verificationToken,
        String encryptKey,
        String difyUrl,
        String difyApiKey
) {
}
