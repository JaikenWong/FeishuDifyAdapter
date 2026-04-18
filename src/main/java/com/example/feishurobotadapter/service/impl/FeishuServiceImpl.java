package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.service.FeishuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.Card;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardResp;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/**
 * 飞书服务实现
 * 负责：
 * 1. 创建交互式卡片（支持流式模式）
 * 2. 发送卡片消息到飞书
 * 3. 流式更新卡片内容
 * 使用飞书 CardKit API 实现卡片的创建和更新
 */
@Service
@Log4j2
public class FeishuServiceImpl implements FeishuService {

    private final ObjectMapper objectMapper;
    // 缓存卡片的当前sequence，飞书要求每次更新sequence必须递增
    private final ConcurrentHashMap<String, Integer> cardSequenceCache = new ConcurrentHashMap<>();
    // 缓存飞书客户端，避免每次都创建新的（每个机器人配置一个client）
    private final ConcurrentHashMap<String, Client> clientCache = new ConcurrentHashMap<>();

    public FeishuServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一个新的交互式卡片
     * @param config 机器人配置
     * @param markdownContent 初始卡片内容
     * @param streaming 是否开启流式模式（开启后支持增量更新效果）
     * @return 卡片ID
     */
    @Override
    public String createCard(BotConfig config, String markdownContent, boolean streaming) {
        try {
            CreateCardReq req = CreateCardReq.newBuilder()
                    .createCardReqBody(CreateCardReqBody.newBuilder()
                            .type("card_json")
                            .data(buildInteractiveCard(markdownContent, streaming))
                            .build())
                    .build();
            CreateCardResp resp = getClient(config).cardkit().v1().card().create(req);
            if (!resp.success()) {
                throw new IllegalStateException("创建飞书卡片失败: " + resp.getMsg());
            }
            String cardId = resp.getData().getCardId();
            // 初始化sequence，从0开始
            cardSequenceCache.put(cardId, 0);
            return cardId;
        } catch (Exception ex) {
            log.error("[Feishu] 创建卡片失败", ex);
            throw new IllegalStateException("创建飞书卡片失败", ex);
        }
    }

    /**
     * 更新卡片内容
     * 飞书卡片更新要求：
     * 1. sequence 必须严格递增
     * 2. 每次更新需要新的uuid
     * @param config 机器人配置
     * @param cardId 卡片ID
     * @param markdownContent 更新后的markdown内容
     * @param streaming 是否保持流式模式
     */
    @Override
    public void updateCard(BotConfig config, String cardId, String markdownContent, boolean streaming) {
        try {
            Card card = Card.newBuilder()
                    .type("card_json")
                    .data(buildInteractiveCard(markdownContent, streaming))
                    .build();
            UpdateCardReq req = UpdateCardReq.newBuilder()
                    .cardId(cardId)
                    .updateCardReqBody(UpdateCardReqBody.newBuilder()
                            .card(card)
                            .uuid(UUID.randomUUID().toString())
                            .sequence(nextSequence(cardId))
                            .build())
                    .build();
            UpdateCardResp resp = getClient(config).cardkit().v1().card().update(req);
            if (!resp.success()) {
                throw new IllegalStateException("更新飞书卡片失败: " + resp.getMsg());
            }
        } catch (Exception ex) {
            log.error("[Feishu] 更新卡片失败: cardId={}", cardId, ex);
            throw new IllegalStateException("更新飞书卡片失败", ex);
        }
    }

    /**
     * 发送卡片消息到飞书对话
     * @param config 机器人配置
     * @param cardId 已创建的卡片ID
     * @param chatId 会话ID
     * @param originalMessageId 原消息ID（用于回复）
     * @param chatType 会话类型 p2p/group
     * @return 发送成功返回消息ID
     */
    @Override
    public String sendCardMessage(BotConfig config, String cardId, String chatId, String originalMessageId, String chatType) {
        String content = "{\"type\":\"card\",\"data\":{\"card_id\":\"" + cardId + "\"}}";
        try {
            if ("p2p".equals(chatType)) {
                // 单聊直接发送
                CreateMessageReq req = CreateMessageReq.newBuilder()
                        .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                .receiveId(chatId)
                                .msgType("interactive")
                                .content(content)
                                .build())
                        .build();
                CreateMessageResp resp = getClient(config).im().message().create(req);
                if (resp.getCode() != 0) {
                    throw new IllegalStateException("发送飞书卡片消息失败: " + resp.getMsg());
                }
                return resp.getData().getMessageId();
            }

            // 群聊回复原消息
            ReplyMessageReq req = ReplyMessageReq.newBuilder()
                    .messageId(originalMessageId)
                    .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                            .content(content)
                            .msgType("interactive")
                            .build())
                    .build();
            ReplyMessageResp resp = getClient(config).im().message().reply(req);
            if (resp.getCode() != 0) {
                throw new IllegalStateException("回复飞书卡片消息失败: " + resp.getMsg());
            }
            return resp.getData().getMessageId();
        } catch (Exception ex) {
            log.error("[Feishu] 发送卡片消息失败", ex);
            throw new IllegalStateException("发送飞书卡片消息失败", ex);
        }
    }

    /**
     * 构建飞书交互式卡片的JSON数据
     * @param markdownContent markdown内容
     * @param streaming 是否开启流式更新模式
     * 流式模式配置参考飞书官方文档：https://open.feishu.cn/document/uAjLw4CM/ukzMukzMukzM/development/interactive-cards/streaming-mode
     */
    private String buildInteractiveCard(String markdownContent, boolean streaming) {
        try {
            Map<String, Object> config = streaming
                    ? Map.of(
                            "streaming_mode", true,
                            "streaming_config", Map.of(
                                    "print_frequency_ms", Map.of("default", 70, "android", 70, "ios", 70, "pc", 70),
                                    "print_step", Map.of("default", 1, "android", 1, "ios", 1, "pc", 1),
                                    "print_strategy", "fast"
                            ),
                            "summary", Map.of("content", "[生成中...]")
                    )
                    : Map.of("streaming_mode", false);
            return objectMapper.writeValueAsString(Map.of(
                    "schema", "2.0",
                    "config", config,
                    "body", Map.of(
                            "elements", new Object[] {
                                    Map.of(
                                            "tag", "markdown",
                                            "content", markdownContent == null || markdownContent.isBlank() ? "..." : markdownContent,
                                            "element_id", "answer_content"
                                    )
                            }
                    )
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("构造飞书卡片失败", ex);
        }
    }

    /**
     * 获取下一个递增的sequence
     * 飞书要求每次更新sequence必须大于上一次，否则更新会被拒绝
     */
    private int nextSequence(String cardId) {
        int current = cardSequenceCache.getOrDefault(cardId, 0) + 1;
        cardSequenceCache.put(cardId, current);
        return current;
    }

    /**
     * 获取飞书客户端，缓存复用
     */
    private Client getClient(BotConfig config) {
        String key = config.getAppId() + ":" + config.getAppSecret();
        return clientCache.computeIfAbsent(key, k ->
            Client.newBuilder(config.getAppId(), config.getAppSecret()).build()
        );
    }
}
