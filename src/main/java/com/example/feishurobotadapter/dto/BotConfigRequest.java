package com.example.feishurobotadapter.dto;

public record BotConfigRequest(
        String robotName,
        String appId,
        String appSecret,
        String verificationToken,
        String encryptKey,
        String difyUrl,
        String difyApiKey,
        Boolean employeeAuthEnabled,
        String employeeAuthDeniedReply,
        String employeeAuthBitableAppToken,
        String employeeAuthBitableTableId,
        String employeeAuthBitableViewId,
        String employeeAuthBitableEmployeeField
) {
}
