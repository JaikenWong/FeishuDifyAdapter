package com.example.feishurobotadapter.repository;

import com.example.feishurobotadapter.entity.ConversationRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRecordRepository extends JpaRepository<ConversationRecord, Long> {

    List<ConversationRecord> findByBotConfigIdOrderByCreatedAtDesc(Long botConfigId);
}
