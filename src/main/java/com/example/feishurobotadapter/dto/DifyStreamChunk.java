package com.example.feishurobotadapter.dto;

public record DifyStreamChunk(
        String event,
        String answerChunk,
        String taskId,
        String conversationId,
        boolean completed
) {
}
