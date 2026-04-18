package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.DifyStreamChunk;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.entity.ConversationRecord;
import com.example.feishurobotadapter.service.ConversationRecordService;
import com.example.feishurobotadapter.service.DifyService;
import com.example.feishurobotadapter.service.FeishuService;
import com.example.feishurobotadapter.service.MessageRelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/**
 * 消息转发服务实现
 * 负责：
 * 1. 接收飞书长连接推送的消息
 * 2. 消息去重（飞书可能重发）
 * 3. 异步处理消息（避免阻塞长连接）
 * 4. 调用 Dify API 获取回答
 * 5. 流式更新飞书卡片
 * 6. 保存会话记录到数据库
 */
@Service
@Log4j2
public class MessageRelayServiceImpl implements MessageRelayService {

    private final DifyService difyService;
    private final FeishuService feishuService;
    private final ConversationRecordService conversationRecordService;
    private final ObjectMapper objectMapper;
    // 异步线程池处理消息，不阻塞长连接
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    // 消息去重缓存：防止飞书重发导致重复处理
    private final ConcurrentHashMap<String, Long> messageCache = new ConcurrentHashMap<>();

    public MessageRelayServiceImpl(
            DifyService difyService,
            FeishuService feishuService,
            ConversationRecordService conversationRecordService,
            ObjectMapper objectMapper
    ) {
        this.difyService = difyService;
        this.feishuService = feishuService;
        this.conversationRecordService = conversationRecordService;
        this.objectMapper = objectMapper;
    }

    /**
     * 入口：处理飞书推送的消息接收事件
     * 这里只做去重，然后提交到线程池异步处理
     */
    @Override
    public void process(BotConfig config, P2MessageReceiveV1 event) {
        String sourceMessageId = event.getEvent().getMessage().getMessageId();
        // 去重：如果已经处理过这个消息，直接返回
        if (messageCache.putIfAbsent(sourceMessageId, System.currentTimeMillis()) != null) {
            log.info("[MessageRelay] 消息已处理，跳过: {}", sourceMessageId);
            return;
        }

        log.info("[MessageRelay] 收到新消息: messageId={}, robot={}", sourceMessageId, config.getRobotName());
        executorService.submit(() -> handleAsync(config, event));
    }

    /**
     * 异步处理消息的核心逻辑
     */
    private void handleAsync(BotConfig config, P2MessageReceiveV1 event) {
        String messageType = event.getEvent().getMessage().getMessageType();
        // 只处理文本消息
        if (!"text".equals(messageType)) {
            log.info("[MessageRelay] 忽略非文本消息: type={}", messageType);
            messageCache.remove(event.getEvent().getMessage().getMessageId());
            return;
        }

        // 提取消息信息
        String openId = event.getEvent().getSender().getSenderId().getOpenId();
        String chatId = event.getEvent().getMessage().getChatId();
        String originalMessageId = event.getEvent().getMessage().getMessageId();
        String chatType = event.getEvent().getMessage().getChatType();
        String question = extractText(event.getEvent().getMessage().getContent());

        log.info("[MessageRelay] 问题内容: {}", question);

        // 使用 AtomicReference 解决 lambda 必须引用 final 变量的问题
        final AtomicReference<String> cardId = new AtomicReference<>();
        final AtomicReference<String> responseMessageId = new AtomicReference<>();
        // 累积Dify返回的回答
        AtomicReference<String> answer = new AtomicReference<>("");
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<String> conversationId = new AtomicReference<>();

        try {
            // 1. 创建流式卡片，显示"思考中..."
            cardId.set(feishuService.createCard(config, "**思考中...**", true));
            log.info("[MessageRelay] 创建飞书卡片成功: cardId={}", cardId.get());

            // 2. 发送卡片消息到飞书群/私聊
            responseMessageId.set(feishuService.sendCardMessage(config, cardId.get(), chatId, originalMessageId, chatType));
            log.info("[MessageRelay] 发送卡片消息成功: messageId={}", responseMessageId.get());

            // 3. 调用 Dify 流式API，每收到一个chunk就更新一次卡片
            int[] chunkCount = {0};
            difyService.streamChat(config, openId, question)
                    .doOnNext(chunk -> {
                        chunkCount[0]++;
                        applyStreamChunk(config, cardId.get(), answer, taskId, conversationId, chunk);
                    })
                    .blockLast(Duration.ofMinutes(5));

            log.info("[MessageRelay] Dify 流式响应完成，总共 {} 个chunk，最终回答长度: {}", chunkCount[0], answer.get().length());

            // 4. 保存会话记录到数据库
            ConversationRecord record = new ConversationRecord();
            record.setBotConfigId(config.getId());
            record.setOpenId(openId);
            record.setChatId(chatId);
            record.setFeishuMessageId(responseMessageId.get());
            record.setDifyConversationId(conversationId.get());
            record.setDifyTaskId(taskId.get());
            record.setQuestion(question);
            record.setAnswer(answer.get());
            record.setCreatedAt(LocalDateTime.now());
            conversationRecordService.save(record);

            log.info("[MessageRelay] 消息处理完成");
        } catch (Exception ex) {
            // 处理异常，在飞书卡片显示错误信息
            if (cardId.get() != null) {
                String errorMsg = "**调用 Dify 失败**: " + ex.getMessage();
                feishuService.updateCard(config, cardId.get(), errorMsg, false);
            }
            // 清除消息缓存允许重试
            messageCache.remove(event.getEvent().getMessage().getMessageId());
            // 打印日志
            log.error("[MessageRelay] 处理消息失败", ex);
        }
    }

    /**
     * 处理 Dify 返回的一个流式chunk，更新飞书卡片
     */
    private void applyStreamChunk(
            BotConfig config,
            String cardId,
            AtomicReference<String> answer,
            AtomicReference<String> taskId,
            AtomicReference<String> conversationId,
            DifyStreamChunk chunk
    ) {
        // 保存元信息
        if (chunk.taskId() != null && !chunk.taskId().isBlank()) {
            taskId.set(chunk.taskId());
        }
        if (chunk.conversationId() != null && !chunk.conversationId().isBlank()) {
            conversationId.set(chunk.conversationId());
        }
        // 如果有新的文本增量，追加到回答并更新卡片
        if (chunk.answerChunk() != null && !chunk.answerChunk().isBlank()) {
            answer.set(answer.get() + chunk.answerChunk());
            feishuService.updateCard(config, cardId, answer.get(), true);
        }
        // 如果流式结束，最后一次更新关闭流式模式
        if (chunk.completed()) {
            feishuService.updateCard(config, cardId, answer.get(), false);
        }
    }

    /**
     * 从飞书消息content中提取纯文本
     * 飞书文本消息的content是JSON格式：{"text":"用户问题"}
     */
    private String extractText(String jsonContent) {
        try {
            JsonNode node = objectMapper.readTree(jsonContent);
            return node.path("text").asText("");
        } catch (Exception ex) {
            // 如果解析失败，直接返回原始内容
            return jsonContent;
        }
    }
}
