package com.yuanxuan.aigrade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 豆包爱学（火山引擎）作业批改配置。
 *
 * 对应接口：
 *   提交 https://open.feedcoopapi.com/agent_api/agent/home_work/mark/submit
 *   查询 https://open.feedcoopapi.com/agent_api/agent/home_work/mark/query
 *
 * 鉴权：Authorization: Bearer <api-key>，并带 ServiceName 头（默认 ask_echo）。
 * bot_id 为火山控制台创建的智能体 id，提交与查询接口都必填。
 */
@ConfigurationProperties(prefix = "doubao")
public class DoubaoProperties {

    private String apiKey = "";
    private String botId = "";
    private String baseUrl = "https://open.feedcoopapi.com";
    private String serviceName = "ask_echo";
    private int connectTimeoutSeconds = 15;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }
}
