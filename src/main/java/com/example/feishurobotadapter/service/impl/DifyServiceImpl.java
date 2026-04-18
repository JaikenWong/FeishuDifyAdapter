package com.example.feishurobotadapter.service.impl;

import com.example.feishurobotadapter.dto.DifyStreamChunk;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.service.DifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Dify API 服务实现
 * 负责调用 Dify 的流式聊天接口，解析 SSE 响应并返回给上层处理
 *
 * 这里使用传统的 HttpURLConnection + BufferedReader.readLine() 来保证按行正确解析SSE
 * 参考 plm-mcp-server 项目的成熟实现，比 WebFlux WebClient 更稳定可靠
 */
@Service
@Log4j2
public class DifyServiceImpl implements DifyService {

    private final ObjectMapper objectMapper;

    public DifyServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 Dify 流式聊天接口
     * @param config 机器人配置（包含 Dify URL 和 API Key）
     * @param userId 用户ID（飞书 OpenId）
     * @param query 用户问题
     * @return Dify 流式响应块 Flux
     */
    @Override
    public Flux<DifyStreamChunk> streamChat(BotConfig config, String userId, String query) {
        // 使用 Sinks 来桥接同步的回调到 Flux
        Sinks.Many<DifyStreamChunk> sink = Sinks.many().multicast().onBackpressureBuffer();

        // 在后台线程执行HTTP请求读取
        CompletableFuture.runAsync(() -> {
            HttpURLConnection conn = null;
            try {
                String url = normalizeBaseUrl(config.getDifyUrl()) + "/chat-messages";
                Map<String, Object> bodyMap = Map.of(
                        "inputs", Map.of(),
                        "query", query,
                        "response_mode", "streaming",
                        "conversation_id", "",
                        "user", userId
                );
                String requestBody = objectMapper.writeValueAsString(bodyMap);

                log.info("[Dify] 开始请求: {}, query: {}", url, query);
                byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
                conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + config.getDifyApiKey());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setDoInput(true);
                conn.getOutputStream().write(bodyBytes);

                int statusCode = conn.getResponseCode();
                if (statusCode != 200) {
                    String errorBody = readErrorStream(conn);
                    log.error("[Dify] API 请求失败: status={}, body={}", statusCode, errorBody);
                    sink.tryEmitError(new RuntimeException("Dify API 请求失败，状态码：" + statusCode + "，错误：" + errorBody));
                    return;
                }

                log.info("[Dify] 连接建立成功，开始读取流式响应");

                // 使用 BufferedReader 逐行读取，保证SSE正确按行分割
                InputStream inputStream = conn.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

                String line;
                int chunkCount = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        // 移除 "data:" 前缀，处理可能的空格
                        String data = line.substring(5).trim();
                        if (data.isBlank()) {
                            continue;
                        }
                        if ("[DONE]".equals(data)) {
                            log.info("[Dify] 收到 [DONE]，结束读取");
                            break;
                        }
                        try {
                            DifyStreamChunk chunk = parseDataJson(data);
                            if (chunk != null) {
                                chunkCount++;
                                sink.tryEmitNext(chunk);
                            }
                        } catch (Exception ex) {
                            log.warn("[Dify] 解析 chunk 失败: data={}", data, ex);
                        }
                    }
                }

                // 最后发送结束标记
                sink.tryEmitNext(new DifyStreamChunk("message_end", "", null, null, true));
                sink.tryEmitComplete();

                log.info("[Dify] 流式响应完成，总共 {} 个chunk", chunkCount);

                reader.close();
                inputStream.close();
            } catch (Exception ex) {
                log.error("[Dify] 调用异常", ex);
                sink.tryEmitError(ex);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });

        return sink.asFlux();
    }

    /**
     * 解析 data 字段的 JSON
     * Dify SSE 格式说明:
     * - message 事件: 流式返回文本片段，answer 字段是增量文本
     * - message_end 事件: 流式结束
     * - workflow_finished 事件: 工作流结束
     */
    private DifyStreamChunk parseDataJson(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        String event = node.path("event").asText("");
        String answer = node.path("answer").asText("");
        String taskId = node.path("task_id").asText(null);
        String conversationId = node.path("conversation_id").asText(null);
        boolean completed = "message_end".equals(event) || "workflow_finished".equals(event);

        // 只在debug需要的时候打开，默认不打印每个chunk，避免日志过多
        // 如果需要调试可以取消注释下面这行
        // if (!answer.isBlank()) {
        //     log.debug("[Dify] 收到增量文本: event={}, 长度={}", event, answer.length());
        // }

        return new DifyStreamChunk(event, answer, taskId, conversationId, completed);
    }

    /**
     * 读取错误响应内容
     */
    private String readErrorStream(HttpURLConnection conn) {
        try {
            InputStream errorStream = conn.getErrorStream();
            if (errorStream == null) {
                return "";
            }
            return new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 标准化 Dify URL
     * - 移除末尾的斜杠
     * - 如果没有 /v1 后缀，自动添加
     */
    private String normalizeBaseUrl(String difyUrl) {
        String value = difyUrl.endsWith("/") ? difyUrl.substring(0, difyUrl.length() - 1) : difyUrl;
        if (value.endsWith("/v1")) {
            return value;
        }
        return value + "/v1";
    }
}
