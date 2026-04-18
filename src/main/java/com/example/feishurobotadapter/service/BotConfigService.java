package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.BotConfigRequest;
import com.example.feishurobotadapter.dto.BotConfigResponse;
import java.util.List;

public interface BotConfigService {

    List<BotConfigResponse> list();

    BotConfigResponse create(BotConfigRequest request);

    BotConfigResponse toggleLongConnection(Long id, boolean enabled);
}
