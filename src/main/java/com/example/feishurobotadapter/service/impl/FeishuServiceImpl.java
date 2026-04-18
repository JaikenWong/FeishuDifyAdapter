package com.example.feishurobotadapter.service.impl;
import com.example.feishurobotadapter.dto.CardRenderContent;
import com.example.feishurobotadapter.dto.FeishuSenderProfile;
import com.example.feishurobotadapter.entity.BotConfig;
import com.example.feishurobotadapter.service.FeishuService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import com.lark.oapi.service.contact.v3.enums.GetUserDepartmentIdTypeEnum;
import com.lark.oapi.service.contact.v3.enums.GetUserUserIdTypeEnum;
import com.lark.oapi.service.contact.v3.model.GetUserReq;
import com.lark.oapi.service.contact.v3.model.GetUserResp;
import com.lark.oapi.service.contact.v3.model.User;
import com.lark.oapi.service.cardkit.v1.model.Card;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardReqBody;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardResp;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateImageReq;
import com.lark.oapi.service.im.v1.model.CreateImageReqBody;
import com.lark.oapi.service.im.v1.model.CreateImageResp;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;
import com.lark.oapi.service.im.v1.model.UserId;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

/**
 * 飞书服务实现
 * 负责：
 * 1. 创建交互式卡片（支持流式模式、图片、文件链接）
 * 2. 发送卡片消息到飞书
 * 3. 流式更新卡片内容
 * 4. 下载用户发送的图片/文件；上传图片以获取 image_key
 */
@Service
@Log4j2
public class FeishuServiceImpl implements FeishuService {

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Integer> cardSequenceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Client> clientCache = new ConcurrentHashMap<>();

    public FeishuServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String createCard(BotConfig config, CardRenderContent content, boolean streaming) {
        try {
            CreateCardReq req = CreateCardReq.newBuilder()
                    .createCardReqBody(CreateCardReqBody.newBuilder()
                            .type("card_json")
                            .data(buildInteractiveCard(content, streaming))
                            .build())
                    .build();
            CreateCardResp resp = getClient(config).cardkit().v1().card().create(req);
            if (!resp.success()) {
                throw new IllegalStateException("创建飞书卡片失败: " + resp.getMsg());
            }
            String cardId = resp.getData().getCardId();
            cardSequenceCache.put(cardId, 0);
            return cardId;
        } catch (Exception ex) {
            log.error("[Feishu] 创建卡片失败", ex);
            throw new IllegalStateException("创建飞书卡片失败", ex);
        }
    }

    @Override
    public void updateCard(BotConfig config, String cardId, CardRenderContent content, boolean streaming) {
        try {
            Card card = Card.newBuilder()
                    .type("card_json")
                    .data(buildInteractiveCard(content, streaming))
                    .build();
            UpdateCardReq req = UpdateCardReq.newBuilder()
                    .cardId(cardId)
                    .updateCardReqBody(UpdateCardReqBody.newBuilder()
                            .card(card)
                            .uuid(UUID.randomUUID().toString())
                            .sequence(nextSequence(cardId))
                            .build())
                    .build();
            UpdateCardResp resp = getClient(config).cardkit().v1().card().update(req);
            if (!resp.success()) {
                throw new IllegalStateException("更新飞书卡片失败: " + resp.getMsg());
            }
        } catch (Exception ex) {
            log.error("[Feishu] 更新卡片失败: cardId={}", cardId, ex);
            throw new IllegalStateException("更新飞书卡片失败", ex);
        }
    }

    @Override
    public String sendCardMessage(BotConfig config, String cardId, String chatId, String originalMessageId, String chatType) {
        String content = "{\"type\":\"card\",\"data\":{\"card_id\":\"" + cardId + "\"}}";
        try {
            if ("p2p".equals(chatType)) {
                CreateMessageReq req = CreateMessageReq.newBuilder()
                        .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                        .createMessageReqBody(CreateMessageReqBody.newBuilder()
                                .receiveId(chatId)
                                .msgType("interactive")
                                .content(content)
                                .build())
                        .build();
                CreateMessageResp resp = getClient(config).im().message().create(req);
                if (resp.getCode() != 0) {
                    throw new IllegalStateException("发送飞书卡片消息失败: " + resp.getMsg());
                }
                return resp.getData().getMessageId();
            }

            ReplyMessageReq req = ReplyMessageReq.newBuilder()
                    .messageId(originalMessageId)
                    .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                            .content(content)
                            .msgType("interactive")
                            .build())
                    .build();
            ReplyMessageResp resp = getClient(config).im().message().reply(req);
            if (resp.getCode() != 0) {
                throw new IllegalStateException("回复飞书卡片消息失败: " + resp.getMsg());
            }
            return resp.getData().getMessageId();
        } catch (Exception ex) {
            log.error("[Feishu] 发送卡片消息失败", ex);
            throw new IllegalStateException("发送飞书卡片消息失败", ex);
        }
    }

    @Override
    public byte[] downloadMessageResource(BotConfig config, String messageId, String fileKey, String type) {
        try {
            GetMessageResourceReq req = GetMessageResourceReq.newBuilder()
                    .messageId(messageId)
                    .fileKey(fileKey)
                    .type(type)
                    .build();
            GetMessageResourceResp resp = getClient(config).im().messageResource().get(req);
            if (!resp.success()) {
                throw new IllegalStateException("下载飞书消息附件失败: " + resp.getMsg());
            }
            ByteArrayOutputStream data = resp.getData();
            return data == null ? new byte[0] : data.toByteArray();
        } catch (Exception ex) {
            log.error("[Feishu] 下载消息附件失败: messageId={}, fileKey={}, type={}", messageId, fileKey, type, ex);
            throw new IllegalStateException("下载飞书消息附件失败", ex);
        }
    }

    @Override
    public String uploadImage(BotConfig config, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("上传图片字节为空");
        }
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("feishu-card-img-", ".png");
            Files.write(tempFile, imageBytes);
            CreateImageReq req = CreateImageReq.newBuilder()
                    .createImageReqBody(CreateImageReqBody.newBuilder()
                            .imageType("message")
                            .image(tempFile.toFile())
                            .build())
                    .build();
            CreateImageResp resp = getClient(config).im().image().create(req);
            if (!resp.success()) {
                throw new IllegalStateException("上传飞书图片失败: " + resp.getMsg());
            }
            return resp.getData().getImageKey();
        } catch (Exception ex) {
            log.error("[Feishu] 上传图片失败", ex);
            throw new IllegalStateException("上传飞书图片失败", ex);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    /**
     * 构建飞书交互式卡片 JSON
     * 支持 markdown 文本 + 图片 image_key + 文件下载按钮，三类元素顺序展示
     */
    private String buildInteractiveCard(CardRenderContent content, boolean streaming) {
        try {
            String markdown = content == null ? null : content.getMarkdown();
            List<String> imageKeys = content == null ? List.of() : content.getImageKeys();
            List<CardRenderContent.FileLink> fileLinks = content == null ? List.of() : content.getFileLinks();

            List<Map<String, Object>> elements = new ArrayList<>();
            elements.add(Map.of(
                    "tag", "markdown",
                    "content", markdown == null || markdown.isBlank() ? "..." : markdown,
                    "element_id", "answer_content"
            ));

            for (String imageKey : imageKeys) {
                Map<String, Object> imgElement = new LinkedHashMap<>();
                imgElement.put("tag", "img");
                imgElement.put("img_key", imageKey);
                imgElement.put("alt", Map.of("tag", "plain_text", "content", ""));
                imgElement.put("mode", "fit_horizontal");
                imgElement.put("preview", true);
                elements.add(imgElement);
            }

            if (!fileLinks.isEmpty()) {
                StringBuilder fileMd = new StringBuilder("**附件**\n");
                for (CardRenderContent.FileLink link : fileLinks) {
                    fileMd.append("- [")
                            .append(escapeMarkdown(link.filename()))
                            .append("](")
                            .append(link.url())
                            .append(")\n");
                }
                elements.add(Map.of(
                        "tag", "markdown",
                        "content", fileMd.toString()
                ));
            }

            Map<String, Object> cardConfig = streaming
                    ? Map.of(
                            "streaming_mode", true,
                            "streaming_config", Map.of(
                                    "print_frequency_ms", Map.of("default", 70, "android", 70, "ios", 70, "pc", 70),
                                    "print_step", Map.of("default", 1, "android", 1, "ios", 1, "pc", 1),
                                    "print_strategy", "fast"
                            ),
                            "summary", Map.of("content", "[生成中...]")
                    )
                    : Map.of("streaming_mode", false);

            return objectMapper.writeValueAsString(Map.of(
                    "schema", "2.0",
                    "config", cardConfig,
                    "body", Map.of("elements", elements)
            ));
        } catch (Exception ex) {
            throw new IllegalStateException("构造飞书卡片失败", ex);
        }
    }

    private String escapeMarkdown(String value) {
        if (value == null) {
            return "附件";
        }
        return value.replace("[", "\\[").replace("]", "\\]");
    }

    @Override
    public String resolveSenderDisplayName(BotConfig config, P2MessageReceiveV1 event) {
        FeishuSenderProfile profile = resolveSenderProfile(config, event);
        return profile == null ? null : profile.displayName();
    }

    @Override
    public FeishuSenderProfile resolveSenderProfile(BotConfig config, P2MessageReceiveV1 event) {
        if (event == null || event.getEvent() == null) {
            log.debug("[FeishuSender] 无法解析档案: event 为空");
            return null;
        }
        EventSender sender = event.getEvent().getSender();
        if (sender == null || sender.getSenderId() == null) {
            log.debug("[FeishuSender] 无法解析档案: sender / senderId 为空");
            return null;
        }
        UserId senderId = sender.getSenderId();
        String senderOpenId = emptyToNull(senderId.getOpenId());
        String senderUserId = emptyToNull(senderId.getUserId());
        if (senderOpenId == null && senderUserId == null) {
            log.info("[FeishuSender] 无法解析档案: open_id 与 user_id 均为空，无法调通讯录");
            return null;
        }
        String mentionName = resolveMentionDisplayName(senderOpenId, event);

        Optional<User> contact = fetchContactUser(config, senderId);
        if (contact.isPresent()) {
            User u = contact.get();
            String display = pickDisplayName(u);
            boolean displayFromMention = false;
            if (display == null) {
                display = mentionName;
                displayFromMention = mentionName != null;
            }
            FeishuSenderProfile profile = new FeishuSenderProfile(
                    display,
                    emptyToNull(u.getName()),
                    emptyToNull(u.getEmployeeNo()),
                    emptyToNull(u.getEmail()),
                    emptyToNull(u.getEnName())
            );
            log.info(
                    "[FeishuSender] 通讯录命中: openId={}, displayName={}, fullName={}, employeeNo={}, email={}, enName={}, 展示名来自mentions={}",
                    maskTail(senderOpenId),
                    profile.displayName(),
                    profile.fullName(),
                    profile.employeeNo() != null ? "(有)" : "(无)",
                    profile.email() != null ? "(有)" : "(无)",
                    profile.enName(),
                    displayFromMention
            );
            return profile;
        }
        if (mentionName != null) {
            log.info(
                    "[FeishuSender] 通讯录未返回用户，仅使用消息 mentions 展示名（无工号/邮箱）: openId={}, mentionName={}",
                    maskTail(senderOpenId),
                    mentionName
            );
            return new FeishuSenderProfile(mentionName, null, null, null, null);
        }
        log.warn(
                "[FeishuSender] 未拿到任何展示信息: 通讯录失败且无匹配 mentions。请检查应用通讯录权限、用户是否在通讯录、或群场景下是否 @ 到人。openId={}, userId={}",
                maskTail(senderOpenId),
                maskTail(senderUserId)
        );
        return null;
    }

    /** 日志里只保留 id 尾部，避免整段 open_id 刷屏。 */
    private static String maskTail(String id) {
        if (id == null || id.isBlank()) {
            return "(空)";
        }
        if (id.length() <= 8) {
            return "***" + id;
        }
        return "…" + id.substring(id.length() - 8);
    }

    private static String resolveMentionDisplayName(String senderOpenId, P2MessageReceiveV1 event) {
        if (senderOpenId == null) {
            return null;
        }
        MentionEvent[] mentions = event.getEvent().getMessage() != null
                ? event.getEvent().getMessage().getMentions()
                : null;
        if (mentions == null) {
            return null;
        }
        for (MentionEvent m : mentions) {
            if (m == null || m.getId() == null) {
                continue;
            }
            String mo = emptyToNull(m.getId().getOpenId());
            if (senderOpenId.equals(mo)) {
                return emptyToNull(m.getName());
            }
        }
        return null;
    }

    private Optional<User> fetchContactUser(BotConfig config, UserId senderId) {
        String openId = emptyToNull(senderId.getOpenId());
        if (openId != null) {
            Optional<User> u = tryGetUser(config, openId, GetUserUserIdTypeEnum.OPEN_ID);
            if (u.isPresent()) {
                return u;
            }
        }
        String larkUserId = emptyToNull(senderId.getUserId());
        if (larkUserId != null) {
            return tryGetUser(config, larkUserId, GetUserUserIdTypeEnum.USER_ID);
        }
        return Optional.empty();
    }

    /**
     * 与常见业务侧用法对齐：带 open_department_id，便于通讯录返回完整字段（工号、邮箱等视租户与权限而定）。
     */
    private Optional<User> tryGetUser(BotConfig config, String userId, GetUserUserIdTypeEnum userIdType) {
        try {
            GetUserReq req = GetUserReq.newBuilder()
                    .userId(userId)
                    .userIdType(userIdType)
                    .departmentIdType(GetUserDepartmentIdTypeEnum.OPEN_DEPARTMENT_ID)
                    .build();
            GetUserResp resp = getClient(config).contact().user().get(req);
            if (!resp.success()) {
                log.debug("[Feishu] 获取通讯录用户失败: userIdType={}, code={}, msg={}", userIdType, resp.getCode(), resp.getMsg());
                return Optional.empty();
            }
            if (resp.getData() == null || resp.getData().getUser() == null) {
                return Optional.empty();
            }
            return Optional.of(resp.getData().getUser());
        } catch (Exception ex) {
            log.warn("[Feishu] 获取通讯录用户异常: userIdType={}, userId={}", userIdType, userId, ex);
            return Optional.empty();
        }
    }

    private static String emptyToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s;
    }

    private static String pickDisplayName(User user) {
        if (user == null) {
            return null;
        }
        String n = emptyToNull(user.getName());
        if (n != null) {
            return n;
        }
        n = emptyToNull(user.getEnName());
        if (n != null) {
            return n;
        }
        return emptyToNull(user.getNickname());
    }

    private int nextSequence(String cardId) {
        int current = cardSequenceCache.getOrDefault(cardId, 0) + 1;
        cardSequenceCache.put(cardId, current);
        return current;
    }

    private Client getClient(BotConfig config) {
        String key = config.getAppId() + ":" + config.getAppSecret();
        return clientCache.computeIfAbsent(key, k ->
            Client.newBuilder(config.getAppId(), config.getAppSecret()).build()
        );
    }
}
