package com.yuanxuan.aigrade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuanxuan.aigrade.model.GradeInput;
import com.yuanxuan.aigrade.support.Prompts;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 两阶段批改编排（严格对齐 Python 示例的 grade() 流程）：
 *
 * 阶段一  chat([image_url, transcribe_prompt], thinking=False)  -> 转录文本
 * 阶段二  chat(grade_prompt.replace("{transcript}", transcript), thinking=True) -> 批改结果
 */
@Service
public class GradeService {

    private final DashScopeClient client;
    private final ObjectMapper mapper;

    public GradeService(DashScopeClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public void grade(GradeInput input, SseEmitter emitter) {
        try {
            boolean modeB = "question".equalsIgnoreCase(input.mode());

            emit(emitter, "stage", Map.of("stage", "transcribe", "label", "阶段一 · 作业转录"));

            // ---------- 阶段一：只转录，不解题，避免模型先算出答案再「脑补」手写内容 ----------
            String transcribePrompt = modeB ? Prompts.TRANSCRIBE_ANSWER_ONLY : Prompts.TRANSCRIBE_FULL;

            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", Map.of("url", input.imageDataUrl()));
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", transcribePrompt);
            List<Map<String, Object>> content = List.of(imagePart, textPart);

            String stage1Text = client.chatStream(content, false, (reasoning, text) -> {
                if (text != null && !text.isEmpty()) {
                    sendDelta(emitter, "transcript", text);
                }
            });

            String transcript = buildTranscript(input, stage1Text, modeB);
            emit(emitter, "transcript", Map.of("text", transcript));

            // ---------- 阶段二：基于转录文本独立解题并批改，开启思考 ----------
            emit(emitter, "stage", Map.of("stage", "grade", "label", "阶段二 · 解题与批改"));
            String gradePrompt = Prompts.GRADE.replace("{transcript}", transcript);

            client.chatStream(gradePrompt, true, (reasoning, text) -> {
                if (reasoning != null && !reasoning.isEmpty() && input.showThinking()) {
                    sendDelta(emitter, "reasoning", reasoning);
                }
                if (text != null && !text.isEmpty()) {
                    sendDelta(emitter, "content", text);
                }
            });

            emit(emitter, "done", Map.of("ok", true));
            emitter.complete();
        } catch (Exception e) {
            emitError(emitter, friendlyMessage(e));
        }
    }

    /**
     * 模式 B 下题干由用户以文本给出，这里把「用户题干 + 转录出的作答」
     * 拼成与模式 A 完全一致的转录格式，从而复用同一份批改提示词。
     */
    private String buildTranscript(GradeInput input, String stage1Text, boolean modeB) {
        String answer = stage1Text == null ? "" : stage1Text.trim();
        if (!modeB) {
            return answer;
        }
        String question = input.questionText() == null ? "" : input.questionText().trim();
        return "第1题\n题干：" + question + "\n学生答案：" + (answer.isEmpty() ? "未作答" : answer);
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
