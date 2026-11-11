package com.yuanxuan.aigrade.model;

import java.util.List;

/**
 * 一次批改请求的输入。
 *
 * @param mode          image = 上传作业图片（可多张）；question = 题干文本 + 作答图片（可多张）
 * @param questionText  模式 question 下用户输入的题干文本
 * @param imageDataUrls 按上传顺序排列的 data:image/...;base64,... 图片列表，顺序即模型看到的图片顺序
 * @param showThinking  是否把模型的思考过程推送给前端
 */
public record GradeInput(String mode, String questionText, List<String> imageDataUrls, boolean showThinking) {

    /** 兼容单图调用（旧的 curl 示例、单图 CLI 或自定义调用方）。 */
    public GradeInput(String mode, String questionText, String imageDataUrl, boolean showThinking) {
        this(mode, questionText, List.of(imageDataUrl), showThinking);
    }
}
