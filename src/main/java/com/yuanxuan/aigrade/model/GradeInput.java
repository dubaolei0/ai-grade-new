package com.yuanxuan.aigrade.model;

import java.util.List;

/**
 * 一次批改请求的输入。
 *
 * @param imageDataUrls  按上传顺序排列的 data:image/...;base64,... 图片列表，顺序即模型看到的图片顺序
 * @param showThinking   是否把模型的思考过程推送给前端（仅在 enableThinking 开启时有意义）
 * @param enableThinking 阶段二是否开启模型思考（enable_thinking），关闭可节省 token、加快出结果
 */
public record GradeInput(List<String> imageDataUrls, boolean showThinking, boolean enableThinking) {

    /** 兼容单图调用（旧的 curl 示例或自定义调用方）。 */
    public GradeInput(String imageDataUrl, boolean showThinking) {
        this(List.of(imageDataUrl), showThinking, true);
    }
}
