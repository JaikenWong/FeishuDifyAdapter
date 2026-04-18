package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.BotConfigRequest;
import com.example.feishurobotadapter.dto.BotConfigResponse;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.repository.BotConfigRepository;
import com.example.feishurobotadapter.service.BotConfigService;
import com.example.feishurobotadapter.service.FeishuLongConnectionManager;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BotConfigServiceImpl implements BotConfigService {

    private final BotConfigRepository botConfigRepository;
    private final FeishuLongConnectionManager longConnectionManager;

    public BotConfigServiceImpl(BotConfigRepository botConfigRepository, FeishuLongConnectionManager longConnectionManager) {
        this.botConfigRepository = botConfigRepository;
        this.longConnectionManager = longConnectionManager;
    }

    @Override
    public List<BotConfigResponse> list() {
        return botConfigRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public BotConfigResponse create(BotConfigRequest request) {
        LocalDateTime now = LocalDateTime.now();
        BotConfig config = new BotConfig();
        config.setRobotName(request.robotName());
        config.setAppId(request.appId());
        config.setAppSecret(request.appSecret());
        config.setVerificationToken(request.verificationToken());
        config.setEncryptKey(request.encryptKey());
        config.setDifyUrl(request.difyUrl());
        config.setDifyApiKey(request.difyApiKey());
        config.setLongConnectionEnabled(false);
        config.setLastStatusMessage("未启动");
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
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
                config.getLongConnectionEnabled(),
                config.getLastStatusMessage(),
                config.getCreatedAt(),
                config.getUpdatedAt()
        );
    }

    private String mask(String value) {
        if (value == null || value.length() <= 6) {
            return "******";
        }
        return value.substring(0, 3) + "******" + value.substring(value.length() - 3);
    }
}
