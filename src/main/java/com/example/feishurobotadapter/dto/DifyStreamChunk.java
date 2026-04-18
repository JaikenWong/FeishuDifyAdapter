package com.example.feishurobotadapter.dto;

import java.util.List;

/**
 * Dify SSE 单次增量信息
 * - answerChunk: 增量文本（可能为空）
 * - newFiles: 本次 chunk 新带出的附件（图片/文件），通常来自 message_file 事件
 */
public record DifyStreamChunk(
        String event,
        String answerChunk,
        String taskId,
        String conversationId,
        boolean completed,
        List<DifyMessageFile> newFiles
) {
    public DifyStreamChunk(String event, String answerChunk, String taskId, String conversationId, boolean completed) {
        this(event, answerChunk, taskId, conversationId, completed, List.of());
    }
}
