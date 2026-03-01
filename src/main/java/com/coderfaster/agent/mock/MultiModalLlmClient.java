package com.coderfaster.agent.mock;

import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.http.AgentResponse;
import com.coderfaster.agent.http.AgentServerClient;
import com.coderfaster.agent.http.ChatRequest;
import com.coderfaster.agent.http.ToolResultRequest;
import com.coderfaster.agent.model.TokenUsage;
import com.coderfaster.agent.model.ToolCall;
import com.coderfaster.agent.model.ToolCallResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.session.SessionConfig;
import com.coderfaster.agent.session.SessionMessage;
import com.coderfaster.agent.session.compaction.CompactionResult;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.coderfaster.agent.session.compaction.ToolResultCache;
import com.coderfaster.agent.session.store.FileSessionStore;
import com.coderfaster.agent.session.store.SessionStore;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationUsage;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模态 LLM 客户端
 * 直接调用百炼多模态大模型（如 qwen-vl-max, qwen-vl-plus 等），支持图像、视频等输入
 * 支持会话持久化和三层压缩机制
 */
public class MultiModalLlmClient implements AgentServerClient {

    private static final Logger log = LoggerFactory.getLogger(MultiModalLlmClient.class);

    private final AgentConfig config;
    private final ObjectMapper objectMapper;
    private final MultiModalConversation multiModalConversation;
    private final Path projectPath;
    private final SessionConfig sessionConfig;

    private final SessionStore sessionStore;
    private final ToolResultCache toolResultCache;

    private final Map<String, List<MultiModalMessage>> sessionHistory = new ConcurrentHashMap<>();
    private final Map<String, List<ToolSchema>> sessionTools = new ConcurrentHashMap<>();
    private final Map<String, String> lastMessageUuid = new ConcurrentHashMap<>();

    /**
     * 使用默认配置创建客户端（向后兼容）
     */
    public MultiModalLlmClient(AgentConfig config) {
        this(config, Path.of(System.getProperty("user.dir")), SessionConfig.defaults());
    }

    /**
     * 使用指定项目路径创建客户端
     */
    public MultiModalLlmClient(AgentConfig config, Path projectPath) {
        this(config, projectPath, SessionConfig.defaults());
    }

    /**
     * 使用完整配置创建客户端
     */
    public MultiModalLlmClient(AgentConfig config, Path projectPath, SessionConfig sessionConfig) {
        this.config = config;
        this.projectPath = projectPath.toAbsolutePath().normalize();
        this.sessionConfig = sessionConfig;
        this.objectMapper = createObjectMapper();
        this.multiModalConversation = new MultiModalConversation();

        this.sessionStore = new FileSessionStore(this.projectPath, sessionConfig);
        this.toolResultCache = new ToolResultCache(this.projectPath, sessionConfig);

        log.info("MultiModalLlmClient initialized with session persistence, project: {}", this.projectPath);
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Override
    public AgentResponse chat(ChatRequest request) {
        String sessionId = request.getSessionId();

        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = sessionStore.createSession(projectPath);
            log.info("Created new multimodal session: {}", sessionId);
        } else if (!sessionStore.sessionExists(sessionId)) {
            sessionId = sessionStore.createSession(projectPath);
            log.info("Multimodal session not found, created new: {}", sessionId);
        }

        List<MultiModalMessage> messages = sessionHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            sessionTools.put(sessionId, request.getTools());
            sessionStore.setSessionTools(sessionId, request.getTools());
        }

        String userMessage = request.getMessage();
        if (userMessage != null && !userMessage.isEmpty()) {
            messages.add(buildUserMessage(userMessage));

            String parentUuid = lastMessageUuid.get(sessionId);
            SessionMessage sessionMsg = SessionMessage.user(sessionId, userMessage, parentUuid);
            sessionStore.appendMessage(sessionId, sessionMsg);
            lastMessageUuid.put(sessionId, sessionMsg.getUuid());
        }

        return callLlm(sessionId, messages);
    }

    @Override
    public AgentResponse submitToolResult(ToolResultRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ToolResultRequest cannot be null");
        }
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session ID is required for tool result submission");
        }

        List<MultiModalMessage> messages = sessionHistory.get(sessionId);
        if (messages == null) {
            throw new IllegalStateException("Session not found: " + sessionId);
        }

        for (ToolCallResult result : request.getResults()) {
            String content = result.isSuccess() ? result.getContent() : "Error: " + result.getError();
            boolean isError = !result.isSuccess();

            ToolResultCache.MicrocompactResult microResult = toolResultCache.process(
                    sessionId, result.getCallId(), result.getToolName(), content);

            if (microResult.isOffloaded()) {
                messages.add(buildToolResultMessage(microResult.getPreview(), result.getCallId()));

                String parentUuid = lastMessageUuid.get(sessionId);
                SessionMessage sessionMsg = SessionMessage.toolResultOffloaded(
                        sessionId, result.getCallId(), result.getToolName(),
                        microResult.getStoragePath(), microResult.getPreview(), isError, parentUuid);
                sessionStore.appendMessage(sessionId, sessionMsg);
                lastMessageUuid.put(sessionId, sessionMsg.getUuid());
            } else {
                messages.add(buildToolResultMessage(content, result.getCallId()));

                String parentUuid = lastMessageUuid.get(sessionId);
                SessionMessage sessionMsg = SessionMessage.toolResult(
                        sessionId, result.getCallId(), result.getToolName(), content, isError, parentUuid);
                sessionStore.appendMessage(sessionId, sessionMsg);
                lastMessageUuid.put(sessionId, sessionMsg.getUuid());
            }
        }

        return callLlm(sessionId, messages);
    }

    private AgentResponse callLlm(String sessionId, List<MultiModalMessage> messages) {
        long startTime = System.currentTimeMillis();
        try {
            MultiModalConversationParam.MultiModalConversationParamBuilder<?, ?> paramBuilder =
                    MultiModalConversationParam.builder()
                            .model(config.getModelName())
                            .apiKey(getApiKey())
                            .messages(messages);

            List<ToolSchema> tools = sessionTools.get(sessionId);
            if (tools != null && !tools.isEmpty()) {
                List<ToolBase> toolBases = convertTools(tools);
                paramBuilder.tools(toolBases);
            }

            MultiModalConversationParam param = paramBuilder.build();

            log.debug("Calling MultiModal LLM with {} messages", messages.size());
            MultiModalConversationResult result = multiModalConversation.call(param);

            long durationMs = System.currentTimeMillis() - startTime;
            return parseResponse(sessionId, result, messages, durationMs);

        } catch (Exception e) {
            log.error("Failed to call MultiModal LLM", e);
            throw new RuntimeException("MultiModal LLM call failed: " + e.getMessage(), e);
        }
    }

    private List<ToolBase> convertTools(List<ToolSchema> tools) {
        List<ToolBase> result = new ArrayList<>();
        for (ToolSchema tool : tools) {
            JsonNode params = tool.getParameters();
            FunctionDefinition.FunctionDefinitionBuilder<?, ?> builder = FunctionDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription());

            if (params != null) {
                String jsonStr = params.toString();
                JsonObject jsonObject = JsonUtils.parseString(jsonStr).getAsJsonObject();
                builder.parameters(jsonObject);
            }

            result.add(ToolFunction.builder()
                    .function(builder.build())
                    .build());
        }
        return result;
    }

    private AgentResponse parseResponse(String sessionId, MultiModalConversationResult result,
                                        List<MultiModalMessage> messages, long durationMs) {
        if (result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return AgentResponse.error(sessionId, "EMPTY_RESPONSE", "MultiModal LLM returned empty response");
        }

        TokenUsage tokenUsage = extractTokenUsage(result);

        var choice = result.getOutput().getChoices().get(0);
        var message = choice.getMessage();

        messages.add(message);

        List<ToolCallBase> toolCalls = message.getToolCalls();
        if (toolCalls != null && !toolCalls.isEmpty()) {
            List<ToolCall> agentToolCalls = new ArrayList<>();
            for (ToolCallBase toolCall : toolCalls) {
                if (toolCall instanceof ToolCallFunction) {
                    ToolCallFunction funcCall = (ToolCallFunction) toolCall;
                    var function = funcCall.getFunction();

                    JsonNode arguments;
                    try {
                        arguments = objectMapper.readTree(function.getArguments());
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse tool arguments: {}", function.getArguments());
                        arguments = objectMapper.createObjectNode();
                    }

                    agentToolCalls.add(ToolCall.builder()
                            .callId(funcCall.getId())
                            .name(function.getName())
                            .arguments(arguments)
                            .build());
                    
                    // 持久化 TOOL_CALL 消息
                    String parentUuid = lastMessageUuid.get(sessionId);
                    SessionMessage toolCallMsg = SessionMessage.toolCall(
                            sessionId, funcCall.getId(), function.getName(), arguments, parentUuid);
                    sessionStore.appendMessage(sessionId, toolCallMsg);
                    lastMessageUuid.put(sessionId, toolCallMsg.getUuid());
                }
            }

            if (!agentToolCalls.isEmpty()) {
                log.info("MultiModal LLM requested {} tool calls", agentToolCalls.size());
                return AgentResponse.toolCalls(sessionId, agentToolCalls, tokenUsage, durationMs);
            }
        }

        String content = extractTextContent(message);
        String finishReason = choice.getFinishReason();
        boolean done = "stop".equals(finishReason) || "length".equals(finishReason);

        // 持久化 ASSISTANT 消息
        String parentUuid = lastMessageUuid.get(sessionId);
        SessionMessage.TokenUsageInfo usageInfo = SessionMessage.TokenUsageInfo.builder()
                .inputTokens(tokenUsage.getInputTokens())
                .outputTokens(tokenUsage.getOutputTokens())
                .totalTokens(tokenUsage.getTotalTokens())
                .build();
        SessionMessage assistantMsg = SessionMessage.assistant(
                sessionId, content != null ? content : "", config.getModelName(), usageInfo, parentUuid);
        sessionStore.appendMessage(sessionId, assistantMsg);
        lastMessageUuid.put(sessionId, assistantMsg.getUuid());

        log.info("MultiModal LLM returned message, done={}", done);
        return AgentResponse.message(sessionId, content != null ? content : "", done, tokenUsage, durationMs);
    }

    /**
     * 从多模态消息中提取文本内容
     */
    private String extractTextContent(MultiModalMessage message) {
        if (message == null || message.getContent() == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> contentItem : message.getContent()) {
            if (contentItem.containsKey("text")) {
                Object textObj = contentItem.get("text");
                if (textObj != null) {
                    sb.append(textObj.toString());
                }
            }
        }
        return sb.toString();
    }

    /**
     * 从 MultiModalConversationResult 中提取 Token 使用信息
     */
    private TokenUsage extractTokenUsage(MultiModalConversationResult result) {
        if (result == null || result.getUsage() == null) {
            return TokenUsage.empty();
        }

        MultiModalConversationUsage usage = result.getUsage();
        int inputTokens = usage.getInputTokens() != null ? usage.getInputTokens() : 0;
        int outputTokens = usage.getOutputTokens() != null ? usage.getOutputTokens() : 0;
        int totalTokens = usage.getTotalTokens() != null ? usage.getTotalTokens() : (inputTokens + outputTokens);

        return TokenUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .build();
    }

    /**
     * 构建用户消息（纯文本）
     */
    private MultiModalMessage buildUserMessage(String text) {
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("text", text);
        content.add(textContent);

        return MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build();
    }

    /**
     * 构建带图像的用户消息
     *
     * @param text 文本内容
     * @param imageUrl 图像 URL（支持 http/https URL 或 base64 编码）
     */
    public MultiModalMessage buildUserMessageWithImage(String text, String imageUrl) {
        List<Map<String, Object>> content = new ArrayList<>();

        Map<String, Object> imageContent = new HashMap<>();
        imageContent.put("image", imageUrl);
        content.add(imageContent);

        if (text != null && !text.isEmpty()) {
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("text", text);
            content.add(textContent);
        }

        return MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build();
    }

    /**
     * 构建带多张图像的用户消息
     *
     * @param text 文本内容
     * @param imageUrls 图像 URL 列表
     */
    public MultiModalMessage buildUserMessageWithImages(String text, List<String> imageUrls) {
        List<Map<String, Object>> content = new ArrayList<>();

        for (String imageUrl : imageUrls) {
            Map<String, Object> imageContent = new HashMap<>();
            imageContent.put("image", imageUrl);
            content.add(imageContent);
        }

        if (text != null && !text.isEmpty()) {
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("text", text);
            content.add(textContent);
        }

        return MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build();
    }

    /**
     * 构建带视频的用户消息
     *
     * @param text 文本内容
     * @param videoUrl 视频 URL
     */
    public MultiModalMessage buildUserMessageWithVideo(String text, String videoUrl) {
        List<Map<String, Object>> content = new ArrayList<>();

        Map<String, Object> videoContent = new HashMap<>();
        videoContent.put("video", videoUrl);
        content.add(videoContent);

        if (text != null && !text.isEmpty()) {
            Map<String, Object> textContent = new HashMap<>();
            textContent.put("text", text);
            content.add(textContent);
        }

        return MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(content)
                .build();
    }

    /**
     * 构建 Tool 结果消息
     */
    private MultiModalMessage buildToolResultMessage(String content, String toolCallId) {
        List<Map<String, Object>> contentList = new ArrayList<>();
        Map<String, Object> textContent = new HashMap<>();
        textContent.put("text", content);
        contentList.add(textContent);

        return MultiModalMessage.builder()
                .role(Role.TOOL.getValue())
                .content(contentList)
                .toolCallId(toolCallId)
                .build();
    }

    /**
     * 添加多模态消息到会话
     * 允许外部直接添加构建好的多模态消息
     *
     * @param sessionId 会话 ID
     * @param message 多模态消息
     */
    public void addMessage(String sessionId, MultiModalMessage message) {
        List<MultiModalMessage> messages = sessionHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        messages.add(message);
    }

    /**
     * 使用多模态消息进行对话
     *
     * @param sessionId 会话 ID
     * @param message 多模态消息（可包含图像、视频等）
     * @param tools 工具列表
     * @return Agent 响应
     */
    public AgentResponse chatWithMultiModalMessage(String sessionId, MultiModalMessage message,
                                                   List<ToolSchema> tools) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        List<MultiModalMessage> messages = sessionHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());

        if (tools != null && !tools.isEmpty()) {
            sessionTools.put(sessionId, tools);
        }

        messages.add(message);

        return callLlm(sessionId, messages);
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    @Override
    public void close() {
        sessionHistory.clear();
        sessionTools.clear();
        lastMessageUuid.clear();
        toolResultCache.close();
        sessionStore.close();
        log.info("MultiModalLlmClient closed");
    }

    public void clearSession(String sessionId) {
        sessionHistory.remove(sessionId);
        sessionTools.remove(sessionId);
        lastMessageUuid.remove(sessionId);
        toolResultCache.clearSession(sessionId);
    }

    public void clearAllSessions() {
        sessionHistory.clear();
        sessionTools.clear();
        lastMessageUuid.clear();
    }

    /**
     * 获取 API 密钥
     */
    private String getApiKey() {
        return StringUtils.defaultIfBlank(config.getApiKey(), "");
    }

    // ========== 会话管理扩展方法 ==========

    /**
     * 获取 SessionStore
     */
    public SessionStore getSessionStore() {
        return sessionStore;
    }

    /**
     * 获取上下文统计信息
     */
    public ContextStats getContextStats(String sessionId) {
        return sessionStore.getContextStats(sessionId);
    }

    /**
     * 手动执行压缩
     */
    public CompactionResult compact(String sessionId, String focusHint) {
        CompactionResult result = sessionStore.autocompact(sessionId, focusHint);
        if (result.isSuccess() && result.getCompactedMessageCount() > 0) {
            sessionHistory.remove(sessionId);
        }
        return result;
    }

    /**
     * 获取项目路径
     */
    public Path getProjectPath() {
        return projectPath;
    }
}
