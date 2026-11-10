package com.yuanxuan.aigrade.model;

/**
 * 一次批改请求的输入。
 *
 * @param mode          image = 上传整张作业图片；question = 题干文本 + 作答图片
 * @param questionText  模式 question 下用户输入的题干文本
 * @param imageDataUrl  图片的 data:image/...;base64,... 形式
 * @param showThinking  是否把模型的思考过程推送给前端
 */
public record GradeInput(String mode, String questionText, String imageDataUrl, boolean showThinking) {
}
