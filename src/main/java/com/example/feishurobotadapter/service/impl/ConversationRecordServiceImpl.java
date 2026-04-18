package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.entity.ConversationRecord;
import com.example.feishurobotadapter.repository.ConversationRecordRepository;
import com.example.feishurobotadapter.service.ConversationRecordService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationRecordServiceImpl implements ConversationRecordService {

    private final ConversationRecordRepository conversationRecordRepository;

    public ConversationRecordServiceImpl(ConversationRecordRepository conversationRecordRepository) {
        this.conversationRecordRepository = conversationRecordRepository;
    }

    @Override
    public ConversationRecord save(ConversationRecord record) {
        return conversationRecordRepository.save(record);
    }

    @Override
    public List<ConversationRecord> listByBotConfigId(Long botConfigId) {
        return conversationRecordRepository.findByBotConfigIdOrderByCreatedAtDesc(botConfigId);
    }
}
