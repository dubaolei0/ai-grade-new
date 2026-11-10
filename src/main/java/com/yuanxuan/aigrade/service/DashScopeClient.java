package com.yuanxuan.aigrade.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuanxuan.aigrade.config.DashScopeProperties;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * 调用阿里云百炼 OpenAI 兼容模式的 /chat/completions 流式接口。
 *
 * 与 Python 示例的对应关系：
 *   base_url  = https://dashscope.aliyuncs.com/compatible-mode/v1
 *   api_key   = DASHSCOPE_API_KEY（Bearer 认证）
 *   stream    = true
 *   extra_body.enable_thinking -> 请求体顶层字段 enable_thinking
 *   delta.reasoning_content    -> 思考过程
 *   delta.content              -> 最终回答
 *
 * 使用 JDK 21 内置 HttpClient，无需引入第三方 HTTP 依赖。
 */
@Component
public class DashScopeClient {

    private final DashScopeProperties props;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public DashScopeClient(DashScopeProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                .build();
    }

    /** 流式增量回调，两个参数都可能为 null。 */
    public interface DeltaHandler {
        void onDelta(String reasoningChunk, String contentChunk);
    }

    /**
     * 发起一次流式对话。
     *
     * @param content        消息内容：纯文本字符串，或多模态 List&lt;Map&gt; 数组
     * @param enableThinking 是否开启思考（阶段一 false，阶段二 true）
     * @param handler        增量回调
     * @return 拼接后的完整正文
     */
    public String chatStream(Object content, boolean enableThinking, DeltaHandler handler) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("stream", true);
        root.put("enable_thinking", enableThinking);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.set("content", mapper.valueToTree(content));

        String requestBody = mapper.writeValueAsString(root);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(props.getBaseUrl()) + "/chat/completions"))
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        // BodyHandlers.ofLines() 返回惰性行流，遍历时持续读取上游 SSE，天然适合长连接
        HttpResponse<Stream<String>> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            String errorBody;
            try (Stream<String> lines = response.body()) {
                errorBody = String.join("\n", lines.limit(30).toList());
            } catch (Exception ignore) {
                errorBody = "";
            }
            throw new IllegalStateException("模型服务返回 HTTP " + response.statusCode() + "：" + abbreviate(errorBody));
        }

        StringBuilder answer = new StringBuilder();
        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line == null || !line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (Exception parseError) {
                    return; // 忽略心跳、注释等无法解析的行
                }
                JsonNode choices = chunk.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    return; // 对齐 Python 的 if not chunk.choices: continue
                }
                JsonNode delta = choices.get(0).path("delta");
                String reasoning = delta.hasNonNull("reasoning_content")
                        ? delta.get("reasoning_content").asText() : null;
                String text = delta.hasNonNull("content")
                        ? delta.get("content").asText() : null;

                if (text != null && !text.isEmpty()) {
                    answer.append(text);
                }
                if ((reasoning != null && !reasoning.isEmpty()) || (text != null && !text.isEmpty())) {
                    handler.onDelta(reasoning, text);
                }
            });
        } catch (UncheckedIOException e) {
            throw new IllegalStateException("读取模型流式响应时连接中断：" + e.getMessage(), e);
        }
        return answer.toString();
    }

    private static String trimTrailingSlash(String url) {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 600 ? text.substring(0, 600) + "..." : text;
    }
}
