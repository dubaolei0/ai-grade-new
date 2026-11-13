package com.yuanxuan.aigrade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuanxuan.aigrade.model.GradeInput;
import com.yuanxuan.aigrade.service.DashScopeClient.ChatResult;
import com.yuanxuan.aigrade.support.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段批改编排（严格对齐 Python 示例的 grade() 流程）：
 *
 * 阶段一  chat([image_url..., transcribe_prompt], thinking=False)  -> 转录文本
 * 阶段二  chat(grade_prompt.replace("{transcript}", transcript), thinking=True) -> 批改结果
 *
 * 阶段一支持多张图片：多个 image_url 按上传顺序连续放在 content 前部；图片数 > 1 时追加
 * 合并转录说明，让模型把「题干图」与「作答图」当作同一份作业跨图配对，而不是各转录一遍。
 */
@Service
public class GradeService {

    private static final Logger log = LoggerFactory.getLogger(GradeService.class);

    private final DashScopeClient client;
    private final ObjectMapper mapper;

    public GradeService(DashScopeClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public void grade(GradeInput input, SseEmitter emitter) {
        try {
            int imageCount = input.imageDataUrls().size();
            emit(emitter, "stage", Map.of("stage", "transcribe",
                    "label", "阶段一 · 作业转录（" + imageCount + " 张图片）"));

            // ---------- 阶段一：只转录，不解题，避免模型先算出答案再「脑补」手写内容 ----------
            String transcribePrompt = Prompts.TRANSCRIBE;
            if (imageCount > 1) {
                transcribePrompt += Prompts.MULTI_IMAGE;
            }

            // OpenAI 兼容多图写法：图片按上传顺序连续排布，最后一个 text 给出转录要求
            List<Map<String, Object>> content = new ArrayList<>();
            for (String dataUrl : input.imageDataUrls()) {
                Map<String, Object> imagePart = new LinkedHashMap<>();
                imagePart.put("type", "image_url");
                imagePart.put("image_url", Map.of("url", dataUrl));
                content.add(imagePart);
            }
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", transcribePrompt);
            content.add(textPart);

            ChatResult stage1 = client.chatStream(content, false, (reasoning, text) -> {
                if (text != null && !text.isEmpty()) {
                    sendDelta(emitter, "transcript", text);
                }
            });

            String transcript = stage1.content() == null ? "" : stage1.content().trim();
            emit(emitter, "transcript", Map.of("text", transcript));
            logStageUsage("阶段一 · 作业转录", stage1.usage());

            // ---------- 阶段二：基于转录文本独立解题并批改，开启思考 ----------
            emit(emitter, "stage", Map.of("stage", "grade", "label", "阶段二 · 解题与批改"));
            String gradePrompt = Prompts.GRADE.replace("{transcript}", transcript);

            ChatResult stage2 = client.chatStream(gradePrompt, input.enableThinking(), (reasoning, text) -> {
                if (reasoning != null && !reasoning.isEmpty() && input.showThinking()) {
                    sendDelta(emitter, "reasoning", reasoning);
                }
                if (text != null && !text.isEmpty()) {
                    sendDelta(emitter, "content", text);
                }
            });
            logStageUsage("阶段二 · 解题与批改", stage2.usage());
            logTotalUsage(stage1.usage(), stage2.usage());

            emit(emitter, "done", Map.of("ok", true));
            emitter.complete();
        } catch (Exception e) {
            emitError(emitter, friendlyMessage(e));
        }
    }

    private void sendDelta(SseEmitter emitter, String kind, String text) {
        try {
            emit(emitter, "delta", Map.of("kind", kind, "text", text));
        } catch (IOException e) {
            throw new IllegalStateException("前端连接已断开，停止批改", e);
        }
    }

    public void emitError(SseEmitter emitter, String message) {
        try {
            emit(emitter, "error", Map.of("message", message));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void emit(SseEmitter emitter, String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(payload);
        // 用 UTF-8 字节写出，避免 StringHttpMessageConverter 默认 ISO-8859-1 导致中文乱码
        byte[] bytes = mapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        emitter.send(SseEmitter.event().name("message").data(bytes, MediaType.APPLICATION_JSON));
    }

    /** 打印单阶段 token 用量：prompt / completion / total，缺字段时容错。 */
    private void logStageUsage(String stage, JsonNode usage) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            log.info("{} 未返回 token 用量", stage);
            return;
        }
        log.info("{} token 用量：prompt_tokens={}, completion_tokens={}, total_tokens={}",
                stage,
                usage.path("prompt_tokens").asText("?"),
                usage.path("completion_tokens").asText("?"),
                usage.path("total_tokens").asText("?"));
    }

    /** 汇总两阶段用量并打印总计（字段缺失时跳过对应汇总）。 */
    private void logTotalUsage(JsonNode stage1, JsonNode stage2) {
        long prompt = sumToken(stage1, "prompt_tokens") + sumToken(stage2, "prompt_tokens");
        long completion = sumToken(stage1, "completion_tokens") + sumToken(stage2, "completion_tokens");
        long total = sumToken(stage1, "total_tokens") + sumToken(stage2, "total_tokens");
        log.info("本次批改 token 用量汇总：prompt_tokens={}, completion_tokens={}, total_tokens={}",
                prompt, completion, total);
    }

    private long sumToken(JsonNode usage, String field) {
        if (usage == null || usage.isMissingNode() || usage.isNull()) {
            return 0L;
        }
        JsonNode node = usage.get(field);
        return node == null || !node.canConvertToLong() ? 0L : node.asLong();
    }

    private String friendlyMessage(Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        if (message.contains("401")) {
            return "API Key 无效（HTTP 401），请检查环境变量 DASHSCOPE_API_KEY";
        }
        if (message.contains("403")) {
            return "无权访问该模型（HTTP 403），请确认百炼账号已开通对应模型与地域";
        }
        if (message.contains("404") || message.contains("model")) {
            return "模型不可用，请确认 application.yml 中的 dashscope.model 名称：" + message;
        }
        if (message.contains("429")) {
            return "调用过于频繁或额度不足（HTTP 429），请稍后重试";
        }
        if (message.contains("timed out") || message.contains("ConnectException")) {
            return "连接模型服务超时，请检查网络或代理设置：" + message;
        }
        return message;
    }
}
