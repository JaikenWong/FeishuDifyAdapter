package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.DifyStreamChunk;
import com.example.feishurobotadapter.entity.BotConfig;
import reactor.core.publisher.Flux;

public interface DifyService {

    Flux<DifyStreamChunk> streamChat(BotConfig config, String userId, String query);
}
