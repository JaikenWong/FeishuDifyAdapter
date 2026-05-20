package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.service.EmployeePermissionService;
import com.lark.oapi.Client;
import com.lark.oapi.service.contact.v3.enums.GetUserDepartmentIdTypeEnum;
import com.lark.oapi.service.contact.v3.enums.GetUserUserIdTypeEnum;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.contact.v3.model.GetUserResp;
import com.lark.oapi.service.bitable.v1.enums.SearchAppTableRecordUserIdTypeEnum;
import com.lark.oapi.service.bitable.v1.model.Condition;
import com.lark.oapi.service.bitable.v1.model.FilterInfo;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReq;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordReqBody;
import com.lark.oapi.service.bitable.v1.model.SearchAppTableRecordResp;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class EmployeePermissionServiceImpl implements EmployeePermissionService {

    private final ConcurrentHashMap<String, Client> clientCache = new ConcurrentHashMap<>();

    @Override
    public AuthDecision checkPermission(BotConfig config, FeishuSenderProfile senderProfile, String openId, String userId, String unionId) {
        if (!Boolean.TRUE.equals(config.getEmployeeAuthEnabled())) {
            String employeeNo = senderProfile == null ? null : trimToNull(senderProfile.employeeNo());
            return new AuthDecision(true, employeeNo);
        }
        String employeeNo = resolveEmployeeNo(config, senderProfile, openId, userId, unionId);
        if (employeeNo == null) {
            log.info(
                    "[EmployeeAuth] 鉴权开启但工号为空，拒绝通过。openId={}, userId={}, unionId={}",
                    safeTail(openId), userId, safeTail(unionId)
            );
            return new AuthDecision(false, null);
        }
        try {
            boolean allowed = hasPermissionByBitable(config, employeeNo);
            log.info("[EmployeeAuth] 多维表格鉴权结果: employeeNo={}, allowed={}", safeTail(employeeNo), allowed);
            return new AuthDecision(allowed, employeeNo);
        } catch (Exception ex) {
            log.warn("[EmployeeAuth] 多维表格鉴权调用异常，按无权限处理。employeeNo={}", safeTail(employeeNo), ex);
            return new AuthDecision(false, employeeNo);
        }
    }

    private String resolveEmployeeNo(BotConfig config, FeishuSenderProfile senderProfile, String openId, String userId, String unionId) {
        String fromProfile = senderProfile == null ? null : trimToNull(senderProfile.employeeNo());
        if (fromProfile != null) {
            return fromProfile;
        }
        if (!hasDedicatedAuthApp(config)) {
            return queryEmployeeNoByOpenId(config, openId);
        }
        String byUserId = queryEmployeeNoByUserId(config, userId);
        if (byUserId != null) {
            return byUserId;
        }
        return queryEmployeeNoByUnionId(config, unionId);
    }

    private String queryEmployeeNoByOpenId(BotConfig config, String openId) {
        String id = trimToNull(openId);
        if (id == null) {
            return null;
        }
        try {
            GetUserReq req = GetUserReq.newBuilder()
                    .userId(id)
                    .userIdType(GetUserUserIdTypeEnum.OPEN_ID)
                    .departmentIdType(GetUserDepartmentIdTypeEnum.OPEN_DEPARTMENT_ID)
                    .build();
            GetUserResp resp = getClient(config).contact().user().get(req);
            if (!resp.success() || resp.getData() == null || resp.getData().getUser() == null) {
                log.warn(
                        "[EmployeeAuth] open_id 查询通讯录失败: code={}, msg={}, openId={}, authApp={}",
                        resp.getCode(),
                        resp.getMsg(),
                        safeTail(id),
                        resolveAuthAppId(config)
                );
                return null;
            }
            return trimToNull(resp.getData().getUser().getEmployeeNo());
        } catch (Exception ex) {
            log.warn("[EmployeeAuth] open_id 查询工号异常: openId={}, authApp={}", safeTail(id), resolveAuthAppId(config), ex);
            return null;
        }
    }

    private String queryEmployeeNoByUserId(BotConfig config, String userId) {
        String id = trimToNull(userId);
        if (id == null) {
            return null;
        }
        try {
            GetUserReq req = GetUserReq.newBuilder()
                    .userId(id)
                    .userIdType(GetUserUserIdTypeEnum.USER_ID)
                    .departmentIdType(GetUserDepartmentIdTypeEnum.OPEN_DEPARTMENT_ID)
                    .build();
            GetUserResp resp = getClient(config).contact().user().get(req);
            if (!resp.success() || resp.getData() == null || resp.getData().getUser() == null) {
                log.warn(
                        "[EmployeeAuth] user_id 查询通讯录失败: code={}, msg={}, userId={}, authApp={}",
                        resp.getCode(),
                        resp.getMsg(),
                        id,
                        resolveAuthAppId(config)
                );
                return null;
            }
            return trimToNull(resp.getData().getUser().getEmployeeNo());
        } catch (Exception ex) {
            log.warn("[EmployeeAuth] user_id 查询工号异常: userId={}, authApp={}", id, resolveAuthAppId(config), ex);
            return null;
        }
    }

    private String queryEmployeeNoByUnionId(BotConfig config, String unionId) {
        String union = trimToNull(unionId);
        if (union == null) {
            return null;
        }
        try {
            GetUserReq req = GetUserReq.newBuilder()
                    .userId(union)
                    .userIdType(GetUserUserIdTypeEnum.UNION_ID)
                    .departmentIdType(GetUserDepartmentIdTypeEnum.OPEN_DEPARTMENT_ID)
                    .build();
            GetUserResp resp = getClient(config).contact().user().get(req);
            if (!resp.success() || resp.getData() == null || resp.getData().getUser() == null) {
                log.warn(
                        "[EmployeeAuth] union_id 查询通讯录失败: code={}, msg={}, unionId={}, authApp={}",
                        resp.getCode(),
                        resp.getMsg(),
                        safeTail(union),
                        resolveAuthAppId(config)
                );
                return null;
            }
            return trimToNull(resp.getData().getUser().getEmployeeNo());
        } catch (Exception ex) {
            log.warn("[EmployeeAuth] union_id 查询工号异常: unionId={}, authApp={}", safeTail(union), resolveAuthAppId(config), ex);
            return null;
        }
    }

    private boolean hasPermissionByBitable(BotConfig config, String employeeNo) throws Exception {
        String appToken = trimToNull(config.getEmployeeAuthBitableAppToken());
        String tableId = trimToNull(config.getEmployeeAuthBitableTableId());
        if (appToken == null || tableId == null) {
            log.warn("[EmployeeAuth] 多维表格鉴权配置缺失: appToken/tableId");
            return false;
        }
        String viewId = trimToNull(config.getEmployeeAuthBitableViewId());
        String employeeField = trimToNull(config.getEmployeeAuthBitableEmployeeField());
        if (employeeField == null) {
            employeeField = "工号";
        }
        log.info(
                "[EmployeeAuth] 开始多维表格鉴权: appToken={}, tableId={}, viewId={}, employeeField={}, employeeNo={}, authApp={}",
                maskToken(appToken),
                tableId,
                viewId == null ? "(空)" : viewId,
                employeeField,
                safeTail(employeeNo),
                resolveAuthAppId(config)
        );
        SearchAppTableRecordReqBody.Builder bodyBuilder = SearchAppTableRecordReqBody.newBuilder()
                .filter(FilterInfo.newBuilder()
                        .conjunction("and")
                        .conditions(new Condition[] {
                                Condition.newBuilder()
                                        .fieldName(employeeField)
                                        .operator("is")
                                        .value(new String[] { employeeNo })
                                        .build()
                        })
                        .build());
        if (viewId != null) {
            bodyBuilder.viewId(viewId);
        }
        SearchAppTableRecordReq req = SearchAppTableRecordReq.newBuilder()
                // 避免跨应用场景默认 open_id 触发 open_id cross app
                .userIdType(SearchAppTableRecordUserIdTypeEnum.UNION_ID.getValue())
                .appToken(appToken)
                .tableId(tableId)
                .pageSize(1)
                .searchAppTableRecordReqBody(bodyBuilder.build())
                .build();
        SearchAppTableRecordResp resp = getClient(config).bitable().v1().appTableRecord().search(req);
        if (!resp.success()) {
            String logId = resp.getError() == null ? null : trimToNull(resp.getError().getLogId());
            String errorMessage = resp.getError() == null ? null : trimToNull(resp.getError().getMessage());
            String troubleshooter = resp.getError() == null ? null : trimToNull(resp.getError().getTroubleshooter());
            log.warn(
                    "[EmployeeAuth] 多维表格筛选查询失败: code={}, msg={}, requestId={}, logId={}, errorMessage={}, troubleshooter={}, statusCode={}, appToken={}, tableId={}, viewId={}, employeeField={}, employeeNo={}, authApp={}",
                    resp.getCode(),
                    resp.getMsg(),
                    emptyToDash(resp.getRequestId()),
                    emptyToDash(logId),
                    emptyToDash(errorMessage),
                    emptyToDash(troubleshooter),
                    resp.getRawResponse() == null ? -1 : resp.getRawResponse().getStatusCode(),
                    maskToken(appToken),
                    tableId,
                    viewId == null ? "(空)" : viewId,
                    employeeField,
                    safeTail(employeeNo),
                    resolveAuthAppId(config)
            );
            if (resp.getRawResponse() != null && resp.getRawResponse().getBody() != null) {
                log.warn("[EmployeeAuth] 多维表格筛选失败响应体: {}", truncateBody(resp.getRawResponse().getBody()));
            }
            return false;
        }
        if (resp.getData() == null || resp.getData().getItems() == null) {
            return false;
        }
        return resp.getData().getItems().length > 0;
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

    private static String emptyToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String truncateBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "(empty)";
        }
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.length() <= 500) {
            return text;
        }
        return text.substring(0, 500) + "...(truncated)";
    }

    private Client getClient(BotConfig config) {
        String appId = resolveAuthAppId(config);
        String appSecret = resolveAuthAppSecret(config);
        String key = appId + ":" + appSecret;
        return clientCache.computeIfAbsent(key, k ->
                Client.newBuilder(appId, appSecret).build()
        );
    }

    private String resolveAuthAppId(BotConfig config) {
        String override = trimToNull(config.getEmployeeAuthAppId());
        return override == null ? config.getAppId() : override;
    }

    private String resolveAuthAppSecret(BotConfig config) {
        String override = trimToNull(config.getEmployeeAuthAppSecret());
        return override == null ? config.getAppSecret() : override;
    }

    private boolean hasDedicatedAuthApp(BotConfig config) {
        return trimToNull(config.getEmployeeAuthAppId()) != null && trimToNull(config.getEmployeeAuthAppSecret()) != null;
    }
}
