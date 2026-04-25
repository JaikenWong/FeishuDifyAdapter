package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.BotConfigRequest;
import com.example.feishurobotadapter.dto.BotConfigResponse;
import com.example.feishurobotadapter.dto.DifyInputMappingItem;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.repository.BotConfigRepository;
import com.example.feishurobotadapter.repository.ConversationRecordRepository;
import com.example.feishurobotadapter.service.BotConfigService;
import com.example.feishurobotadapter.service.FeishuLongConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotConfigServiceImpl implements BotConfigService {

    private final BotConfigRepository botConfigRepository;
    private final ConversationRecordRepository conversationRecordRepository;
    private final FeishuLongConnectionManager longConnectionManager;
    private final ObjectMapper objectMapper;

    public BotConfigServiceImpl(
            BotConfigRepository botConfigRepository,
            ConversationRecordRepository conversationRecordRepository,
            FeishuLongConnectionManager longConnectionManager,
            ObjectMapper objectMapper) {
        this.botConfigRepository = botConfigRepository;
        this.conversationRecordRepository = conversationRecordRepository;
        this.longConnectionManager = longConnectionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<BotConfigResponse> list(String keyword, String sort) {
        String trimmed = keyword == null ? "" : keyword.trim();
        boolean byName = "name".equalsIgnoreCase(sort == null ? "" : sort.trim());
        List<BotConfig> rows;
        if (trimmed.isEmpty()) {
            rows = byName
                    ? botConfigRepository.findAllByOrderByRobotNameAsc()
                    : botConfigRepository.findAllByOrderByCreatedAtDesc();
        } else {
            rows = byName
                    ? botConfigRepository.searchByKeywordOrderByRobotNameAsc(trimmed)
                    : botConfigRepository.searchByKeywordOrderByCreatedAtDesc(trimmed);
        }
        return rows.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public BotConfigResponse create(BotConfigRequest request) {
        LocalDateTime now = LocalDateTime.now();
        BotConfig config = new BotConfig();
        applyRequestToConfig(config, request, true);
        config.setLongConnectionEnabled(false);
        config.setLastStatusMessage("未启动");
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        validateEmployeeAuthConfig(config);
        return toResponse(botConfigRepository.save(config));
    }

    @Override
    @Transactional
    public BotConfigResponse update(Long id, BotConfigRequest request) {
        BotConfig config = botConfigRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("配置不存在"));
        if (Boolean.TRUE.equals(config.getLongConnectionEnabled())) {
            throw new IllegalArgumentException("请先关闭长连接后再修改配置");
        }
        applyRequestToConfig(config, request, false);
        config.setUpdatedAt(LocalDateTime.now());
        validateEmployeeAuthConfig(config);
        return toResponse(botConfigRepository.save(config));
    }

    @Override
    @Transactional
    public BotConfigResponse toggleLongConnection(Long id, boolean enabled) {
        BotConfig config = botConfigRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("配置不存在"));
        config.setLongConnectionEnabled(enabled);
        config.setUpdatedAt(LocalDateTime.now());
        config.setLastStatusMessage(enabled
                ? "已开启飞书 SDK 长连接监听"
                : "已关闭飞书 SDK 长连接监听");
        if (enabled) {
            longConnectionManager.enable(id);
        } else {
            longConnectionManager.disable(id);
        }
        return toResponse(botConfigRepository.save(config));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        if (!botConfigRepository.existsById(id)) {
            throw new IllegalArgumentException("配置不存在");
        }
        longConnectionManager.disable(id);
        conversationRecordRepository.deleteByBotConfigId(id);
        botConfigRepository.deleteById(id);
    }

    private BotConfigResponse toResponse(BotConfig config) {
        return new BotConfigResponse(
                config.getId(),
                config.getRobotName(),
                config.getAppId(),
                mask(config.getAppSecret()),
                mask(config.getVerificationToken()),
                mask(config.getEncryptKey()),
                config.getDifyUrl(),
                mask(config.getDifyApiKey()),
                config.getEmployeeAuthEnabled(),
                config.getEmployeeAuthDeniedReply(),
                config.getEmployeeAuthBitableAppToken(),
                config.getEmployeeAuthBitableTableId(),
                config.getEmployeeAuthBitableViewId(),
                config.getEmployeeAuthBitableEmployeeField(),
                config.getDifyInputNameVar(),
                config.getDifyInputEmployeeNoVar(),
                readDifyInputMappings(config),
                config.getLongConnectionEnabled(),
                config.getLastStatusMessage(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private void applyRequestToConfig(BotConfig config, BotConfigRequest request, boolean creating) {
        config.setRobotName(requireText(request.robotName(), "机器人名称不能为空"));
        config.setAppId(requireText(request.appId(), "飞书 App ID 不能为空"));
        config.setDifyUrl(validateDifyUrl(requireText(request.difyUrl(), "Dify URL 不能为空")));

        String appSecret = emptyToNull(request.appSecret());
        if (creating && appSecret == null) {
            throw new IllegalArgumentException("飞书 App Secret 不能为空");
        }
        if (appSecret != null) {
            config.setAppSecret(appSecret);
        }

        String difyApiKey = emptyToNull(request.difyApiKey());
        if (creating && difyApiKey == null) {
            throw new IllegalArgumentException("Dify API Key 不能为空");
        }
        if (difyApiKey != null) {
            config.setDifyApiKey(difyApiKey);
        }

        String verificationToken = emptyToNull(request.verificationToken());
        if (creating || verificationToken != null) {
            config.setVerificationToken(verificationToken);
        }
        String encryptKey = emptyToNull(request.encryptKey());
        if (creating || encryptKey != null) {
            config.setEncryptKey(encryptKey);
        }
        config.setEmployeeAuthEnabled(Boolean.TRUE.equals(request.employeeAuthEnabled()));

        config.setEmployeeAuthDeniedReply(emptyToNull(request.employeeAuthDeniedReply()));
        config.setEmployeeAuthBitableAppToken(emptyToNull(request.employeeAuthBitableAppToken()));
        config.setEmployeeAuthBitableTableId(emptyToNull(request.employeeAuthBitableTableId()));
        config.setEmployeeAuthBitableViewId(emptyToNull(request.employeeAuthBitableViewId()));
        config.setEmployeeAuthBitableEmployeeField(
                emptyToNull(request.employeeAuthBitableEmployeeField()) == null
                        ? "工号"
                        : emptyToNull(request.employeeAuthBitableEmployeeField()));
        config.setDifyInputNameVar(
                emptyToNull(request.difyInputNameVar()) == null
                        ? "feishu_sender_name"
                        : emptyToNull(request.difyInputNameVar()));
        config.setDifyInputEmployeeNoVar(
                emptyToNull(request.difyInputEmployeeNoVar()) == null
                        ? "feishu_employee_no"
                        : emptyToNull(request.difyInputEmployeeNoVar()));
        config.setDifyInputMappingsJson(writeDifyInputMappings(request, config));
    }

    private String requireText(String value, String errorMessage) {
        String normalized = emptyToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    private String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private void validateEmployeeAuthConfig(BotConfig config) {
        if (!Boolean.TRUE.equals(config.getEmployeeAuthEnabled())) {
            return;
        }
        if (config.getEmployeeAuthBitableAppToken() == null || config.getEmployeeAuthBitableTableId() == null) {
            throw new IllegalArgumentException("多维表格鉴权模式下，App Token 和 Table ID 不能为空");
        }
    }

    private String validateDifyUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("Dify URL 必须使用 http 或 https 协议");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Dify URL 缺少主机地址");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Dify URL 格式无效: " + ex.getMessage());
        }
        return url;
    }

    private String mask(String value) {
        if (value == null || value.length() <= 6) {
            return "******";
        }
        return value.substring(0, 3) + "******" + value.substring(value.length() - 3);
    }

    private String writeDifyInputMappings(BotConfigRequest request, BotConfig config) {
        List<DifyInputMappingItem> normalized = new ArrayList<>();
        if (request.difyInputMappings() != null) {
            for (DifyInputMappingItem item : request.difyInputMappings()) {
                if (item == null) {
                    continue;
                }
                String variable = emptyToNull(item.variable());
                String source = normalizeSource(item.source());
                if (variable == null || source == null) {
                    continue;
                }
                normalized.add(new DifyInputMappingItem(variable, source));
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(new DifyInputMappingItem(config.getDifyInputNameVar(), "display_name"));
            normalized.add(new DifyInputMappingItem(config.getDifyInputEmployeeNoVar(), "employee_no"));
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Dify input 映射保存失败: " + ex.getMessage());
        }
    }

    private List<DifyInputMappingItem> readDifyInputMappings(BotConfig config) {
        return DifyInputMappingItem.fromConfigJson(
                config.getDifyInputMappingsJson(), config.getDifyInputNameVar(), config.getDifyInputEmployeeNoVar());
    }

    private String normalizeSource(String source) {
        String value = emptyToNull(source);
        if (value == null) {
            return null;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "display_name", "full_name", "employee_no", "email", "en_name", "open_id", "union_id" -> lower;
            default -> null;
        };
    }
}
