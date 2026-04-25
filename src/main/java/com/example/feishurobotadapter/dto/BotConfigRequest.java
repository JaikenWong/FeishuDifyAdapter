package com.example.feishurobotadapter.dto;

import java.util.List;

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
        String employeeAuthBitableEmployeeField,
        String difyInputNameVar,
        String difyInputEmployeeNoVar,
        List<DifyInputMappingItem> difyInputMappings
) {
}
