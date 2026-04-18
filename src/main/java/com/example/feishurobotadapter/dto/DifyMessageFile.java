package com.example.feishurobotadapter.dto;

/**
 * Dify 流式响应中的附件信息
 * 对应 SSE 中 message_file 事件，或 message 事件里的 message_files 数组
 */
public record DifyMessageFile(
        String id,
        String type,
        String url,
        String filename,
        String mimeType
) {
    public boolean isImage() {
        return "image".equalsIgnoreCase(type);
    }
}
