package com.example.feishurobotadapter.service.impl;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import com.example.feishurobotadapter.dto.CardRenderContent;
import com.example.feishurobotadapter.dto.DifyInputMappingItem;
import com.example.feishurobotadapter.dto.DifyMessageFile;
import com.example.feishurobotadapter.dto.DifyUploadedFile;
import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.entity.ConversationRecord;
import com.example.feishurobotadapter.service.ConversationRecordService;
import com.example.feishurobotadapter.service.DifyService;
import com.example.feishurobotadapter.service.EmployeePermissionService;
import com.example.feishurobotadapter.service.FeishuService;
import com.example.feishurobotadapter.service.MessageRelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;

import lombok.extern.log4j.Log4j2;

/**
 * 消息转发服务实现
 *
 * 核心流程：
 * 1. 接入消息（text/image/file/post），对不支持类型礼貌提示
 * 2. 下载用户发来的图片/文件并上传到 Dify，以支持多模态输入
 * 3. 发送「思考中」卡片，启动定时任务每 2s 更新等待时间
 *    收到 Dify 第一个有效 chunk 后立即取消定时任务
 * 4. Dify 流式回答过程中：
 *    - 累积文本
 *    - 对 message_file 事件：图片下载并上传飞书得到 img_key，文件保留下载链接
 * 5. 最终在卡片里渲染：markdown 文本 + 图片元素 + 文件下载列表
 */
@Service
@Log4j2
public class MessageRelayServiceImpl implements MessageRelayService {

    private static final String THINKING_INITIAL = "**思考中…**\n\n请稍候，正在连接 Dify。";
    private static final String THINKING_PATTERN = "**思考中…**\n\n⏳ 已等待 %d 秒";
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);
    private static final long THINKING_REFRESH_SECONDS = 2;
    private static final int UPDATE_INTERVAL_CHARS = 20;

    private final DifyService difyService;
    private final FeishuService feishuService;
    private final EmployeePermissionService employeePermissionService;
    private final ConversationRecordService conversationRecordService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService = new ThreadPoolExecutor(
            4, 32, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
            r -> { Thread t = new Thread(r, "message-relay"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(2,
            r -> { Thread t = new Thread(r, "message-scheduler"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<String, Long> messageCache = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public MessageRelayServiceImpl(
            DifyService difyService,
            FeishuService feishuService,
            EmployeePermissionService employeePermissionService,
            ConversationRecordService conversationRecordService,
            ObjectMapper objectMapper
    ) {
        this.difyService = difyService;
        this.feishuService = feishuService;
        this.employeePermissionService = employeePermissionService;
        this.conversationRecordService = conversationRecordService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        scheduledExecutor.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void process(BotConfig config, P2MessageReceiveV1 event) {
        String sourceMessageId = event.getEvent().getMessage().getMessageId();
        if (messageCache.putIfAbsent(sourceMessageId, System.currentTimeMillis()) != null) {
            log.info("[MessageRelay] 消息已处理，跳过: {}", sourceMessageId);
            return;
        }
        log.info("[MessageRelay] 收到新消息: messageId={}, robot={}", sourceMessageId, config.getRobotName());
        try {
            executorService.submit(() -> handleAsync(config, event));
        } catch (RejectedExecutionException ex) {
            messageCache.remove(sourceMessageId);
            log.error("[MessageRelay] 线程池/队列已满，无法排队处理: messageId={}", sourceMessageId, ex);
            throw ex;
        }
    }

    private void handleAsync(BotConfig config, P2MessageReceiveV1 event) {
        String messageType = event.getEvent().getMessage().getMessageType();
        String sourceMessageId = event.getEvent().getMessage().getMessageId();
        String openId = event.getEvent().getSender().getSenderId().getOpenId();
        String chatId = event.getEvent().getMessage().getChatId();
        String chatType = event.getEvent().getMessage().getChatType();
        String contentJson = event.getEvent().getMessage().getContent();

        if (!isSupportedMessageType(messageType)) {
            log.info("[MessageRelay] 不支持的消息类型: {}", messageType);
            safeReplyTip(config, chatId, sourceMessageId, chatType,
                    "**暂不支持此类消息**\n\n目前可以处理：文本、图片、文件、富文本（post）。");
            messageCache.remove(sourceMessageId);
            return;
        }

        ParsedIncomingMessage parsed;
        try {
            parsed = parseIncoming(messageType, contentJson);
        } catch (Exception ex) {
            log.warn("[MessageRelay] 解析用户消息失败: type={}, content={}", messageType, contentJson, ex);
            safeReplyTip(config, chatId, sourceMessageId, chatType, "**消息解析失败**，请重试或稍后再试。");
            messageCache.remove(sourceMessageId);
            return;
        }

        log.info("[MessageRelay] 问题内容: {}, 附件数: {}", parsed.text(), parsed.attachments().size());

        var senderProfile = feishuService.resolveSenderProfile(config, event);
        boolean hasPermission = employeePermissionService.hasPermission(config, senderProfile, openId);
        if (!hasPermission) {
            String denyReply = resolveDeniedReply(config);
            log.info("[MessageRelay] 工号鉴权未通过，直接回复固定文案。openId={}", openId);
            safeReplyTip(config, chatId, sourceMessageId, chatType, denyReply);
            messageCache.remove(sourceMessageId);
            return;
        }

        String unionId = event.getEvent().getSender().getSenderId().getUnionId();
        String difyUserId = resolveDifyUserId(senderProfile, openId, unionId);
        if (isClearCommand(parsed)) {
            int cleared = conversationRecordService.clearConversationContext(config.getId(), difyUserId, chatId);
            log.info("[MessageRelay] 用户触发 /clear，已清空会话上下文: botId={}, chatId={}, user={}, affected={}",
                    config.getId(), chatId, difyUserId, cleared);
            safeReplyTip(config, chatId, sourceMessageId, chatType, "**已清空上下文**\n\n下一条消息将开启新会话。");
            messageCache.remove(sourceMessageId);
            return;
        }

        AtomicReference<String> cardId = new AtomicReference<>();
        AtomicReference<String> responseMessageId = new AtomicReference<>();
        StringBuilder answer = new StringBuilder();
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<String> conversationId = new AtomicReference<>();
        List<String> cachedImageKeys = Collections.synchronizedList(new ArrayList<>());
        List<CardRenderContent.FileLink> cachedFileLinks = Collections.synchronizedList(new ArrayList<>());
        ThinkTagFilter thinkTagFilter = new ThinkTagFilter();
        AtomicLong firstDifyChunkAt = new AtomicLong(0);
        AtomicLong firstVisibleTextAt = new AtomicLong(0);
        AtomicLong firstFeishuUpdateAt = new AtomicLong(0);
        AtomicInteger cardUpdateCount = new AtomicInteger(0);
        AtomicLong totalCardUpdateCostMs = new AtomicLong(0);
        AtomicBoolean firstChunkArrived = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> thinkingFuture = new AtomicReference<>();
        Object updateLock = new Object();
        long startTime = System.currentTimeMillis();

        try {
            cardId.set(feishuService.createCard(config, CardRenderContent.ofText(THINKING_INITIAL), true));
            log.info("[MessageRelay] 创建飞书卡片成功: cardId={}", cardId.get());

            responseMessageId.set(feishuService.sendCardMessage(config, cardId.get(), chatId, sourceMessageId, chatType));
            log.info("[MessageRelay] 发送卡片消息成功: messageId={}", responseMessageId.get());

            thinkingFuture.set(scheduledExecutor.scheduleAtFixedRate(() -> {
                if (firstChunkArrived.get()) {
                    return;
                }
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                String text = String.format(THINKING_PATTERN, elapsed);
                try {
                    synchronized (updateLock) {
                        if (!firstChunkArrived.get()) {
                            feishuService.updateCard(config, cardId.get(), CardRenderContent.ofText(text), true);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[MessageRelay] 更新思考中文案失败", ex);
                }
            }, THINKING_REFRESH_SECONDS, THINKING_REFRESH_SECONDS, TimeUnit.SECONDS));

            Map<String, String> difyInputs = new LinkedHashMap<>();
            applyConfiguredDifyInputs(config, senderProfile, openId, unionId, difyInputs);
            if (unionId != null && !unionId.isBlank()) {
                difyInputs.putIfAbsent("feishu_union_id", unionId);
            }
            log.info(
                    "[MessageRelay] Dify 用户标识 user={} (工号优先，否则 open_id/union_id), openId={}, profile={}, inputs={}",
                    difyUserId,
                    openId,
                    senderProfile,
                    difyInputs.keySet()
            );

            List<DifyUploadedFile> difyFiles = uploadAttachmentsToDify(config, difyUserId, sourceMessageId, parsed.attachments());
            String question = parsed.text();
            if ((question == null || question.isBlank()) && !difyFiles.isEmpty()) {
                question = "请基于我发送的附件回答或总结。";
            }

            String difyConversationId = conversationRecordService
                    .findLatestDifyConversationId(config.getId(), difyUserId, chatId)
                    .orElse(null);
            if (difyConversationId != null) {
                log.info("[MessageRelay] Dify 续聊: conversationId={}, chatId={}", difyConversationId, chatId);
            }

            int[] chunkCount = {0};
            final String finalQuestion = question;
            String convToUse = difyConversationId;
            for (int attempt = 0; attempt < 2; attempt++) {
                final long streamAttemptStart = System.currentTimeMillis();
                try {
                    if (attempt > 0) {
                        prepareDifyStreamRetry(
                                chunkCount,
                                conversationId,
                                taskId,
                                answer,
                                firstChunkArrived,
                                cachedImageKeys,
                                cachedFileLinks
                        );
                        thinkTagFilter.reset();
                    }
                    difyService.streamChat(config, difyUserId, convToUse, difyInputs, finalQuestion, difyFiles)
                            .doOnNext(chunk -> {
                                chunkCount[0]++;
                                firstDifyChunkAt.compareAndSet(0, System.currentTimeMillis());

                                String visibleChunk = thinkTagFilter.filter(chunk.answerChunk());
                                boolean hasText = visibleChunk != null && !visibleChunk.isBlank();
                                if (hasText) {
                                    firstVisibleTextAt.compareAndSet(0, System.currentTimeMillis());
                                }
                                boolean hasFiles = chunk.newFiles() != null && !chunk.newFiles().isEmpty();
                                boolean isCompleted = chunk.completed();

                                if ((hasText || hasFiles || isCompleted) && firstChunkArrived.compareAndSet(false, true)) {
                                    ScheduledFuture<?> future = thinkingFuture.get();
                                    if (future != null) {
                                        future.cancel(false);
                                    }
                                }

                                if (chunk.taskId() != null && !chunk.taskId().isBlank()) {
                                    taskId.set(chunk.taskId());
                                }
                                if (chunk.conversationId() != null && !chunk.conversationId().isBlank()) {
                                    conversationId.set(chunk.conversationId());
                                }
                                if (hasText) {
                                    answer.append(visibleChunk);
                                }
                                if (hasFiles) {
                                    processDifyAttachments(config, chunk.newFiles(), cachedImageKeys, cachedFileLinks);
                                }

                                int len = answer.length();
                                boolean hasNewFiles = hasFiles && !chunk.newFiles().isEmpty();
                                // 仅在有可见新增文本/附件/结束事件时更新卡片，避免 answerLen=0 时高频空刷新。
                                boolean needUpdate = isCompleted
                                        || hasNewFiles
                                        || (hasText && ((len % UPDATE_INTERVAL_CHARS == 0) || (len < UPDATE_INTERVAL_CHARS)));

                                if (needUpdate) {
                                    CardRenderContent content = buildCardContent(answer.toString(), cachedImageKeys, cachedFileLinks);
                                    try {
                                        long updateStart = System.currentTimeMillis();
                                        synchronized (updateLock) {
                                            feishuService.updateCard(config, cardId.get(), content, !isCompleted);
                                        }
                                        long cost = System.currentTimeMillis() - updateStart;
                                        totalCardUpdateCostMs.addAndGet(cost);
                                        int updateNo = cardUpdateCount.incrementAndGet();
                                        firstFeishuUpdateAt.compareAndSet(0, System.currentTimeMillis());
                                        log.debug("[MessageRelay][Perf] 飞书卡片更新完成: no={}, costMs={}, completed={}, answerLen={}",
                                                updateNo, cost, isCompleted, answer.length());
                                    } catch (Exception ex) {
                                        log.warn("[MessageRelay] 更新回答卡片失败", ex);
                                    }
                                }
                            })
                            .blockLast(STREAM_TIMEOUT);
                    log.info("[MessageRelay][Perf] Dify 流读取完成: attempt={}, streamCostMs={}, chunkCount={}",
                            attempt + 1, System.currentTimeMillis() - streamAttemptStart, chunkCount[0]);
                    break;
                } catch (Exception ex) {
                    if (attempt == 0
                            && convToUse != null
                            && !convToUse.isBlank()
                            && isLikelyStaleDifyConversationError(ex)) {
                        log.warn(
                                "[MessageRelay] Dify conversation_id 可能已在服务端失效，将清空后重试新会话。首次错误: {}",
                                ex.getMessage()
                        );
                        convToUse = null;
                        continue;
                    }
                    throw ex;
                }
            }

            ScheduledFuture<?> finalFuture = thinkingFuture.get();
            if (finalFuture != null) {
                finalFuture.cancel(false);
            }

            log.info("[MessageRelay] Dify 流式响应完成，总共 {} 个chunk，最终回答长度: {}", chunkCount[0], answer.length());
            long now = System.currentTimeMillis();
            log.info("[MessageRelay][Perf] 总耗时统计: totalMs={}, firstDifyChunkMs={}, firstVisibleTextMs={}, firstFeishuUpdateMs={}, feishuUpdateCount={}, feishuUpdateTotalCostMs={}, feishuUpdateAvgCostMs={}",
                    now - startTime,
                    firstDifyChunkAt.get() == 0 ? -1 : firstDifyChunkAt.get() - startTime,
                    firstVisibleTextAt.get() == 0 ? -1 : firstVisibleTextAt.get() - startTime,
                    firstFeishuUpdateAt.get() == 0 ? -1 : firstFeishuUpdateAt.get() - startTime,
                    cardUpdateCount.get(),
                    totalCardUpdateCostMs.get(),
                    cardUpdateCount.get() == 0 ? -1 : (totalCardUpdateCostMs.get() / cardUpdateCount.get()));

            ConversationRecord record = new ConversationRecord();
            record.setBotConfigId(config.getId());
            record.setOpenId(openId);
            record.setDifyUserKey(difyUserId);
            record.setChatId(chatId);
            record.setFeishuMessageId(responseMessageId.get());
            record.setDifyConversationId(conversationId.get());
            record.setDifyTaskId(taskId.get());
            record.setQuestion(buildQuestionForRecord(parsed, difyFiles));
            record.setAnswer(buildAnswerForRecord(answer.toString(), cachedFileLinks, cachedImageKeys.size()));
            record.setCreatedAt(LocalDateTime.now());
            conversationRecordService.save(record);

            messageCache.remove(sourceMessageId);
            log.info("[MessageRelay] 消息处理完成");
        } catch (Exception ex) {
            ScheduledFuture<?> future = thinkingFuture.get();
            if (future != null) {
                future.cancel(false);
            }
            if (cardId.get() != null) {
                try {
                    CardRenderContent errorContent = CardRenderContent.ofText("**调用 Dify 失败**：" + ex.getMessage());
                    synchronized (updateLock) {
                        feishuService.updateCard(config, cardId.get(), errorContent, false);
                    }
                } catch (Exception inner) {
                    log.warn("[MessageRelay] 刷新错误文案失败", inner);
                }
            }
            messageCache.remove(sourceMessageId);
            log.error("[MessageRelay] 处理消息失败", ex);
        }
    }

    private boolean isSupportedMessageType(String type) {
        return "text".equals(type) || "image".equals(type) || "file".equals(type) || "post".equals(type);
    }

    private ParsedIncomingMessage parseIncoming(String messageType, String contentJson) throws Exception {
        JsonNode node = objectMapper.readTree(contentJson);
        StringBuilder text = new StringBuilder();
        List<IncomingAttachment> attachments = new ArrayList<>();

        switch (messageType) {
            case "text" -> text.append(node.path("text").asText(""));
            case "image" -> {
                String imageKey = node.path("image_key").asText(null);
                if (imageKey != null && !imageKey.isBlank()) {
                    attachments.add(new IncomingAttachment(imageKey, "image",
                            node.path("image_name").asText("image.png"),
                            "image/png"));
                }
            }
            case "file" -> {
                String fileKey = node.path("file_key").asText(null);
                if (fileKey != null && !fileKey.isBlank()) {
                    String filename = node.path("file_name").asText("file.bin");
                    attachments.add(new IncomingAttachment(fileKey, "file", filename, guessMimeType(filename)));
                }
            }
            case "post" -> parsePostContent(node, text, attachments);
            default -> { /* already filtered above */ }
        }

        return new ParsedIncomingMessage(text.toString().trim(), attachments);
    }

    private void parsePostContent(JsonNode root, StringBuilder text, List<IncomingAttachment> attachments) {
        String title = root.path("title").asText("");
        if (!title.isBlank()) {
            text.append(title).append('\n');
        }
        JsonNode contentArray = root.path("content");
        if (!contentArray.isArray()) {
            return;
        }
        Iterator<JsonNode> lines = contentArray.elements();
        while (lines.hasNext()) {
            JsonNode lineNode = lines.next();
            if (!lineNode.isArray()) {
                continue;
            }
            Iterator<JsonNode> segments = lineNode.elements();
            StringBuilder lineText = new StringBuilder();
            while (segments.hasNext()) {
                JsonNode seg = segments.next();
                String tag = seg.path("tag").asText("");
                switch (tag) {
                    case "text", "md" -> lineText.append(seg.path("text").asText(""));
                    case "a" -> {
                        String href = seg.path("href").asText("");
                        String label = seg.path("text").asText(href);
                        lineText.append(label).append('(').append(href).append(')');
                    }
                    case "at" -> lineText.append('@').append(seg.path("user_name").asText(""));
                    case "img" -> {
                        String imageKey = seg.path("image_key").asText(null);
                        if (imageKey != null && !imageKey.isBlank()) {
                            attachments.add(new IncomingAttachment(imageKey, "image", "image.png", "image/png"));
                        }
                    }
                    case "file" -> {
                        String fileKey = seg.path("file_key").asText(null);
                        if (fileKey != null && !fileKey.isBlank()) {
                            String filename = seg.path("file_name").asText("file.bin");
                            attachments.add(new IncomingAttachment(fileKey, "file", filename, guessMimeType(filename)));
                        }
                    }
                    default -> {
                        // 其它 tag 忽略
                    }
                }
            }
            if (!lineText.isEmpty()) {
                text.append(lineText).append('\n');
            }
        }
    }

    /**
     * 与 Dify {@code chat-messages} 的 {@code user} 一致：优先工号，否则 open_id / union_id。
     */
    private static String resolveDifyUserId(FeishuSenderProfile profile, String openId, String unionId) {
        if (profile != null && profile.employeeNo() != null && !profile.employeeNo().isBlank()) {
            return profile.employeeNo().trim();
        }
        if (openId != null && !openId.isBlank()) {
            return openId.trim();
        }
        if (unionId != null && !unionId.isBlank()) {
            return unionId.trim();
        }
        return "feishu-unknown";
    }

    private List<DifyUploadedFile> uploadAttachmentsToDify(
            BotConfig config,
            String difyUserId,
            String messageId,
            List<IncomingAttachment> attachments
    ) {
        List<DifyUploadedFile> uploaded = new ArrayList<>();
        for (IncomingAttachment att : attachments) {
            try {
                byte[] bytes = feishuService.downloadMessageResource(config, messageId, att.fileKey(), att.feishuType());
                DifyUploadedFile file = difyService.uploadFile(config, difyUserId, bytes, att.filename(), att.mimeType());
                uploaded.add(file);
            } catch (Exception ex) {
                log.warn("[MessageRelay] 附件上传到 Dify 失败: filename={}", att.filename(), ex);
            }
        }
        return uploaded;
    }

    private void processDifyAttachments(
            BotConfig config,
            List<DifyMessageFile> newFiles,
            List<String> cachedImageKeys,
            List<CardRenderContent.FileLink> cachedFileLinks
    ) {
        for (DifyMessageFile file : newFiles) {
            if (file.isImage()) {
                try {
                    byte[] bytes = downloadRemoteBytes(file.url());
                    String imgKey = feishuService.uploadImage(config, bytes);
                    cachedImageKeys.add(imgKey);
                } catch (Exception ex) {
                    log.warn("[MessageRelay] Dify 图片中转失败，退化为链接: url={}", file.url(), ex);
                    cachedFileLinks.add(new CardRenderContent.FileLink(
                            file.filename() != null ? file.filename() : "图片", file.url()));
                }
            } else {
                cachedFileLinks.add(new CardRenderContent.FileLink(
                        file.filename() != null ? file.filename() : "附件", file.url()));
            }
        }
    }

    private CardRenderContent buildCardContent(String markdown, List<String> imageKeys, List<CardRenderContent.FileLink> fileLinks) {
        CardRenderContent content = new CardRenderContent(markdown == null || markdown.isBlank() ? "…" : markdown);
        synchronized (imageKeys) {
            for (String key : imageKeys) {
                content.addImageKey(key);
            }
        }
        synchronized (fileLinks) {
            for (CardRenderContent.FileLink link : fileLinks) {
                content.addFileLink(link.filename(), link.url());
            }
        }
        return content;
    }

    private String buildQuestionForRecord(ParsedIncomingMessage parsed, List<DifyUploadedFile> difyFiles) {
        if (parsed.attachments().isEmpty() && difyFiles.isEmpty()) {
            return parsed.text();
        }
        StringBuilder sb = new StringBuilder(parsed.text() == null ? "" : parsed.text());
        if (!parsed.attachments().isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("[用户附件]\n");
            for (IncomingAttachment att : parsed.attachments()) {
                sb.append("- ").append(att.feishuType()).append(": ").append(att.filename()).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildAnswerForRecord(String answer, List<CardRenderContent.FileLink> fileLinks, int imageCount) {
        if (fileLinks.isEmpty() && imageCount == 0) {
            return answer;
        }
        StringBuilder sb = new StringBuilder(answer == null ? "" : answer);
        if (imageCount > 0) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("[图片] 共 ").append(imageCount).append(" 张");
        }
        if (!fileLinks.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("[附件]\n");
            for (CardRenderContent.FileLink link : fileLinks) {
                sb.append("- ").append(link.filename()).append(": ").append(link.url()).append('\n');
            }
        }
        return sb.toString();
    }

    private byte[] downloadRemoteBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<InputStream> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("下载远端资源失败，status=" + resp.statusCode() + ", url=" + url);
        }
        try (InputStream in = resp.body()) {
            return in.readAllBytes();
        }
    }

    private String guessMimeType(String filename) {
        if (filename == null) {
            return "application/octet-stream";
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".md")) return "text/markdown";
        return "application/octet-stream";
    }

    private void safeReplyTip(BotConfig config, String chatId, String sourceMessageId, String chatType, String markdown) {
        try {
            String tipCardId = feishuService.createCard(config, CardRenderContent.ofText(markdown), false);
            feishuService.sendCardMessage(config, tipCardId, chatId, sourceMessageId, chatType);
        } catch (Exception ex) {
            log.warn("[MessageRelay] 发送提示卡片失败", ex);
        }
    }

    private String resolveDeniedReply(BotConfig config) {
        String configured = config.getEmployeeAuthDeniedReply();
        if (configured == null || configured.isBlank()) {
            return "**暂无权限**\n\n当前账号无访问权限，请联系管理员开通。";
        }
        return configured;
    }

    /** 重试新会话前清理本轮已累积的流式状态，避免脏数据叠在卡片上。 */
    private static void prepareDifyStreamRetry(
            int[] chunkCount,
            AtomicReference<String> conversationId,
            AtomicReference<String> taskId,
            StringBuilder answer,
            AtomicBoolean firstChunkArrived,
            List<String> cachedImageKeys,
            List<CardRenderContent.FileLink> cachedFileLinks
    ) {
        chunkCount[0] = 0;
        conversationId.set(null);
        taskId.set(null);
        answer.setLength(0);
        firstChunkArrived.set(false);
        cachedImageKeys.clear();
        cachedFileLinks.clear();
    }

    /**
     * 根据 Dify/网关返回判断是否为「旧 conversation_id 无效」类错误，适合清空会话 ID 后重试一次。
     */
    private static boolean isLikelyStaleDifyConversationError(Throwable ex) {
        String msg = deepMessages(ex);
        if (msg.isBlank()) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        boolean clientError =
                msg.contains("状态码：400")
                        || msg.contains("状态码：404")
                        || lower.contains("status code: 400")
                        || lower.contains("status code: 404");
        if (!clientError) {
            return false;
        }
        return lower.contains("conversation")
                || msg.contains("会话")
                || lower.contains("dialog");
    }

    private static String deepMessages(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = ex; t != null && sb.length() < 4000; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean isClearCommand(ParsedIncomingMessage parsed) {
        if (parsed == null || parsed.attachments() == null || !parsed.attachments().isEmpty()) {
            return false;
        }
        String text = parsed.text();
        return text != null && "/clear".equalsIgnoreCase(text.trim());
    }

    /**
     * 过滤模型输出中的 <think>...</think>，支持标签跨 chunk 断裂场景。
     */
    private static final class ThinkTagFilter {
        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";
        private String pending = "";
        private boolean inThink = false;

        String filter(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }
            pending += chunk;
            StringBuilder out = new StringBuilder();
            while (true) {
                String lower = pending.toLowerCase(Locale.ROOT);
                if (inThink) {
                    int closeIdx = lower.indexOf(CLOSE);
                    if (closeIdx < 0) {
                        int keep = Math.min(pending.length(), CLOSE.length() - 1);
                        pending = pending.substring(pending.length() - keep);
                        return out.toString();
                    }
                    pending = pending.substring(closeIdx + CLOSE.length());
                    inThink = false;
                    continue;
                }
                int openIdx = lower.indexOf(OPEN);
                if (openIdx < 0) {
                    int keep = keepPartialSuffix(pending, OPEN);
                    out.append(pending, 0, pending.length() - keep);
                    pending = pending.substring(pending.length() - keep);
                    return out.toString();
                }
                out.append(pending, 0, openIdx);
                pending = pending.substring(openIdx + OPEN.length());
                inThink = true;
            }
        }

        void reset() {
            pending = "";
            inThink = false;
        }

        private int keepPartialSuffix(String value, String token) {
            int max = Math.min(value.length(), token.length() - 1);
            for (int len = max; len > 0; len--) {
                String suffix = value.substring(value.length() - len).toLowerCase(Locale.ROOT);
                String prefix = token.substring(0, len).toLowerCase(Locale.ROOT);
                if (suffix.equals(prefix)) {
                    return len;
                }
            }
            return 0;
        }
    }

    private void applyConfiguredDifyInputs(BotConfig config, FeishuSenderProfile senderProfile, String openId, String unionId, Map<String, String> difyInputs) {
        List<DifyInputMappingItem> mappings = readDifyInputMappings(config);
        for (DifyInputMappingItem item : mappings) {
            if (item == null || item.variable() == null || item.variable().isBlank()) {
                continue;
            }
            String value = resolveSourceValue(item.source(), senderProfile, openId, unionId);
            if (value != null && !value.isBlank()) {
                difyInputs.put(item.variable().trim(), value.trim());
            }
        }
    }

    private List<DifyInputMappingItem> readDifyInputMappings(BotConfig config) {
        return DifyInputMappingItem.fromConfigJson(
                config.getDifyInputMappingsJson(), config.getDifyInputNameVar(), config.getDifyInputEmployeeNoVar());
    }

    private static String resolveSourceValue(String source, FeishuSenderProfile senderProfile, String openId, String unionId) {
        if (source == null) {
            return null;
        }
        return switch (source.toLowerCase(Locale.ROOT)) {
            case "display_name" -> senderProfile != null ? senderProfile.displayName() : null;
            case "full_name" -> senderProfile != null ? senderProfile.fullName() : null;
            case "employee_no" -> senderProfile != null ? senderProfile.employeeNo() : null;
            case "email" -> senderProfile != null ? senderProfile.email() : null;
            case "en_name" -> senderProfile != null ? senderProfile.enName() : null;
            case "open_id" -> openId;
            case "union_id" -> unionId;
            default -> null;
        };
    }

    private record ParsedIncomingMessage(String text, List<IncomingAttachment> attachments) {
    }

    private record IncomingAttachment(String fileKey, String feishuType, String filename, String mimeType) {
    }
}
