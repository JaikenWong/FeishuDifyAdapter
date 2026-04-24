package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.BotConfigRequest;
import com.example.feishurobotadapter.dto.BotConfigResponse;
import java.util.List;

public interface BotConfigService {

    /**
     * @param sort {@code created}：按创建时间倒序（新的在前）；{@code name}：按机器人名称升序
     */
    List<BotConfigResponse> list(String keyword, String sort);

    BotConfigResponse create(BotConfigRequest request);

    BotConfigResponse update(Long id, BotConfigRequest request);

    BotConfigResponse toggleLongConnection(Long id, boolean enabled);

    void delete(Long id);
}
