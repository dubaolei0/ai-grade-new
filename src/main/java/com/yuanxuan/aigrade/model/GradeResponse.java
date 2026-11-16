package com.yuanxuan.aigrade.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code /api/grade/sdk} 的同步响应对象。
 *
 * 成功时 {@code ok=true}，携带 {@code transcript / reasoning / content / usage}；
 * 失败时 {@code ok=false}，仅 {@code message} 有值，其余字段为 null（序列化时省略）。
 *
 * 字段说明：
 *   - transcript：阶段一的作业转录全文
 *   - reasoning ：阶段二的思考过程，仅在 enableThinking=true 且 showThinking=true 时有值
 *   - content   ：阶段二的批改结果，结构化对象 {@link GradeContent}（逐题含 题号/学生答案/标准答案/判定结果）
 *   - usage     ：两阶段汇总的 token 用量
 *
 * 使用 {@code @JsonInclude(NON_NULL)} 省略 null 字段，使成功/失败响应都保持简洁。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GradeResponse {

    private final boolean ok;
    private final String message;
    private final String transcript;
    private final String reasoning;
    private final GradeContent content;
    private final Usage usage;

    public GradeResponse(boolean ok, String message, String transcript, String reasoning, GradeContent content,
                         Usage usage) {
        this.ok = ok;
        this.message = message;
        this.transcript = transcript;
        this.reasoning = reasoning;
        this.content = content;
        this.usage = usage;
    }

    /** 两阶段汇总的 token 用量。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {

        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;

        public Usage(long promptTokens, long completionTokens, long totalTokens) {
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
    }

    /** 构造成功响应。reasoning 为 null 时序列化省略。 */
    public static GradeResponse ok(String transcript, String reasoning, GradeContent content, Usage usage) {
        return new GradeResponse(true, null, transcript, reasoning, content, usage);
    }

    /** 构造失败响应。 */
    public static GradeResponse error(String message) {
        return new GradeResponse(false, message, null, null, null, null);
    }

    public boolean isOk() {
        return ok;
    }

    public String getMessage() {
        return message;
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

    public Usage getUsage() {
        return usage;
    }
}
