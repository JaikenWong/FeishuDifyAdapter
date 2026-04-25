package com.example.feishurobotadapter.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书发件人在通讯录/消息中的信息，用于填入 Dify {@code inputs}。
 * <p>变量名需在 Dify 应用中自行配置同名输入项后，方可在提示词中引用。</p>
 */
public record FeishuSenderProfile(
        String displayName,
        String fullName,
        String employeeNo,
        String email,
        String enName
) {
}
