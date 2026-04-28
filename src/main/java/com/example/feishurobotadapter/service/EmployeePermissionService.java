package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;

public interface EmployeePermissionService {

    AuthDecision checkPermission(BotConfig config, FeishuSenderProfile senderProfile, String openId, String userId, String unionId);

    record AuthDecision(boolean allowed, String employeeNo) {
    }
}
