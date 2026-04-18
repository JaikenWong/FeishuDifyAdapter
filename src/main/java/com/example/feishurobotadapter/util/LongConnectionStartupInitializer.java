package com.example.feishurobotadapter.util;

import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.repository.BotConfigRepository;
import com.example.feishurobotadapter.service.FeishuLongConnectionManager;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class LongConnectionStartupInitializer {

    private final BotConfigRepository botConfigRepository;
    private final FeishuLongConnectionManager longConnectionManager;

    public LongConnectionStartupInitializer(
            BotConfigRepository botConfigRepository,
            FeishuLongConnectionManager longConnectionManager
    ) {
        this.botConfigRepository = botConfigRepository;
        this.longConnectionManager = longConnectionManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreEnabledLongConnections() {
        List<BotConfig> enabledConfigs = botConfigRepository.findByLongConnectionEnabledTrueOrderByCreatedAtDesc();
        if (enabledConfigs.isEmpty()) {
            log.info("[FeishuLongConnection] 启动时未发现需要恢复的长连接配置");
            return;
        }

        log.info("[FeishuLongConnection] 开始恢复长连接，配置数量={}", enabledConfigs.size());
        for (BotConfig config : enabledConfigs) {
            try {
                longConnectionManager.enable(config.getId());
                config.setLastStatusMessage("应用启动后已自动恢复飞书 SDK 长连接监听");
                config.setUpdatedAt(LocalDateTime.now());
                botConfigRepository.save(config);
            } catch (Exception ex) {
                config.setLastStatusMessage("应用启动自动恢复长连接失败: " + ex.getMessage());
                config.setUpdatedAt(LocalDateTime.now());
                botConfigRepository.save(config);
                log.error("[FeishuLongConnection] 自动恢复失败: configId={}, robot={}", config.getId(), config.getRobotName(), ex);
            }
        }
    }
}
