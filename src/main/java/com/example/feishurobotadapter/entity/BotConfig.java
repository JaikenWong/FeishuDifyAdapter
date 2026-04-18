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
@Table(name = "bot_configs")
public class BotConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String robotName;

    @Column(nullable = false, length = 128)
    private String appId;

    @Column(nullable = false, length = 256)
    private String appSecret;

    @Column(nullable = false, length = 512)
    private String difyUrl;

    @Column(nullable = false, length = 256)
    private String difyApiKey;

    @Column(length = 128)
    private String verificationToken;

    @Column(length = 128)
    private String encryptKey;

    @Column(nullable = false)
    private Boolean longConnectionEnabled = Boolean.FALSE;

    @Lob
    private String lastStatusMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
