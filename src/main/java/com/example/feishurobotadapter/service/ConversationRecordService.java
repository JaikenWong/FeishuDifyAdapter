package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.entity.ConversationRecord;
import java.util.List;
import java.util.Optional;

public interface ConversationRecordService {

    ConversationRecord save(ConversationRecord record);

    List<ConversationRecord> listByBotConfigId(Long botConfigId);

    /**
     * 用于 Dify 多轮对话：同一配置、{@code difyUserKey}（与请求 Dify 的 {@code user} 一致）、飞书 chat 内复用上次的 {@code conversation_id}。
     */
    Optional<String> findLatestDifyConversationId(Long botConfigId, String difyUserKey, String chatId);

    /**
     * 清空同一机器人 + Dify 用户 + chat 下已保存的 Dify 会话 ID，后续消息将创建新会话。
     */
    int clearConversationContext(Long botConfigId, String difyUserKey, String chatId);
}
