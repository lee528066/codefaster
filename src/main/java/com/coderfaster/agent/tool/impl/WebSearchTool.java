package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.ToolNames;
import com.coderfaster.agent.utils.ModelUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * WebSearch 工具 - 使用百炼 (DashScope) 执行网络搜索。
 * <p>
 * 基于 coderfaster-agent web-search 实现
 * <p>
 * 允许搜索网络并使用结果来支持响应。
 * 提供训练数据截止日期之后的当前事件和最新数据的最新信息。
 * 返回带有简洁答案和来源链接的格式化搜索结果。
 * <p>
 * 支持的提供商：
 * - DashScope（百炼，默认且唯一）
 * <p>
 * 初始化方式：
 * 1. 通过构造函数传入 API Key（推荐）
 * 2. 通过 setConfig() 方法手动配置
 */
public class WebSearchTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WebSearchTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * HTTP 请求的默认超时时间（秒）
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * 默认最大结果数
     */
    private static final int DEFAULT_MAX_RESULTS = 5;

    /**
     * 用于发起请求的 HTTP 客户端
     */
    private final OkHttpClient httpClient;

    /**
     * 网络搜索配置
     */
    private WebSearchConfig config;

    private static final String DESCRIPTION =
            "Allows searching the web and using results to inform responses. " +
                    "Provides up-to-date information for current events and recent data " +
                    "beyond the training data cutoff. Returns search results formatted with " +
                    "concise answers and source links.\n\n" +
                    "Use this tool when accessing information that may be outdated or beyond " +
                    "the knowledge cutoff.\n\n" +
                    "Supported provider: dashscope";

    /**
     * 创建 WebSearchTool 实例（无配置）。
     * 需要通过 setConfig() 或 setApiKey() 方法配置 API Key。
     */
    public WebSearchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 创建 WebSearchTool 实例并配置 DashScope API Key。
     *
     * @param dashscopeApiKey 百炼 API Key
     */
    public WebSearchTool(String dashscopeApiKey) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();

        // 使用传入的 API Key 配置 DashScope 提供商
        if (dashscopeApiKey != null && !dashscopeApiKey.trim().isEmpty()) {
            initializeWithApiKey(dashscopeApiKey);
        } else {
            logger.warn("DashScope API Key is empty. Web search will be unavailable until configured.");
        }
    }

    /**
     * 使用 API Key 初始化 DashScope 配置。
     */
    private void initializeWithApiKey(String apiKey) {
        WebSearchProviderConfig dashscopeConfig = new WebSearchProviderConfig();
        dashscopeConfig.type = "dashscope";
        dashscopeConfig.apiKey = apiKey;
        dashscopeConfig.modelName = "qwen3.5-plus";
        dashscopeConfig.maxResults = DEFAULT_MAX_RESULTS;

        WebSearchConfig autoConfig = new WebSearchConfig();
        autoConfig.providers.add(dashscopeConfig);
        autoConfig.defaultProvider = "dashscope";

        this.config = autoConfig;
        logger.info("DashScope provider configured successfully");
    }

    /**
     * 设置 DashScope API Key。
     * 如果之前已配置，将覆盖原有配置。
     *
     * @param apiKey 百炼 API Key
     */
    public void setApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.warn("Cannot set empty API Key");
            return;
        }
        initializeWithApiKey(apiKey);
    }

    /**
     * 设置网络搜索配置。
     * 如果手动调用此方法，将覆盖自动配置。
     */
    public void setConfig(WebSearchConfig config) {
        this.config = config;
        logger.info("WebSearchTool configured with {} providers",
                config != null && config.providers != null ? config.providers.size() : 0);
    }

    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
                .addProperty("query", "string",
                        "The search query to find information on the web.")
                .setRequired(Arrays.asList("query"))
                .build();

        return new ToolSchema(
                ToolNames.WEB_SEARCH,
                DESCRIPTION,
                ToolKind.FETCH,
                parameters,
                "1.0.0"
        );
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String query = params.path("query").asText("");

        logger.debug("WebSearch: query={}", query);

        // 验证查询
        if (query.trim().isEmpty()) {
            return ToolResult.failure("The 'query' parameter cannot be empty.");
        }

        // 检查是否配置了网络搜索
        if (config == null || config.providers.isEmpty()) {
            return ToolResult.failure(
                    "Web search is disabled. Please configure API Key via constructor or setApiKey() method."
            );
        }

        try {
            // 选择提供商
            WebSearchProvider provider = selectProvider();
            if (provider == null) {
                return ToolResult.failure("No web search providers are available.");
            }

            // 执行搜索
            WebSearchResult result = provider.search(query);

            // 格式化结果
            String content = formatSearchResults(result, provider.getName());

            if (content.trim().isEmpty()) {
                return ToolResult.success(
                        "No search results found for query: \"" + query + "\" (via " + provider.getName() + ")"
                );
            }

            // 构建用于显示的来源
            List<Map<String, String>> sources = new ArrayList<>();
            for (WebSearchResultItem item : result.results) {
                Map<String, String> source = new HashMap<>();
                source.put("title", item.title);
                source.put("url", item.url);
                sources.add(source);
            }

            return ToolResult.builder()
                    .success(true)
                    .content("Web search results for \"" + query + "\" (via " + provider.getName() + "):\n\n" + content)
                    .displayData(sources)
                    .build();

        } catch (Exception e) {
            logger.error("Error during web search: {}", e.getMessage(), e);
            return ToolResult.failure("Error during web search: " + e.getMessage());
        }
    }

    /**
     * 选择 DashScope 提供商。
     */
    private WebSearchProvider selectProvider() {
        // 尝试默认提供商
        if (config.defaultProvider != null) {
            for (WebSearchProviderConfig providerConfig : config.providers) {
                if (providerConfig.type.equalsIgnoreCase(config.defaultProvider)) {
                    WebSearchProvider provider = createProvider(providerConfig);
                    if (provider != null && provider.isAvailable()) {
                        return provider;
                    }
                }
            }
        }

        // 回退到第一个可用的提供商
        for (WebSearchProviderConfig providerConfig : config.providers) {
            WebSearchProvider provider = createProvider(providerConfig);
            if (provider != null && provider.isAvailable()) {
                return provider;
            }
        }

        return null;
    }

    /**
     * 从配置创建提供商实例。
     */
    private WebSearchProvider createProvider(WebSearchProviderConfig providerConfig) {
        if ("dashscope".equalsIgnoreCase(providerConfig.type)) {
            return new DashScopeProvider(providerConfig, httpClient);
        } else {
            logger.warn("Unknown provider type: {}", providerConfig.type);
            return null;
        }
    }

    /**
     * 获取可用提供商名称列表。
     */
    private List<String> getAvailableProviderNames() {
        List<String> names = new ArrayList<>();
        if (config != null) {
            for (WebSearchProviderConfig providerConfig : config.providers) {
                WebSearchProvider provider = createProvider(providerConfig);
                if (provider != null && provider.isAvailable()) {
                    names.add(providerConfig.type);
                }
            }
        }
        return names;
    }

    /**
     * 将搜索结果格式化为内容字符串。
     */
    private String formatSearchResults(WebSearchResult result, String providerName) {
        // 如果提供商返回了答案，使用它
        if (result.answer != null && !result.answer.trim().isEmpty()) {
            StringBuilder content = new StringBuilder(result.answer.trim());

            // 附加来源
            if (!result.results.isEmpty()) {
                content.append("\n\n**Sources:**\n");
                for (int i = 0; i < Math.min(result.results.size(), 5); i++) {
                    WebSearchResultItem item = result.results.get(i);
                    content.append(String.format("- [%s](%s)%n", item.title, item.url));
                }
            }

            return content.toString();
        }

        // 回退：从结果构建信息摘要
        if (result.results.isEmpty()) {
            return "";
        }

        StringBuilder content = new StringBuilder();
        int count = Math.min(result.results.size(), 5);

        for (int i = 0; i < count; i++) {
            WebSearchResultItem item = result.results.get(i);
            content.append(String.format("%d. **%s**%n", i + 1, item.title));

            if (item.content != null && !item.content.trim().isEmpty()) {
                content.append("   ").append(item.content.trim()).append("\n");
            }

            content.append("   Source: ").append(item.url).append("\n");

            if (item.score != null) {
                content.append(String.format("   Relevance: %.0f%%%n", item.score * 100));
            }

            if (item.publishedDate != null && !item.publishedDate.isEmpty()) {
                content.append("   Published: ").append(item.publishedDate).append("\n");
            }

            content.append("\n");
        }

        content.append("*Note: For detailed content from any source above, use the web_fetch tool with the URL.*");

        return content.toString();
    }

    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("query") || params.path("query").asText("").trim().isEmpty()) {
            return "The 'query' parameter cannot be empty.";
        }

        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return true; // 网络搜索需要确认
    }

    // ==================== 配置类 ====================

    /**
     * 网络搜索配置。
     */
    public static class WebSearchConfig {
        public List<WebSearchProviderConfig> providers = new ArrayList<>();
        public String defaultProvider;

        public WebSearchConfig() {
        }

        public WebSearchConfig(List<WebSearchProviderConfig> providers, String defaultProvider) {
            this.providers = providers;
            this.defaultProvider = defaultProvider;
        }
    }

    /**
     * 提供商配置。
     */
    public static class WebSearchProviderConfig {
        public String type;
        public String apiKey;
        public String modelName; // DashScope API 的模型名称
        public int maxResults = DEFAULT_MAX_RESULTS;

        private static final String DEFAULT_MODEL_NAME = "qwen-plus";

        public WebSearchProviderConfig() {
        }

        public WebSearchProviderConfig(String type, String apiKey) {
            this.type = type;
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName != null && !modelName.isEmpty() ? modelName : DEFAULT_MODEL_NAME;
        }
    }

    // ==================== 结果类 ====================

    /**
     * 搜索结果项。
     */
    public static class WebSearchResultItem {
        public String title;
        public String url;
        public String content;
        public Double score;
        public String publishedDate;

        public WebSearchResultItem() {
        }

        public WebSearchResultItem(String title, String url, String content) {
            this.title = title;
            this.url = url;
            this.content = content;
        }
    }

    /**
     * 搜索结果。
     */
    public static class WebSearchResult {
        public String query;
        public String answer;
        public List<WebSearchResultItem> results = new ArrayList<>();

        public WebSearchResult() {
        }

        public WebSearchResult(String query) {
            this.query = query;
        }
    }

    // ==================== 提供商接口 ====================

    /**
     * 网络搜索提供商接口。
     */
    public interface WebSearchProvider {
        String getName();

        boolean isAvailable();

        WebSearchResult search(String query) throws IOException;
    }

    // ==================== 提供商实现 ====================

    /**
     * DashScope 搜索提供商（阿里云百炼）。
     * 使用百炼大模型的联网搜索能力。
     */
    private static class DashScopeProvider implements WebSearchProvider {
        private static final String DASHSCOPE_API_URL = "https://dashscope.aliyuncs"
                + ".com/api/v1/services/aigc/text-generation/generation";
        private static final String DASHSCOPE_MULTI_MODAL_API_URL = "https://dashscope.aliyuncs"
                + ".com/api/v1/services/aigc/multimodal-generation/generation";


        private final WebSearchProviderConfig config;
        private final OkHttpClient httpClient;

        DashScopeProvider(WebSearchProviderConfig config, OkHttpClient httpClient) {
            this.config = config;
            this.httpClient = httpClient;
        }

        @Override
        public String getName() {
            return "DashScope";
        }

        @Override
        public boolean isAvailable() {
            return config.apiKey != null && !config.apiKey.isEmpty();
        }

        @Override
        public WebSearchResult search(String query) throws IOException {
            // 构建百炼 API 请求
            String requestBodyJson = buildRequestBody(query);

            RequestBody body = RequestBody.create(
                    requestBodyJson,
                    MediaType.parse("application/json")
            );

            String url = ModelUtils.isMultiModalMode(config.getModelName()) ? DASHSCOPE_MULTI_MODAL_API_URL :
                    DASHSCOPE_API_URL;

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + config.apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            logger.info("Sending request to DashScope API: requestBodyJson:{}", requestBodyJson);

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = response.body() != null ? response.body().string() : "Unknown error";
                    throw new IOException("DashScope API error: " + response.code() + " " + response.message() +
                            " - " + errorMsg);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode json = objectMapper.readTree(responseBody);

                WebSearchResult result = new WebSearchResult(query);

                // 解析百炼响应
                if (json.has("output")) {
                    JsonNode output = json.path("output");
                    if (output.has("choices") && output.path("choices").isArray()) {
                        JsonNode firstChoice = output.path("choices").get(0);
                        if (firstChoice != null && firstChoice.has("message")) {
                            JsonNode message = firstChoice.path("message");
                            JsonNode contentNode = message.path("content");

                            // content 可能是字符串或数组
                            if (contentNode.isTextual()) {
                                result.answer = contentNode.asText("");
                            } else if (contentNode.isArray() && !contentNode.isEmpty()) {
                                // 解析 content 数组格式：[{ "text": "..." }]
                                JsonNode firstContent = contentNode.get(0);
                                if (firstContent != null && firstContent.has("text")) {
                                    result.answer = firstContent.path("text").asText("");
                                }
                            }
                        }
                    }
                }

                return result;
            } catch (Exception e) {
                logger.error("Error during DashScope search: {}", e.getMessage(), e);
                throw new IOException("Error during DashScope search: " + e.getMessage());
            }
        }

        /**
         * 构建百炼 API 请求体 JSON。
         */
        private String buildRequestBody(String query) {
            try {
                Map<String, Object> jsonMap = new HashMap<>();
                jsonMap.put("model", config.getModelName());

                // 构建 input.messages
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> systemMessage = new HashMap<>();
                systemMessage.put("role", "system");
                systemMessage.put("content", "You are a helpful assistant.");
                messages.add(systemMessage);

                Map<String, String> userMessage = new HashMap<>();
                userMessage.put("role", "user");
                userMessage.put("content", query);
                messages.add(userMessage);

                jsonMap.put("input", Map.of("messages", messages));

                // 构建 parameters，使用 enable_search 而不是 plugins
                Map<String, Object> parameters = new HashMap<>();
                parameters.put("enable_search", true);
                parameters.put("result_format", "message");
                jsonMap.put("parameters", parameters);

                return objectMapper.writeValueAsString(jsonMap);
            } catch (Exception e) {
                logger.error("Error serializing DashScope request to JSON", e);
                throw new RuntimeException("Failed to serialize request", e);
            }
        }
    }
}
