package com.yuanxuan.aigrade.service;

import com.alibaba.dashscope.aigc.completion.ChatCompletion;
import com.alibaba.dashscope.aigc.completion.ChatCompletionParam;
import com.alibaba.dashscope.aigc.completion.ChatCompletionUsage;
import com.alibaba.dashscope.aigc.completion.ChatCompletions;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.yuanxuan.aigrade.config.DashScopeProperties;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 dashscope-sdk-java 官方 SDK 的 OpenAI 兼容对话客户端（非流式）。
 *
 * 与 {@link DashScopeClient}（手写 HttpClient + SSE 解析）并行存在、互不影响：
 *   - DashScopeClient   ：/api/grade/stream 走流式，逐字推送 content / reasoning_content
 *   - DashScopeSdkClient：/api/grade/sdk    走 SDK 的 {@link ChatCompletions#call}，一次性返回完整结果
 *
 * 使用 SDK 的 {@code ChatCompletions(baseUrl, connectionOptions)} 构造，传入配置里的 compatible-mode
 * base-url；SDK 默认 isService=false，最终 URL = base-url + "/chat/completions"，与手写 client 一致。
 *
 * enable_thinking 是 OpenAI 兼容模式下的请求体顶层字段，SDK 的 {@link ChatCompletionParam} 没有对应
 * 字段，通过继承自 HalfDuplexParamBaseBuilder 的 {@code .parameter(k, v)} 注入（Flatten 父类会扁平化
 * 到顶层）。思考关闭时模型不产出 reasoning_content；思考开启时从非流式结果的 message.reasoningContent
 * 一次性取出，规避了 SDK 流式 Delta 不含 reasoning_content 的限制。
 */
@Component
@Slf4j
public class DashScopeSdkClient {

    private final DashScopeProperties props;
    private final ChatCompletions completions;

    public DashScopeSdkClient(DashScopeProperties props) {
        this.props = props;
        // readTimeout 给到与 SSE 超时一致的量级，避免非流式调用因模型思考耗时被过早掐断
        this.completions = new ChatCompletions(props.getBaseUrl(), ConnectionOptions.builder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                .readTimeout(Duration.ofMinutes(10))
                .build());
    }

    /** 一次非流式对话的结果：完整正文 + 思考过程（仅 enableThinking=true 时有值） + token 用量。 */
    public static class ChatResult {

        private final String content;
        private final String reasoningContent;
        private final ChatCompletionUsage usage;

        public ChatResult(String content, String reasoningContent, ChatCompletionUsage usage) {
            this.content = content;
            this.reasoningContent = reasoningContent;
            this.usage = usage;
        }

        public String getContent() {
            return content;
        }

        public String getReasoningContent() {
            return reasoningContent;
        }

        public ChatCompletionUsage getUsage() {
            return usage;
        }
    }

    /**
     * 发起一次非流式对话。
     *
     * @param messages       消息列表；阶段一用多模态 Message（contents 含 image_url + text），
     *                       阶段二用纯文本 Message（content 为 grade prompt）
     * @param enableThinking 是否开启思考（阶段一 false，阶段二由调用方决定，默认关闭）
     * @return 完整正文、思考过程与 token 用量
     */
    public ChatResult chat(List<Message> messages, boolean enableThinking) throws Exception {
        ChatCompletionParam param = ChatCompletionParam.builder()
                .model(props.getModel())
                .apiKey(props.getApiKey())
                .messages(messages)
                .parameter("enable_thinking", enableThinking)
                .build();

        ChatCompletion result = completions.call(param);
        log.info("SDK 非流式调用：model={}, enable_thinking={}, usage={}",
                props.getModel(), enableThinking, result.getUsage());

        ChatCompletion.Choice choice = (result.getChoices() == null || result.getChoices().isEmpty())
                ? null : result.getChoices().get(0);
        Message msg = choice == null ? null : choice.getMessage();

        String content = msg == null || msg.getContent() == null ? "" : msg.getContent();
        String reasoning = msg == null ? null : msg.getReasoningContent();
        return new ChatResult(content, reasoning, result.getUsage());
    }
}
