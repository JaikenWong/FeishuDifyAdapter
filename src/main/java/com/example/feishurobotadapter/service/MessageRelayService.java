package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.entity.BotConfig;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;

public interface MessageRelayService {

    void process(BotConfig config, P2MessageReceiveV1 event);
}
