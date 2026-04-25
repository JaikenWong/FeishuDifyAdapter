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

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean employeeAuthEnabled = Boolean.FALSE;

    @Lob
    private String employeeAuthDeniedReply;

    @Column(length = 128)
    private String employeeAuthBitableAppToken;

    @Column(length = 128)
    private String employeeAuthBitableTableId;

    @Column(length = 128)
    private String employeeAuthBitableViewId;

    @Column(length = 128)
    private String employeeAuthBitableEmployeeField = "工号";

    @Column(length = 128)
    private String difyInputNameVar = "feishu_sender_name";

    @Column(length = 128)
    private String difyInputEmployeeNoVar = "feishu_employee_no";

    @Lob
    private String difyInputMappingsJson;

    @Lob
    private String lastStatusMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
