package com.example.feishurobotadapter.dto;

import java.time.LocalDateTime;

public record BotConfigResponse(
        Long id,
        String robotName,
        String appId,
        String appSecretMasked,
        String verificationTokenMasked,
        String encryptKeyMasked,
        String difyUrl,
        String difyApiKeyMasked,
        Boolean longConnectionEnabled,
        String lastStatusMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
