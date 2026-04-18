package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.repository.ConversationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationRecordMaintenance {

    private final ConversationRecordRepository conversationRecordRepository;

    @Transactional
    public int backfillDifyUserKeyFromOpenIdWhereNull() {
        return conversationRecordRepository.backfillDifyUserKeyFromOpenIdWhereNull();
    }
}
