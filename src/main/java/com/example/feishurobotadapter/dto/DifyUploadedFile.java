package com.example.feishurobotadapter.dto;

/**
 * 已经上传到 Dify 的文件引用，用于 chat-messages 请求里的 files 字段
 * type: image / document
 */
public record DifyUploadedFile(
        String fileId,
        String type,
        String filename,
        String mimeType
) {
}
