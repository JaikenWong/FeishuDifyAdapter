package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.entity.ConversationRecord;
import java.util.List;

public interface ConversationRecordService {

    ConversationRecord save(ConversationRecord record);

    List<ConversationRecord> listByBotConfigId(Long botConfigId);
}
