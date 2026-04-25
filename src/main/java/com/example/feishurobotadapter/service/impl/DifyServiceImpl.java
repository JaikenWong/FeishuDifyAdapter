package com.example.feishurobotadapter.service.impl;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import com.example.feishurobotadapter.dto.DifyMessageFile;
import com.example.feishurobotadapter.dto.DifyStreamChunk;
import com.example.feishurobotadapter.dto.DifyUploadedFile;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.service.DifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Dify API 服务实现
 * - /chat-messages：流式对话，支持 files 参数
 * - /files/upload：上传图片或文件，得到 upload_file_id
 *
 * 流式响应使用 HttpURLConnection + BufferedReader.readLine() 保证按行正确解析 SSE
 */
@Service
@Log4j2
public class DifyServiceImpl implements DifyService {

    private final ObjectMapper objectMapper;

    public DifyServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<DifyStreamChunk> streamChat(
            BotConfig config,
            String userId,
            String conversationId,
            Map<String, String> inputVariables,
            String query,
            List<DifyUploadedFile> files
    ) {
        Sinks.Many<DifyStreamChunk> sink = Sinks.many().multicast().onBackpressureBuffer();

        CompletableFuture.runAsync(() -> {
            HttpURLConnection conn = null;
            try {
                String url = normalizeBaseUrl(config.getDifyUrl()) + "/chat-messages";

                Map<String, Object> bodyMap = new LinkedHashMap<>();
                Map<String, Object> inputs = new LinkedHashMap<>();
                if (inputVariables != null) {
                    inputVariables.forEach(inputs::put);
                }
                bodyMap.put("inputs", inputs);
                bodyMap.put("query", query == null ? "" : query);
                bodyMap.put("response_mode", "streaming");
                bodyMap.put("conversation_id", conversationId == null || conversationId.isBlank() ? "" : conversationId);
                bodyMap.put("user", userId);
                if (files != null && !files.isEmpty()) {
                    List<Map<String, Object>> fileList = new ArrayList<>();
                    for (DifyUploadedFile file : files) {
                        fileList.add(Map.of(
                                "type", file.type(),
                                "transfer_method", "local_file",
                                "upload_file_id", file.fileId()
                        ));
                    }
                    bodyMap.put("files", fileList);
                }
                String requestBody = objectMapper.writeValueAsString(bodyMap);

                log.info("[Dify] 开始请求: {}, conversationId={}, query 长度={}, 附件数={}",
                        url,
                        conversationId == null || conversationId.isBlank() ? "(新会话)" : conversationId,
                        query == null ? 0 : query.length(),
                        files == null ? 0 : files.size());
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
                long responseStart = System.currentTimeMillis();
                long firstSseAt = 0L;
                long firstAnswerAt = 0L;

                try (InputStream inputStream = conn.getInputStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    int chunkCount = 0;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
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
                                    if (firstSseAt == 0L) {
                                        firstSseAt = System.currentTimeMillis();
                                    }
                                    if (firstAnswerAt == 0L && chunk.answerChunk() != null && !chunk.answerChunk().isBlank()) {
                                        firstAnswerAt = System.currentTimeMillis();
                                    }
                                    chunkCount++;
                                    sink.tryEmitNext(chunk);
                                }
                            } catch (Exception ex) {
                                log.warn("[Dify] 解析 chunk 失败: data={}", data, ex);
                            }
                        }
                    }
                    sink.tryEmitNext(new DifyStreamChunk("message_end", "", null, null, true, List.of()));
                    sink.tryEmitComplete();
                    log.info("[Dify] 流式响应完成，总共 {} 个chunk", chunkCount);
                    log.info("[Dify][Perf] 首包与总耗时: firstSseMs={}, firstAnswerMs={}, streamTotalMs={}",
                            firstSseAt == 0L ? -1 : firstSseAt - responseStart,
                            firstAnswerAt == 0L ? -1 : firstAnswerAt - responseStart,
                            System.currentTimeMillis() - responseStart);
                }
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

    @Override
    public DifyUploadedFile uploadFile(BotConfig config, String userId, byte[] bytes, String filename, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Dify 文件上传字节为空");
        }
        String safeFilename = filename == null || filename.isBlank() ? "upload.bin" : filename;
        String safeMime = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
        String difyType = safeMime.toLowerCase().startsWith("image/") ? "image" : "document";

        HttpURLConnection conn = null;
        try {
            String url = normalizeBaseUrl(config.getDifyUrl()) + "/files/upload";
            String boundary = "----DifyBoundary" + UUID.randomUUID().toString().replace("-", "");
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + config.getDifyApiKey());
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);

            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                writeFormField(out, boundary, "user", userId);
                writeFormField(out, boundary, "type", difyType);

                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + escapeQuotes(safeFilename) + "\"\r\n");
                out.writeBytes("Content-Type: " + safeMime + "\r\n\r\n");
                out.write(bytes);
                out.writeBytes("\r\n");
                out.writeBytes("--" + boundary + "--\r\n");
                out.flush();
            }

            int statusCode = conn.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                String errorBody = readErrorStream(conn);
                log.error("[Dify] 文件上传失败: status={}, body={}", statusCode, errorBody);
                throw new IllegalStateException("Dify 文件上传失败，状态码：" + statusCode + "，错误：" + errorBody);
            }

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] data = new byte[4096];
                int n;
                while ((n = in.read(data)) != -1) {
                    buffer.write(data, 0, n);
                }
                JsonNode node = objectMapper.readTree(buffer.toByteArray());
                String fileId = node.path("id").asText(null);
                if (fileId == null || fileId.isBlank()) {
                    throw new IllegalStateException("Dify 文件上传未返回文件 id: " + node);
                }
                log.info("[Dify] 文件上传成功: fileId={}, name={}, type={}", fileId, safeFilename, difyType);
                return new DifyUploadedFile(fileId, difyType, safeFilename, safeMime);
            }
        } catch (Exception ex) {
            log.error("[Dify] 文件上传异常: filename={}", safeFilename, ex);
            throw new IllegalStateException("Dify 文件上传异常", ex);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void writeFormField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private String escapeQuotes(String value) {
        return value.replace("\"", "\\\"");
    }

    /**
     * 解析 Dify SSE data JSON
     * 支持的事件：
     * - message：answer 增量文本，可能包含 message_files 数组
     * - message_file：独立推送一个附件（图片/文件）
     * - message_end / workflow_finished：结束标记
     */
    private DifyStreamChunk parseDataJson(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        String event = node.path("event").asText("");
        String answer = node.path("answer").asText("");
        String taskId = node.path("task_id").asText(null);
        String conversationId = node.path("conversation_id").asText(null);
        boolean completed = "message_end".equals(event) || "workflow_finished".equals(event);

        List<DifyMessageFile> files = new ArrayList<>();
        if ("message_file".equals(event)) {
            DifyMessageFile file = parseMessageFile(node);
            if (file != null) {
                files.add(file);
            }
        } else if (node.hasNonNull("message_files") && node.path("message_files").isArray()) {
            Iterator<JsonNode> it = node.path("message_files").elements();
            while (it.hasNext()) {
                DifyMessageFile file = parseMessageFile(it.next());
                if (file != null) {
                    files.add(file);
                }
            }
        }

        return new DifyStreamChunk(event, answer, taskId, conversationId, completed, files);
    }

    private DifyMessageFile parseMessageFile(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String id = node.path("id").asText(null);
        String type = node.path("type").asText("");
        String url = node.path("url").asText(null);
        String filename = node.hasNonNull("filename")
                ? node.path("filename").asText()
                : node.path("name").asText(null);
        String mimeType = node.hasNonNull("mime_type")
                ? node.path("mime_type").asText()
                : null;
        if (url == null || url.isBlank()) {
            return null;
        }
        return new DifyMessageFile(id, type, url, filename, mimeType);
    }

    private String readErrorStream(HttpURLConnection conn) {
        try {
            InputStream errorStream = conn.getErrorStream();
            if (errorStream == null) {
                return "";
            }
            try (errorStream) {
                return new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String normalizeBaseUrl(String difyUrl) {
        String value = difyUrl.endsWith("/") ? difyUrl.substring(0, difyUrl.length() - 1) : difyUrl;
        if (value.endsWith("/v1")) {
            return value;
        }
        return value + "/v1";
    }
}
