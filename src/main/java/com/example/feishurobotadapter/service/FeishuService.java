package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.entity.BotConfig;

public interface FeishuService {

    String createCard(BotConfig config, String markdownContent, boolean streaming);

    void updateCard(BotConfig config, String cardId, String markdownContent, boolean streaming);

    String sendCardMessage(BotConfig config, String cardId, String chatId, String originalMessageId, String chatType);
}
