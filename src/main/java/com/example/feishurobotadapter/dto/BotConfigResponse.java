package com.example.feishurobotadapter.dto;

import java.time.LocalDateTime;
import java.util.List;

public record BotConfigResponse(
        Long id,
        String robotName,
        String appId,
        String appSecretMasked,
        String verificationTokenMasked,
        String encryptKeyMasked,
        String difyUrl,
        String difyApiKeyMasked,
        Boolean employeeAuthEnabled,
        String employeeAuthDeniedReply,
        String employeeAuthBitableAppToken,
        String employeeAuthBitableTableId,
        String employeeAuthBitableViewId,
        String employeeAuthBitableEmployeeField,
        String difyInputNameVar,
        String difyInputEmployeeNoVar,
        List<DifyInputMappingItem> difyInputMappings,
        Boolean longConnectionEnabled,
        String lastStatusMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
