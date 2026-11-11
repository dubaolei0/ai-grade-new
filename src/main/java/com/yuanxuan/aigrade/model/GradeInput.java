package com.yuanxuan.aigrade.model;

import java.util.List;

/**
 * 一次批改请求的输入。
 *
 * @param imageDataUrls 按上传顺序排列的 data:image/...;base64,... 图片列表，顺序即模型看到的图片顺序
 * @param showThinking  是否把模型的思考过程推送给前端
 */
public record GradeInput(List<String> imageDataUrls, boolean showThinking) {

    /** 兼容单图调用（旧的 curl 示例或自定义调用方）。 */
    public GradeInput(String imageDataUrl, boolean showThinking) {
        this(List.of(imageDataUrl), showThinking);
    }
}
