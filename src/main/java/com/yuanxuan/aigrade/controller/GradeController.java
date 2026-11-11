package com.yuanxuan.aigrade.controller;

import com.yuanxuan.aigrade.config.DashScopeProperties;
import com.yuanxuan.aigrade.model.GradeInput;
import com.yuanxuan.aigrade.service.GradeService;
import com.yuanxuan.aigrade.support.ImageUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class GradeController {

    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;
    /** 一次批改最多允许的图片张数，避免请求体过大与模型上下文超限。 */
    private static final int MAX_IMAGES = 8;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final GradeService gradeService;
    private final DashScopeProperties props;

    public GradeController(GradeService gradeService, DashScopeProperties props) {
        this.gradeService = gradeService;
        this.props = props;
    }

    /** 前端用来展示当前模型与 API Key 配置状态。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", props.getModel());
        result.put("baseUrl", props.getBaseUrl());
        result.put("showThinking", props.isShowThinking());
        result.put("apiKeyConfigured", props.getApiKey() != null && !props.getApiKey().isBlank());
        return result;
    }

    /**
     * 批改接口：multipart 上传一张或多张作业图片，SSE 流式回推「转录 / 思考 / 批改结果」。
     *
     * @param showThinking 是否推送思考过程
     * @param images       按上传顺序排列的多张图片（题干与作答可分在不同图片里）
     * @param image        旧的单图参数，保留以兼容已有 curl / 调用方
     */
    @PostMapping(value = "/grade/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter gradeStream(
            @RequestParam(value = "showThinking", defaultValue = "true") boolean showThinking,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "image", required = false) MultipartFile image) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            failFast(emitter, "尚未配置 API Key：请设置环境变量 DASHSCOPE_API_KEY 后重启服务（详见 README）");
            return emitter;
        }

        // 兼容旧的单文件参数：只有 images 为空时才回退到 image
        List<MultipartFile> files = new ArrayList<>();
        if (images != null) {
            for (MultipartFile f : images) {
                if (f != null && !f.isEmpty()) {
                    files.add(f);
                }
            }
        }
        if (files.isEmpty() && image != null && !image.isEmpty()) {
            files.add(image);
        }

        if (files.isEmpty()) {
            failFast(emitter, "请至少上传一张图片");
            return emitter;
        }
        if (files.size() > MAX_IMAGES) {
            failFast(emitter, "一次最多上传 " + MAX_IMAGES + " 张图片，当前为 " + files.size() + " 张");
            return emitter;
        }
        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            if (!ImageUtils.isImage(f)) {
                failFast(emitter, "第 " + (i + 1) + " 个文件不是图片（jpg / jpeg / png / webp / gif / bmp）");
                return emitter;
            }
            if (f.getSize() > MAX_FILE_BYTES) {
                failFast(emitter, "第 " + (i + 1) + " 张图片超过 20MB，请压缩后再上传");
                return emitter;
            }
        }

        final List<String> dataUrls = new ArrayList<>();
        try {
            for (MultipartFile f : files) {
                dataUrls.add(ImageUtils.toDataUrl(f));
            }
        } catch (Exception e) {
            failFast(emitter, "读取图片失败：" + e.getMessage());
            return emitter;
        }

        GradeInput input = new GradeInput(dataUrls, showThinking);
        CompletableFuture.runAsync(() -> gradeService.grade(input, emitter));
        return emitter;
    }

    private void failFast(SseEmitter emitter, String message) {
        CompletableFuture.runAsync(() -> gradeService.emitError(emitter, message));
    }
}
