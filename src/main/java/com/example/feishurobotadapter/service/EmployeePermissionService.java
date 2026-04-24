package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;

public interface EmployeePermissionService {

    boolean hasPermission(BotConfig config, FeishuSenderProfile senderProfile, String openId);
}
