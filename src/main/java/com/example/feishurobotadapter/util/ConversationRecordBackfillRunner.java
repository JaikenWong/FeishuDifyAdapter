package com.example.feishurobotadapter.util;

import com.example.feishurobotadapter.service.ConversationRecordMaintenance;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 保证 Dify 续聊所需字段持久、可查询：历史数据 {@code dify_user_key} 为空时回填为 {@code open_id}。
 * <p>会话主数据在 H2 文件库中，进程重启不会丢失；若查不到历史 conversation_id，多为该字段未对齐。</p>
 */
@Component
@Order(20)
@RequiredArgsConstructor
@Log4j2
public class ConversationRecordBackfillRunner implements ApplicationRunner {

    private final ConversationRecordMaintenance conversationRecordMaintenance;

    @Override
    public void run(ApplicationArguments args) {
        int updated = conversationRecordMaintenance.backfillDifyUserKeyFromOpenIdWhereNull();
        if (updated > 0) {
            log.info(
                    "[ConversationRecord] 已回填 dify_user_key {} 条（与 open_id 对齐），重启后仍可按同一 Dify 用户续聊",
                    updated
            );
        }
    }
}
