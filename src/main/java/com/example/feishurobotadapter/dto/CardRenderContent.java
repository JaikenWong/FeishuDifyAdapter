package com.example.feishurobotadapter.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡片渲染内容
 * 用来统一描述一次卡片更新需要显示的文本、图片和文件附件，
 * 由 FeishuService 据此构建最终的飞书卡片 JSON。
 */
public class CardRenderContent {

    private String markdown;
    private final List<String> imageKeys = new ArrayList<>();
    private final List<FileLink> fileLinks = new ArrayList<>();

    public CardRenderContent() {
    }

    public CardRenderContent(String markdown) {
        this.markdown = markdown;
    }

    public static CardRenderContent ofText(String markdown) {
        return new CardRenderContent(markdown);
    }

    public String getMarkdown() {
        return markdown;
    }

    public CardRenderContent setMarkdown(String markdown) {
        this.markdown = markdown;
        return this;
    }

    public List<String> getImageKeys() {
        return imageKeys;
    }

    public CardRenderContent addImageKey(String imageKey) {
        if (imageKey != null && !imageKey.isBlank()) {
            this.imageKeys.add(imageKey);
        }
        return this;
    }

    public List<FileLink> getFileLinks() {
        return fileLinks;
    }

    public CardRenderContent addFileLink(String filename, String url) {
        if (url != null && !url.isBlank()) {
            this.fileLinks.add(new FileLink(filename == null ? "附件" : filename, url));
        }
        return this;
    }

    public record FileLink(String filename, String url) {
    }
}
