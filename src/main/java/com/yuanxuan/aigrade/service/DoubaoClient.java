package com.yuanxuan.aigrade.service;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuanxuan.aigrade.config.DoubaoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 调用豆包爱学（火山引擎）作业批改的两个接口：
 *
 *   提交 POST /agent_api/agent/home_work/mark/submit  -> 返回 task_id
 *   查询 POST /agent_api/agent/home_work/mark/query   -> 返回 status + mark_results
 *
 * 鉴权头：Authorization: Bearer <api-key>、Content-Type: application/json、ServiceName: ask_echo。
 * 两个接口均为同步 JSON，非流式。使用 JDK 21 内置 HttpClient，无第三方 HTTP 依赖。
 *
 * 返回的是响应体里 Result 节点（含 task_id / status / mark_results / usage 等），
 * 异常时抛 IllegalStateException，由调用方转成给前端的错误信息。
 */
@Component
public class DoubaoClient {

    private static final Logger log = LoggerFactory.getLogger(DoubaoClient.class);

    private final DoubaoProperties props;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public DoubaoClient(DoubaoProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                .build();
    }

    /** 提交批改任务，imageBase64DataUrl 形如 data:image/jpeg;base64,... */
    public JsonNode submit(String imageBase64DataUrl) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("bot_id", props.getBotId());
        body.put("image_base64", imageBase64DataUrl);
        body.put("enable_image_dewarp", true);
        body.put("enable_orientation_classification", true);
        body.put("enable_cross_judge", false);
        return post("/agent_api/agent/home_work/mark/submit", body);
    }

    /** 查询批改任务结果。 */
    public JsonNode query(String taskId) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("bot_id", props.getBotId());
        body.put("task_id", taskId);
        return post("/agent_api/agent/home_work/mark/query", body);
    }

    private JsonNode post(String path, ObjectNode body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(props.getBaseUrl()) + path))
                .header("Authorization", "Bearer " + props.getApiKey())
                .header("Content-Type", "application/json")
                .header("ServiceName", props.getServiceName())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        log.info("豆包 {} 请求 body={}", path, JSON.toJSONString(body));
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String respBody = response.body() == null ? "" : response.body();
        log.info("豆包 {} 原始响应：HTTP {}，body={}", path, response.statusCode(), respBody);

        if (response.statusCode() != 200) {
            throw new IllegalStateException("豆包服务返回 HTTP " + response.statusCode() + "：" + abbreviate(respBody));
        }

        JsonNode root = mapper.readTree(respBody);
        JsonNode result = root.path("Result");
        if (result.isMissingNode() || result.isNull()) {
            // 错误响应：ResponseMetadata.Error.Message 或 BaseResp
            String message = root.path("ResponseMetadata").path("Error").path("Message").asText("");
            if (message.isBlank()) {
                message = root.path("BaseResp").path("Message").asText("");
            }
            throw new IllegalStateException("豆包服务返回异常：" + (message.isBlank() ? abbreviate(respBody) : message));
        }
        return result;
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
