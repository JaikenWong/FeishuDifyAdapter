package com.example.feishurobotadapter.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "conversation_records")
public class ConversationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long botConfigId;

    @Column(length = 128)
    private String openId;

    @Column(length = 128)
    private String chatId;

    @Column(length = 128)
    private String feishuMessageId;

    @Column(length = 128)
    private String difyConversationId;

    @Column(length = 128)
    private String difyTaskId;

    @Lob
    @Column(nullable = false)
    private String question;

    @Lob
    private String answer;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
