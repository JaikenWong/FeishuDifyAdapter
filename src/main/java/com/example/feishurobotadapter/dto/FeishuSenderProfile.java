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

    /**
     * 合并进 Dify 请求体 {@code inputs}；仅包含非空字段。
     */
    public Map<String, String> toDifyInputVariables() {
        Map<String, String> m = new LinkedHashMap<>();
        putIfPresent(m, "feishu_sender_name", displayName);
        putIfPresent(m, "feishu_full_name", fullName);
        putIfPresent(m, "feishu_employee_no", employeeNo);
        putIfPresent(m, "feishu_email", email);
        putIfPresent(m, "feishu_en_name", enName);
        return m;
    }

    private static void putIfPresent(Map<String, String> m, String key, String value) {
        if (value != null && !value.isBlank()) {
            m.put(key, value);
        }
    }
}
