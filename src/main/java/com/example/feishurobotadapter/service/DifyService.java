package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.DifyStreamChunk;
import com.example.feishurobotadapter.dto.DifyUploadedFile;
import com.example.feishurobotadapter.entity.BotConfig;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

public interface DifyService {

    /**
     * 调用 Dify 流式对话
     * @param conversationId 已有 Dify 会话 ID 时传入以续聊；{@code null} 或空则新开会话
     * @param inputVariables 传入 Dify 的 {@code inputs}（如 {@code feishu_sender_name}），可为 {@code null} 或空
     * @param files 已经通过 uploadFile 得到的文件引用，可为空
     */
    Flux<DifyStreamChunk> streamChat(
            BotConfig config,
            String userId,
            String conversationId,
            Map<String, String> inputVariables,
            String query,
            List<DifyUploadedFile> files
    );

    /**
     * 将字节上传到 Dify /files/upload
     * @return 上传完成后的文件引用（包含 fileId 与 type）
     */
    DifyUploadedFile uploadFile(BotConfig config, String userId, byte[] bytes, String filename, String mimeType);
}
