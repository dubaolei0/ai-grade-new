package com.yuanxuan.aigrade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云百炼 OpenAI 兼容模式配置。
 * 对应 Python 示例中的 DASHSCOPE_API_KEY / base_url / MODEL。
 */
@ConfigurationProperties(prefix = "dashscope")
public class DashScopeProperties {

    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen3.8-max";
    private boolean showThinking = true;
    private int connectTimeoutSeconds = 15;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isShowThinking() {
        return showThinking;
    }

    public void setShowThinking(boolean showThinking) {
        this.showThinking = showThinking;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }
}
