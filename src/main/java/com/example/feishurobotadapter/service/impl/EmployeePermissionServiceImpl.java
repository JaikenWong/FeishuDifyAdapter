package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.service.EmployeePermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class EmployeePermissionServiceImpl implements EmployeePermissionService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private static final String FEISHU_OPEN_API = "https://open.feishu.cn/open-apis";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper;

    public EmployeePermissionServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean hasPermission(BotConfig config, FeishuSenderProfile senderProfile, String openId) {
        if (!Boolean.TRUE.equals(config.getEmployeeAuthEnabled())) {
            return true;
        }
        String employeeNo = senderProfile == null ? null : trimToNull(senderProfile.employeeNo());
        if (employeeNo == null) {
            log.info("[EmployeeAuth] 鉴权开启但工号为空，拒绝通过。openId={}", safeTail(openId));
            return false;
        }
        try {
            boolean allowed = hasPermissionByBitable(config, employeeNo);
            log.info("[EmployeeAuth] 多维表格鉴权结果: employeeNo={}, allowed={}", employeeNo, allowed);
            return allowed;
        } catch (Exception ex) {
            log.warn("[EmployeeAuth] 多维表格鉴权调用异常，按无权限处理。employeeNo={}", employeeNo, ex);
            return false;
        }
    }

    private boolean hasPermissionByBitable(BotConfig config, String employeeNo) throws Exception {
        String appToken = trimToNull(config.getEmployeeAuthBitableAppToken());
        String tableId = trimToNull(config.getEmployeeAuthBitableTableId());
        if (appToken == null || tableId == null) {
            log.warn("[EmployeeAuth] 多维表格鉴权配置缺失: appToken/tableId");
            return false;
        }
        String accessToken = fetchTenantAccessToken(config);
        String viewId = trimToNull(config.getEmployeeAuthBitableViewId());
        String employeeField = trimToNull(config.getEmployeeAuthBitableEmployeeField());
        if (employeeField == null) {
            employeeField = "工号";
        }
        log.info(
                "[EmployeeAuth] 开始多维表格鉴权: appToken={}, tableId={}, viewId={}, employeeField={}, employeeNo={}",
                maskToken(appToken),
                tableId,
                viewId == null ? "(空)" : viewId,
                employeeField,
                employeeNo
        );
        String pageToken = null;
        for (int i = 0; i < 10; i++) {
            String url = FEISHU_OPEN_API + "/bitable/v1/apps/" + appToken + "/tables/" + tableId
                    + "/records?page_size=100";
            if (viewId != null) {
                url += "&view_id=" + viewId;
            }
            if (pageToken != null) {
                url += "&page_token=" + pageToken;
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn(
                        "[EmployeeAuth] 多维表格查询失败: status={}, appToken={}, tableId={}, viewId={}, pageToken={}, body={}",
                        response.statusCode(),
                        maskToken(appToken),
                        tableId,
                        viewId == null ? "(空)" : viewId,
                        pageToken == null ? "(空)" : pageToken,
                        truncateBody(response.body())
                );
                return false;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                log.warn(
                        "[EmployeeAuth] 多维表格查询返回错误: code={}, msg={}, appToken={}, tableId={}, viewId={}, body={}",
                        root.path("code").asInt(-1),
                        root.path("msg").asText(""),
                        maskToken(appToken),
                        tableId,
                        viewId == null ? "(空)" : viewId,
                        truncateBody(response.body())
                );
                return false;
            }
            JsonNode items = root.path("data").path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    JsonNode fields = item.path("fields");
                    if (!fields.isObject()) {
                        continue;
                    }
                    if (matchesEmployeeNo(fields.get(employeeField), employeeNo)) {
                        return true;
                    }
                }
            }
            boolean hasMore = root.path("data").path("has_more").asBoolean(false);
            if (!hasMore) {
                break;
            }
            pageToken = trimToNull(root.path("data").path("page_token").asText(null));
            if (pageToken == null) {
                break;
            }
        }
        return false;
    }

    private String fetchTenantAccessToken(BotConfig config) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "app_id", config.getAppId(),
                "app_secret", config.getAppSecret()
        ));
        HttpRequest request = HttpRequest.newBuilder(URI.create(FEISHU_OPEN_API + "/auth/v3/tenant_access_token/internal"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                    "获取飞书 tenant_access_token 失败，status="
                            + response.statusCode()
                            + ", body="
                            + truncateBody(response.body())
            );
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(
                    "获取飞书 tenant_access_token 失败: code="
                            + root.path("code").asInt(-1)
                            + ", msg="
                            + root.path("msg").asText("")
                            + ", body="
                            + truncateBody(response.body())
            );
        }
        String token = trimToNull(root.path("tenant_access_token").asText(null));
        if (token == null) {
            throw new IllegalStateException("tenant_access_token 为空");
        }
        return token;
    }

    private boolean matchesEmployeeNo(JsonNode fieldValue, String employeeNo) {
        if (fieldValue == null || fieldValue.isMissingNode() || fieldValue.isNull()) {
            return false;
        }
        if (fieldValue.isTextual()) {
            return employeeNo.equals(trimToNull(fieldValue.asText()));
        }
        if (fieldValue.isArray()) {
            for (JsonNode node : fieldValue) {
                if (matchesEmployeeNo(node, employeeNo)) {
                    return true;
                }
            }
            return false;
        }
        if (fieldValue.isObject()) {
            JsonNode textNode = fieldValue.get("text");
            if (textNode != null && textNode.isTextual() && employeeNo.equals(trimToNull(textNode.asText()))) {
                return true;
            }
            JsonNode valueNode = fieldValue.get("value");
            if (valueNode != null && valueNode.isTextual() && employeeNo.equals(trimToNull(valueNode.asText()))) {
                return true;
            }
        }
        return employeeNo.equals(trimToNull(fieldValue.asText()));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String safeTail(String value) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        if (value.length() <= 6) {
            return "***" + value;
        }
        return "..." + value.substring(value.length() - 6);
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "(空)";
        }
        if (token.length() <= 8) {
            return "***" + token;
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    private static String truncateBody(String body) {
        if (body == null) {
            return "(空)";
        }
        String trimmed = body.trim();
        if (trimmed.length() <= 500) {
            return trimmed;
        }
        return trimmed.substring(0, 500) + "...(truncated)";
    }
}
