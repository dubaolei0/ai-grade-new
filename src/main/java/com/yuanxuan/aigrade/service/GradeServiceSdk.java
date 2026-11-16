package com.yuanxuan.aigrade.service;

import com.alibaba.dashscope.aigc.completion.ChatCompletionUsage;
import com.alibaba.dashscope.common.ImageURL;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.MessageContentBase;
import com.alibaba.dashscope.common.MessageContentImageURL;
import com.alibaba.dashscope.common.MessageContentText;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuanxuan.aigrade.model.GradeContent;
import com.yuanxuan.aigrade.model.GradeInput;
import com.yuanxuan.aigrade.model.QuestionResult;
import com.yuanxuan.aigrade.service.DashScopeSdkClient.ChatResult;
import com.yuanxuan.aigrade.support.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 基于 dashscope 官方 SDK 的两阶段批改编排（与 {@link GradeService} 并行、互不影响）。
 *
 * 与 {@link GradeService} 的差异：
 *   - 两阶段均走 {@link DashScopeSdkClient#chat} 非流式调用，不再逐字推送；
 *   - {@link #grade(GradeInput)} 同步返回 {@link GradeResult}（转录 / 思考 / 批改正文 / token 用量），
 *     由控制器直接以 JSON 返回给前端，不走 SSE；
 *   - enableThinking 默认关闭：思考关闭时模型不产出 reasoning_content，{@code reasoning} 为 null。
 */
@Service
public class GradeServiceSdk {

    private static final Logger log = LoggerFactory.getLogger(GradeServiceSdk.class);

    private final DashScopeSdkClient client;
    private final ObjectMapper mapper;

    public GradeServiceSdk(DashScopeSdkClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /** 一次批改的最终结果。reasoning 仅在 enableThinking=true 且 showThinking=true 时有值。 */
    public static class GradeResult {

        private final String transcript;
        private final String reasoning;
        private final GradeContent content;
        private final UsageSummary usage;

        public GradeResult(String transcript, String reasoning, GradeContent content, UsageSummary usage) {
            this.transcript = transcript;
            this.reasoning = reasoning;
            this.content = content;
            this.usage = usage;
        }

        public String getTranscript() {
            return transcript;
        }

        public String getReasoning() {
            return reasoning;
        }

        public GradeContent getContent() {
            return content;
        }

        public UsageSummary getUsage() {
            return usage;
        }
    }

    /** 两阶段汇总的 token 用量。 */
    public static class UsageSummary {

        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;

        public UsageSummary(long promptTokens, long completionTokens, long totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public long getPromptTokens() {
            return promptTokens;
        }

        public long getCompletionTokens() {
            return completionTokens;
        }

        public long getTotalTokens() {
            return totalTokens;
        }

        @Override
        public String toString() {
            return "UsageSummary{promptTokens=" + promptTokens
                    + ", completionTokens=" + completionTokens
                    + ", totalTokens=" + totalTokens + '}';
        }
    }

    public GradeResult grade(GradeInput input) throws Exception {
        int imageCount = input.getImageDataUrls().size();

        // ---------- 阶段一：只转录，不解题，思考关闭 ----------
        String transcribePrompt = Prompts.TRANSCRIBE;
        if (imageCount > 1) {
            transcribePrompt += Prompts.MULTI_IMAGE;
        }

        // 多模态消息：图片按上传顺序连续排布，最后一个 text 给出转录要求（与 OpenAI 兼容写法一致）
        List<MessageContentBase> contents = new ArrayList<>();
        for (String dataUrl : input.getImageDataUrls()) {
            contents.add(MessageContentImageURL.builder()
                    .type("image_url")
                    .imageURL(ImageURL.builder().url(dataUrl).build())
                    .build());
        }
        contents.add(MessageContentText.builder()
                .type("text")
                .text(transcribePrompt)
                .build());
        Message stage1Message = Message.builder().role("user").contents(contents).build();

        ChatResult stage1 = client.chat(List.of(stage1Message), false);
        String transcript = stage1.getContent() == null ? "" : stage1.getContent().trim();
        logUsage("阶段一 · 作业转录", stage1.getUsage());

        // ---------- 阶段二：基于转录文本独立解题并批改，要求模型输出 JSON 数组，思考由调用方决定（默认关闭） ----------
        String gradePrompt = Prompts.GRADE_JSON.replace("{transcript}", transcript);
        Message stage2Message = Message.builder().role("user").content(gradePrompt).build();

        ChatResult stage2 = client.chat(List.of(stage2Message), input.isEnableThinking());
        logUsage("阶段二 · 解题与批改", stage2.getUsage());
        UsageSummary usage = aggregateUsage(stage1.getUsage(), stage2.getUsage());
        log.info("本次批改 token 用量汇总：{}", usage);

        // 思考关闭或前端不展示时，不回传 reasoning
        String reasoning = (input.isShowThinking() && stage2.getReasoningContent() != null && !stage2.getReasoningContent().isEmpty())
                ? stage2.getReasoningContent() : null;
        GradeContent content = parseContent(stage2.getContent());
        return new GradeResult(transcript, reasoning, content, usage);
    }

    /**
     * 把模型返回的批改结果解析成 {@link GradeContent}。
     *
     * 提示词要求模型只输出 JSON 数组，但实际可能夹杂 Markdown 代码块或前后缀文字，
     * 这里取第一个 {@code [} 到最后一个 {@code ]} 之间的子串再解析，提高容错。
     */
    private GradeContent parseContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return new GradeContent(List.of());
        }
        String json = extractJsonArray(raw);
        try {
            List<QuestionResult> questions = mapper.readValue(json, new TypeReference<List<QuestionResult>>() {});
            return new GradeContent(questions);
        } catch (Exception e) {
            throw new IllegalStateException("解析批改结果失败：" + e.getMessage()
                    + "；模型原文：" + abbreviate(raw), e);
        }
    }

    /** 截取模型输出中第一个 JSON 数组（[ ... ]）片段；找不到则抛出明确异常。 */
    private static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("模型未返回可解析的 JSON 数组，原文：" + abbreviate(raw));
        }
        return raw.substring(start, end + 1);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 600 ? text.substring(0, 600) + "..." : text;
    }

    private UsageSummary aggregateUsage(ChatCompletionUsage stage1, ChatCompletionUsage stage2) {
        long prompt = sumToken(stage1, ChatCompletionUsage::getPromptTokens)
                + sumToken(stage2, ChatCompletionUsage::getPromptTokens);
        long completion = sumToken(stage1, ChatCompletionUsage::getCompletionTokens)
                + sumToken(stage2, ChatCompletionUsage::getCompletionTokens);
        long total = sumToken(stage1, ChatCompletionUsage::getTotalTokens)
                + sumToken(stage2, ChatCompletionUsage::getTotalTokens);
        return new UsageSummary(prompt, completion, total);
    }

    private void logUsage(String stage, ChatCompletionUsage usage) {
        if (usage == null) {
            log.info("{} 未返回 token 用量", stage);
            return;
        }
        log.info("{} token 用量：prompt_tokens={}, completion_tokens={}, total_tokens={}",
                stage, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private long sumToken(ChatCompletionUsage usage, Function<ChatCompletionUsage, Integer> getter) {
        if (usage == null) {
            return 0L;
        }
        Integer v = getter.apply(usage);
        return v == null ? 0L : v;
    }

    /** 把模型/SDK 异常转成对用户友好的中文提示，供控制器组装错误响应。 */
    public static String friendlyMessage(Exception e) {
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
        if (message.contains("timed out") || message.contains("ConnectException") || message.contains("Timeout")) {
            return "连接模型服务超时，请检查网络或代理设置：" + message;
        }
        return message;
    }
}
