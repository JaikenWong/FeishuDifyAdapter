package com.example.feishurobotadapter.repository;

import com.example.feishurobotadapter.entity.ConversationRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ConversationRecordRepository extends JpaRepository<ConversationRecord, Long> {

    List<ConversationRecord> findByBotConfigIdOrderByCreatedAtDesc(Long botConfigId);

    /**
     * 同一机器人 + 同一 Dify 用户标识（工号或 open_id 等）+ 同一会话下，最近一次已成功拿到 Dify 会话 ID 的记录。
     */
    Optional<ConversationRecord> findTopByBotConfigIdAndDifyUserKeyAndChatIdAndDifyConversationIdIsNotNullOrderByCreatedAtDesc(
            Long botConfigId,
            String difyUserKey,
            String chatId
    );

    /**
     * 升级后历史行 {@code dify_user_key} 为空时，用飞书 open_id 对齐（与此前用 open_id 作为 Dify user 的行为一致），避免重启后无法续聊。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ConversationRecord r SET r.difyUserKey = r.openId WHERE r.difyUserKey IS NULL AND r.openId IS NOT NULL AND r.openId <> ''")
    int backfillDifyUserKeyFromOpenIdWhereNull();
}
