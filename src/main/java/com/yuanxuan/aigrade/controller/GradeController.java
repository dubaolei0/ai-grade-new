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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class GradeController {

    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

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
     * 批改接口：multipart 上传图片，SSE 流式回推「转录 / 思考 / 批改结果」。
     *
     * @param mode         image | question
     * @param questionText 模式 question 下的题干文本
     * @param showThinking 是否推送思考过程
     * @param image        作业图片 / 作答图片
     */
    @PostMapping(value = "/grade/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter gradeStream(
            @RequestParam(value = "mode", defaultValue = "image") String mode,
            @RequestParam(value = "questionText", required = false, defaultValue = "") String questionText,
            @RequestParam(value = "showThinking", defaultValue = "true") boolean showThinking,
            @RequestParam(value = "image", required = false) MultipartFile image) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            failFast(emitter, "尚未配置 API Key：请设置环境变量 DASHSCOPE_API_KEY 后重启服务（详见 README）");
            return emitter;
        }
        if (!ImageUtils.isImage(image)) {
            failFast(emitter, "请上传图片文件（jpg / jpeg / png / webp / gif / bmp）");
            return emitter;
        }
        if ("question".equalsIgnoreCase(mode) && (questionText == null || questionText.isBlank())) {
            failFast(emitter, "「题干文本 + 作答图片」模式下，请填写题干文本");
            return emitter;
        }

        final String dataUrl;
        try {
            dataUrl = ImageUtils.toDataUrl(image);
        } catch (Exception e) {
            failFast(emitter, "读取图片失败：" + e.getMessage());
            return emitter;
        }

        GradeInput input = new GradeInput(mode, questionText, dataUrl, showThinking);
        CompletableFuture.runAsync(() -> gradeService.grade(input, emitter));
        return emitter;
    }

    private void failFast(SseEmitter emitter, String message) {
        CompletableFuture.runAsync(() -> gradeService.emitError(emitter, message));
    }
}
