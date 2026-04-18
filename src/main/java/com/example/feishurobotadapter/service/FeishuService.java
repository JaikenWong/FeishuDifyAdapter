package com.example.feishurobotadapter.service;

import com.example.feishurobotadapter.dto.CardRenderContent;
import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;

public interface FeishuService {

    String createCard(BotConfig config, CardRenderContent content, boolean streaming);

    void updateCard(BotConfig config, String cardId, CardRenderContent content, boolean streaming);

    String sendCardMessage(BotConfig config, String cardId, String chatId, String originalMessageId, String chatType);

    /**
     * 下载飞书消息附件（图片或文件）
     * @param type 资源类型：image / file
     */
    byte[] downloadMessageResource(BotConfig config, String messageId, String fileKey, String type);

    /**
     * 将图片字节上传到飞书，获取可以在卡片中使用的 image_key
     */
    String uploadImage(BotConfig config, byte[] imageBytes);

    /**
     * 解析发消息用户在飞书中的展示名（姓名/昵称）。
     * <p>优先使用消息 {@code mentions} 中与发送者 open_id 一致的条目（群聊 @ 时常带展示名）；
     * 否则调用通讯录「获取单个用户」接口（需应用具备通讯录只读等权限）。</p>
     *
     * @return 展示名；无法解析时返回 {@code null}
     */
    String resolveSenderDisplayName(BotConfig config, P2MessageReceiveV1 event);

    /**
     * 解析发件人档案（姓名、工号、邮箱等），供 Dify 多字段使用。
     * <p>优先拉取通讯录；失败时仍可能仅有群聊 {@code mentions} 中的展示名。</p>
     *
     * @return 档案；完全无法解析时返回 {@code null}
     */
    FeishuSenderProfile resolveSenderProfile(BotConfig config, P2MessageReceiveV1 event);
}
