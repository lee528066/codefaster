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
import com.coderfaster.agent.session.compaction.CompactionService;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.coderfaster.agent.session.compaction.ToolResultCache;
import com.coderfaster.agent.session.store.FileSessionStore;
import com.coderfaster.agent.session.store.SessionStore;
import com.alibaba.dashscope.aigc.conversation.ConversationParam;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.aigc.generation.GenerationUsage;
import com.alibaba.dashscope.common.Message;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct LLM 客户端
 * 直接调用百炼大模型，无需远程 Agent Server
 * 支持会话持久化和三层压缩机制
 */
public class DirectLlmClient implements AgentServerClient {

    private static final Logger log = LoggerFactory.getLogger(DirectLlmClient.class);

    private final AgentConfig config;
    private final ObjectMapper objectMapper;
    private final Generation generation;
    private final Path projectPath;
    private final SessionConfig sessionConfig;

    private final SessionStore sessionStore;
    private final ToolResultCache toolResultCache;
    private final CompactionService compactionService;

    private final Map<String, List<Message>> sessionMessageCache = new ConcurrentHashMap<>();
    private final Map<String, List<ToolSchema>> sessionTools = new ConcurrentHashMap<>();
    private final Map<String, String> lastMessageUuid = new ConcurrentHashMap<>();

    /**
     * 使用默认配置创建客户端（向后兼容）
     */
    public DirectLlmClient(AgentConfig config) {
        this(config, Path.of(System.getProperty("user.dir")), SessionConfig.defaults());
    }

    /**
     * 使用指定项目路径创建客户端
     */
    public DirectLlmClient(AgentConfig config, Path projectPath) {
        this(config, projectPath, SessionConfig.defaults());
    }

    /**
     * 使用完整配置创建客户端
     */
    public DirectLlmClient(AgentConfig config, Path projectPath, SessionConfig sessionConfig) {
        this.config = config;
        this.projectPath = projectPath.toAbsolutePath().normalize();
        this.sessionConfig = sessionConfig;
        this.objectMapper = createObjectMapper();
        this.generation = new Generation();

        this.sessionStore = new FileSessionStore(this.projectPath, sessionConfig);
        this.toolResultCache = new ToolResultCache(this.projectPath, sessionConfig);
        this.compactionService = new CompactionService(this.projectPath, sessionConfig);

        log.info("DirectLlmClient initialized with session persistence, project: {}", this.projectPath);
    }

    /**
     * 使用外部提供的 SessionStore 创建客户端
     */
    public DirectLlmClient(AgentConfig config, Path projectPath, SessionStore sessionStore, SessionConfig sessionConfig) {
        this.config = config;
        this.projectPath = projectPath.toAbsolutePath().normalize();
        this.sessionConfig = sessionConfig;
        this.objectMapper = createObjectMapper();
        this.generation = new Generation();

        this.sessionStore = sessionStore;
        this.toolResultCache = new ToolResultCache(this.projectPath, sessionConfig);
        this.compactionService = new CompactionService(this.projectPath, sessionConfig);

        log.info("DirectLlmClient initialized with external SessionStore, project: {}", this.projectPath);
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
            log.info("Created new session: {}", sessionId);
        } else if (!sessionStore.sessionExists(sessionId)) {
            sessionId = sessionStore.createSession(projectPath);
            log.info("Session not found, created new: {}", sessionId);
        }

        List<Message> messages = loadOrCreateMessages(sessionId);

        if (request.getTools() != null && !request.getTools().isEmpty()) {
            sessionTools.put(sessionId, request.getTools());
            sessionStore.setSessionTools(sessionId, request.getTools());
        }

        String userMessage = request.getMessage();
        if (userMessage != null && !userMessage.isEmpty()) {
            Message llmMsg = buildMessage(Role.USER.getValue(), userMessage, null);
            messages.add(llmMsg);

            String parentUuid = lastMessageUuid.get(sessionId);
            SessionMessage sessionMsg = SessionMessage.user(sessionId, userMessage, parentUuid);
            sessionStore.appendMessage(sessionId, sessionMsg);
            lastMessageUuid.put(sessionId, sessionMsg.getUuid());
        }

        checkAndAutoCompact(sessionId);

        return callLlm(sessionId, messages);
    }

    /**
     * 加载或创建会话消息列表
     */
    private List<Message> loadOrCreateMessages(String sessionId) {
        List<Message> cached = sessionMessageCache.get(sessionId);
        if (cached != null) {
            return cached;
        }

        List<Message> messages = sessionStore.getLlmMessages(sessionId);
        sessionMessageCache.put(sessionId, messages);

        List<SessionMessage> sessionMessages = sessionStore.loadSession(sessionId);
        if (!sessionMessages.isEmpty()) {
            lastMessageUuid.put(sessionId, sessionMessages.get(sessionMessages.size() - 1).getUuid());
        }

        return messages;
    }

    /**
     * 检查并执行自动压缩
     */
    private void checkAndAutoCompact(String sessionId) {
        try {
            ContextStats stats = sessionStore.getContextStats(sessionId);
            if (compactionService.needsCompaction(stats)) {
                log.info("Auto-compaction triggered for session {}, usage: {}%",
                        sessionId, stats.getUsagePercent());
                CompactionResult result = sessionStore.autocompact(sessionId, null);
                if (result.isSuccess() && result.getCompactedMessageCount() > 0) {
                    sessionMessageCache.remove(sessionId);
                    log.info("Auto-compaction completed: {}", result.format());
                }
            }
        } catch (Exception e) {
            log.warn("Auto-compaction check failed for session {}: {}", sessionId, e.getMessage());
        }
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

        List<Message> messages = sessionMessageCache.get(sessionId);
        if (messages == null) {
            messages = loadOrCreateMessages(sessionId);
        }

        for (ToolCallResult result : request.getResults()) {
            String content = result.isSuccess() ? result.getContent() : "Error: " + result.getError();
            boolean isError = !result.isSuccess();

            ToolResultCache.MicrocompactResult microResult = toolResultCache.process(
                    sessionId, result.getCallId(), result.getToolName(), content);

            if (microResult.isOffloaded()) {
                messages.add(buildMessage(Role.TOOL.getValue(), microResult.getPreview(), result.getCallId()));

                String parentUuid = lastMessageUuid.get(sessionId);
                SessionMessage sessionMsg = SessionMessage.toolResultOffloaded(
                        sessionId, result.getCallId(), result.getToolName(),
                        microResult.getStoragePath(), microResult.getPreview(), isError, parentUuid);
                sessionStore.appendMessage(sessionId, sessionMsg);
                lastMessageUuid.put(sessionId, sessionMsg.getUuid());

                log.debug("Tool result offloaded: {} -> {}", result.getCallId(), microResult.getStoragePath());
            } else {
                messages.add(buildMessage(Role.TOOL.getValue(), content, result.getCallId()));

                String parentUuid = lastMessageUuid.get(sessionId);
                SessionMessage sessionMsg = SessionMessage.toolResult(
                        sessionId, result.getCallId(), result.getToolName(), content, isError, parentUuid);
                sessionStore.appendMessage(sessionId, sessionMsg);
                lastMessageUuid.put(sessionId, sessionMsg.getUuid());
            }
        }

        checkAndAutoCompact(sessionId);

        return callLlm(sessionId, messages);
    }

    private AgentResponse callLlm(String sessionId, List<Message> messages) {
        long startTime = System.currentTimeMillis();
        try {
            // 使用 Generation API 调用纯文本模型
            return callTextLlm(sessionId, messages, startTime);
        } catch (Exception e) {
            log.error("Failed to call LLM", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * 调用纯文本 LLM
     */
    private AgentResponse callTextLlm(String sessionId, List<Message> messages, long startTime) {
        try {
            GenerationParam.GenerationParamBuilder<?, ?> paramBuilder = GenerationParam.builder()
                    .model(config.getModelName())
                    .apiKey(getApiKey())
                    .messages(messages)
                    .resultFormat(ConversationParam.ResultFormat.MESSAGE);

            List<ToolSchema> tools = sessionTools.get(sessionId);
            if (tools != null && !tools.isEmpty()) {
                List<ToolBase> toolBases = convertTools(tools);
                paramBuilder.tools(toolBases);
            }

            GenerationParam param = paramBuilder.build();

            log.debug("Calling LLM with {} messages", messages.size());
            GenerationResult result = generation.call(param);

            long durationMs = System.currentTimeMillis() - startTime;
            return parseResponse(sessionId, result, messages, durationMs);

        } catch (Exception e) {
            log.error("Failed to call LLM", e);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }

    private List<ToolBase> convertTools(List<ToolSchema> tools) {
        List<ToolBase> result = new ArrayList<>();
        for (ToolSchema tool : tools) {
            JsonNode params = tool.getParameters();
            FunctionDefinition.FunctionDefinitionBuilder builder = FunctionDefinition.builder()
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

    private AgentResponse parseResponse(String sessionId, GenerationResult result, List<Message> messages,
                                        long durationMs) {
        if (result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            return AgentResponse.error(sessionId, "EMPTY_RESPONSE", "LLM returned empty response");
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

                    String parentUuid = lastMessageUuid.get(sessionId);
                    SessionMessage toolCallMsg = SessionMessage.toolCall(
                            sessionId, funcCall.getId(), function.getName(), arguments, parentUuid);
                    sessionStore.appendMessage(sessionId, toolCallMsg);
                    lastMessageUuid.put(sessionId, toolCallMsg.getUuid());
                }
            }

            if (!agentToolCalls.isEmpty()) {
                log.info("LLM requested {} tool calls", agentToolCalls.size());
                return AgentResponse.toolCalls(sessionId, agentToolCalls, tokenUsage, durationMs);
            }
        }

        String content = message.getContent();
        String finishReason = choice.getFinishReason();
        boolean done = "stop".equals(finishReason) || "length".equals(finishReason);

        SessionMessage.TokenUsageInfo usageInfo = SessionMessage.TokenUsageInfo.builder()
                .inputTokens(tokenUsage.getInputTokens())
                .outputTokens(tokenUsage.getOutputTokens())
                .totalTokens(tokenUsage.getTotalTokens())
                .build();

        String parentUuid = lastMessageUuid.get(sessionId);
        SessionMessage assistantMsg = SessionMessage.assistant(
                sessionId, content != null ? content : "", config.getModelName(), usageInfo, parentUuid);
        sessionStore.appendMessage(sessionId, assistantMsg);
        lastMessageUuid.put(sessionId, assistantMsg.getUuid());

        if (done) {
            sessionStore.markCompleted(sessionId, "Task completed");
        }

        log.info("LLM returned message, done={}", done);
        return AgentResponse.message(sessionId, content != null ? content : "", done, tokenUsage, durationMs);
    }

    /**
     * 从 GenerationResult 中提取 Token 使用信息
     */
    private TokenUsage extractTokenUsage(GenerationResult result) {
        if (result == null || result.getUsage() == null) {
            return TokenUsage.empty();
        }

        GenerationUsage usage = result.getUsage();
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
     * 构建消息对象
     *
     * @param role 角色（user/tool/assistant）
     * @param content 文本内容
     * @param toolCallId Tool 调用 ID（仅 tool 角色需要）
     * @return Message 对象
     */
    private Message buildMessage(String role, String content, String toolCallId) {
        Message message = new Message();
        message.setRole(role);
        message.setContent(content);

        if (toolCallId != null) {
            message.setToolCallId(toolCallId);
        }

        return message;
    }

    @Override
    public boolean healthCheck() {
        return true;
    }

    @Override
    public void close() {
        sessionMessageCache.clear();
        sessionTools.clear();
        lastMessageUuid.clear();
        toolResultCache.close();
        sessionStore.close();
        log.info("DirectLlmClient closed");
    }

    public void clearSession(String sessionId) {
        sessionMessageCache.remove(sessionId);
        sessionTools.remove(sessionId);
        lastMessageUuid.remove(sessionId);
        toolResultCache.clearSession(sessionId);
    }

    public void clearAllSessions() {
        sessionMessageCache.clear();
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
            sessionMessageCache.remove(sessionId);
        }
        return result;
    }

    /**
     * 获取项目路径
     */
    public Path getProjectPath() {
        return projectPath;
    }

    /**
     * 获取会话配置
     */
    public SessionConfig getSessionConfig() {
        return sessionConfig;
    }
}
