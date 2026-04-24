package com.example.feishurobotadapter.controller;

import com.example.feishurobotadapter.dto.ApiResponse;
import com.example.feishurobotadapter.dto.BotConfigRequest;
import com.example.feishurobotadapter.dto.BotConfigResponse;
import com.example.feishurobotadapter.entity.ConversationRecord;
import com.example.feishurobotadapter.service.BotConfigService;
import com.example.feishurobotadapter.service.ConversationRecordService;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/bot-configs")
public class BotConfigController {

    private final BotConfigService botConfigService;
    private final ConversationRecordService conversationRecordService;

    public BotConfigController(BotConfigService botConfigService, ConversationRecordService conversationRecordService) {
        this.botConfigService = botConfigService;
        this.conversationRecordService = conversationRecordService;
    }

    @GetMapping
    public List<BotConfigResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "created") String sort) {
        return botConfigService.list(q, sort);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        botConfigService.delete(id);
    }

    @PostMapping
    public BotConfigResponse create(@RequestBody BotConfigRequest request) {
        return botConfigService.create(request);
    }

    @PutMapping("/{id}")
    public BotConfigResponse update(@PathVariable Long id, @RequestBody BotConfigRequest request) {
        return botConfigService.update(id, request);
    }

    @PostMapping("/{id}/long-connection")
    public BotConfigResponse toggleLongConnection(@PathVariable Long id, @RequestParam boolean enabled) {
        return botConfigService.toggleLongConnection(id, enabled);
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(@PathVariable Long id) {
        List<ConversationRecord> records = conversationRecordService.listByBotConfigId(id);
        StringBuilder csv = new StringBuilder("时间,OpenId,Dify用户标识,ChatId,飞书消息ID,Dify会话ID,问题,回答\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (ConversationRecord record : records) {
            csv.append(escapeCsv(record.getCreatedAt().format(formatter))).append(',')
                    .append(escapeCsv(record.getOpenId())).append(',')
                    .append(escapeCsv(record.getDifyUserKey())).append(',')
                    .append(escapeCsv(record.getChatId())).append(',')
                    .append(escapeCsv(record.getFeishuMessageId())).append(',')
                    .append(escapeCsv(record.getDifyConversationId())).append(',')
                    .append(escapeCsv(record.getQuestion())).append(',')
                    .append(escapeCsv(record.getAnswer())).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("bot-" + id + "-records.csv", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString());
    }

    @GetMapping("/{id}/records")
    public List<ConversationRecord> records(@PathVariable Long id) {
        return conversationRecordService.listByBotConfigId(id);
    }

    @GetMapping("/{id}/event-url")
    public ApiResponse eventUrl(@PathVariable Long id) {
        return new ApiResponse(true, "飞书 SDK 长连接模式，无需配置回调地址");
    }

    private String escapeCsv(String value) {
        String text = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + text + "\"";
    }
}
