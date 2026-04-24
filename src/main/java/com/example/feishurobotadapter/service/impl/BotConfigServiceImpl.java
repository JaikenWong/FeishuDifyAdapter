package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.BotConfigRequest;
import com.example.feishurobotadapter.dto.BotConfigResponse;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.repository.BotConfigRepository;
import com.example.feishurobotadapter.repository.ConversationRecordRepository;
import com.example.feishurobotadapter.service.BotConfigService;
import com.example.feishurobotadapter.service.FeishuLongConnectionManager;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotConfigServiceImpl implements BotConfigService {

    private final BotConfigRepository botConfigRepository;
    private final ConversationRecordRepository conversationRecordRepository;
    private final FeishuLongConnectionManager longConnectionManager;

    public BotConfigServiceImpl(
            BotConfigRepository botConfigRepository,
            ConversationRecordRepository conversationRecordRepository,
            FeishuLongConnectionManager longConnectionManager) {
        this.botConfigRepository = botConfigRepository;
        this.conversationRecordRepository = conversationRecordRepository;
        this.longConnectionManager = longConnectionManager;
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
                config.getLongConnectionEnabled(),
                config.getLastStatusMessage(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private void applyRequestToConfig(BotConfig config, BotConfigRequest request, boolean creating) {
        config.setRobotName(requireText(request.robotName(), "机器人名称不能为空"));
        config.setAppId(requireText(request.appId(), "飞书 App ID 不能为空"));
        config.setDifyUrl(requireText(request.difyUrl(), "Dify URL 不能为空"));

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

    private String mask(String value) {
        if (value == null || value.length() <= 6) {
            return "******";
        }
        return value.substring(0, 3) + "******" + value.substring(value.length() - 3);
    }
}
