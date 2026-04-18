package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.repository.BotConfigRepository;
import com.example.feishurobotadapter.service.FeishuLongConnectionManager;
import com.example.feishurobotadapter.service.MessageRelayService;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

@Service
@Log4j2
public class InMemoryFeishuLongConnectionManager implements FeishuLongConnectionManager {

    private final Map<Long, Client> clientMap = new ConcurrentHashMap<>();
    private final BotConfigRepository botConfigRepository;
    private final MessageRelayService messageRelayService;

    public InMemoryFeishuLongConnectionManager(BotConfigRepository botConfigRepository, MessageRelayService messageRelayService) {
        this.botConfigRepository = botConfigRepository;
        this.messageRelayService = messageRelayService;
    }

    @Override
    public void enable(Long configId) {
        if (clientMap.containsKey(configId)) {
            log.info("[FeishuLongConnection] 长连接已存在，跳过重复启动: configId={}", configId);
            return;
        }
        clientMap.computeIfAbsent(configId, this::createAndStartClient);
    }

    @Override
    public void disable(Long configId) {
        Client client = clientMap.remove(configId);
        if (client == null) {
            return;
        }
        shutdownWsClient(client);
        log.info("[FeishuLongConnection] 长连接已关闭: configId={}", configId);
    }

    @Override
    public boolean isEnabled(Long configId) {
        return clientMap.containsKey(configId);
    }

    private Client createAndStartClient(Long configId) {
        BotConfig config = botConfigRepository.findById(configId)
                .orElseThrow(() -> new IllegalArgumentException("配置不存在"));

        String verificationToken = config.getVerificationToken() != null ? config.getVerificationToken() : "";
        String encryptKey = config.getEncryptKey() != null ? config.getEncryptKey() : "";

        EventDispatcher dispatcher = EventDispatcher.newBuilder(verificationToken, encryptKey)
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        messageRelayService.process(config, event);
                    }
                })
                .build();

        Client client = new Client.Builder(config.getAppId(), config.getAppSecret())
                .eventHandler(dispatcher)
                .build();
        client.start();
        log.info("[FeishuLongConnection] 长连接启动成功: configId={}, robot={}", configId, config.getRobotName());
        return client;
    }

    /**
     * SDK 仅公开 {@link Client#start()}；关闭走受保护的 {@code disconnect()}（内部 {@code close(1000, "client closed")}）。
     * 默认自动重连为 true，需先关掉再 {@link ExecutorService#shutdownNow()} 线程池，避免残留任务重连。
     */
    private void shutdownWsClient(Client client) {
        try {
            Field autoReconnectField = Client.class.getDeclaredField("autoReconnect");
            autoReconnectField.setAccessible(true);
            autoReconnectField.set(client, Boolean.FALSE);

            Method disconnect = Client.class.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(client);

            Field executorField = Client.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            ExecutorService executor = (ExecutorService) executorField.get(client);
            if (executor != null) {
                executor.shutdownNow();
            }
        } catch (ReflectiveOperationException e) {
            log.warn("[FeishuLongConnection] 反射关闭 WebSocket 失败（升级 oapi-sdk 后可能需调整字段名）: {}", e.toString());
        }
    }
}
