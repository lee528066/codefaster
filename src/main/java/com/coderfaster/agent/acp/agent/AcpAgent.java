package com.coderfaster.agent.acp.agent;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.acp.AcpException;
import com.coderfaster.agent.acp.protocol.AcpError;
import com.coderfaster.agent.acp.protocol.AcpMethods;
import com.coderfaster.agent.acp.protocol.AcpSchema;
import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.core.AgentResult;
import com.coderfaster.agent.session.SessionConfig;
import com.coderfaster.agent.session.SessionMetadata;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP Agent - 使用 AgentRunner 作为底层实现
 * 提供完整的 ReAct 循环支持，包括工具调用执行
 */
public class AcpAgent {

    private static final Logger log = LoggerFactory.getLogger(AcpAgent.class);

    private final AgentConfig config;
    private final AgentRunner agentRunner;
    private final ObjectMapper objectMapper;
    private final Map<String, AcpSession> sessions;
    private boolean initialized;

    public AcpAgent(AgentConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.sessions = new ConcurrentHashMap<>();
        this.initialized = false;

        // 创建会话配置
        SessionConfig sessionConfig = SessionConfig.builder()
                .cleanupPeriodDays(30)
                .cleanupOnStartup(false)
                .build();

        // 创建 AgentRunner，使用 AgentRunner 作为底层实现
        this.agentRunner = AgentRunner.builder(config)
                .sessionConfig(sessionConfig)
                .build();

        log.info("AcpAgent initialized with AgentRunner");
    }

    /**
     * 使用现有的 AgentRunner 创建 AcpAgent
     * 用于 TuiMain 嵌入模式
     */
    public AcpAgent(AgentRunner agentRunner) {
        this.agentRunner = agentRunner;
        this.config = agentRunner.getConfig();
        this.objectMapper = new ObjectMapper();
        this.sessions = new ConcurrentHashMap<>();
        this.initialized = true;
        log.info("AcpAgent initialized with provided AgentRunner");
    }

    /**
     * 处理 initialize 方法
     */
    public JsonNode initialize(JsonNode params) throws Exception {
        log.info("ACP initialize called");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", AcpMethods.PROTOCOL_VERSION);

        ObjectNode agentInfo = result.putObject("agentInfo");
        agentInfo.put("name", "codefaster");
        agentInfo.put("title", "CodeFaster");
        agentInfo.put("version", "1.0.0");

        ArrayNode authMethods = result.putArray("authMethods");
        ObjectNode authMethod = authMethods.addObject();
        authMethod.put("id", "API_KEY");
        authMethod.put("name", "API Key");
        authMethod.put("description", "Use API Key for authentication");

        ObjectNode modes = result.putObject("modes");
        modes.put("currentModeId", "default");
        ArrayNode availableModes = modes.putArray("availableModes");
        ObjectNode mode = availableModes.addObject();
        mode.put("id", "default");
        mode.put("name", "Default");
        mode.put("description", "Default mode");

        ObjectNode capabilities = result.putObject("agentCapabilities");
        capabilities.put("loadSession", true);
        ObjectNode promptCaps = capabilities.putObject("promptCapabilities");
        promptCaps.put("image", false);
        promptCaps.put("audio", false);
        promptCaps.put("embeddedContext", true);

        initialized = true;
        log.info("ACP initialized successfully");
        return result;
    }

    /**
     * 处理 session_new 方法
     */
    public JsonNode newSession(JsonNode params) throws Exception {
        if (!initialized) {
            throw AcpException.internalError("Agent not initialized");
        }

        log.info("Creating new ACP session");

        // 创建新的 AcpSession，使用 AgentRunner
        AcpSession session = new AcpSession(agentRunner);
        sessions.put(session.getSessionId(), session);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("sessionId", session.getSessionId());

        ObjectNode models = result.putObject("models");
        models.put("currentModelId", config.getModelName() + "(API_KEY)");
        ArrayNode availableModels = models.putArray("availableModels");
        ObjectNode model = availableModels.addObject();
        model.put("modelId", config.getModelName() + "(API_KEY)");
        model.put("name", config.getModelName());
        model.put("description", "Default model");
        ObjectNode meta = model.putObject("_meta");
        meta.put("contextLimit", 32000);

        return result;
    }

    /**
     * 处理 session_load 方法
     */
    public JsonNode loadSession(JsonNode params) throws Exception {
        if (!initialized) {
            throw AcpException.internalError("Agent not initialized");
        }

        String sessionId = params.get("sessionId").asText();
        log.info("Loading session: {}", sessionId);

        // 从 AgentRunner 加载会话
        List<SessionMetadata> sessions = agentRunner.findSessionsByPrefix(sessionId);
        if (sessions.isEmpty()) {
            throw AcpException.invalidParams("Session not found: " + sessionId);
        }

        SessionMetadata metadata = sessions.get(0);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sessionId", metadata.getSessionId());
        result.put("createdAt", metadata.getCreatedAt().toEpochMilli());
        result.put("messageCount", metadata.getMessageCount());

        // 恢复会话到 AcpSession
        AcpSession session = new AcpSession(agentRunner, metadata.getSessionId());
        this.sessions.put(metadata.getSessionId(), session);

        return result;
    }

    /**
     * 处理 session_list 方法
     */
    public JsonNode listSessions(JsonNode params) throws Exception {
        log.info("Listing sessions");

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("items");

        // 列出 AgentRunner 中的会话
        List<SessionMetadata> sessionList = agentRunner.listSessions();
        for (SessionMetadata metadata : sessionList) {
            ObjectNode item = items.addObject();
            item.put("sessionId", metadata.getSessionId());
            item.put("cwd", config.getWorkingDirectory().toString());
            item.put("startTime", metadata.getCreatedAt().toEpochMilli());
            item.put("messageCount", metadata.getMessageCount());
        }

        // 也包含内存中的会话
        sessions.keySet().forEach(id -> {
            if (sessionList.stream().noneMatch(m -> m.getSessionId().equals(id))) {
                ObjectNode item = items.addObject();
                item.put("sessionId", id);
                item.put("cwd", config.getWorkingDirectory().toString());
                item.put("startTime", System.currentTimeMillis());
                item.put("messageCount", 0);
            }
        });

        result.put("hasMore", false);
        return result;
    }

    /**
     * 处理 session_prompt 方法
     */
    public JsonNode prompt(JsonNode params) throws Exception {
        if (!initialized) {
            throw AcpException.internalError("Agent not initialized");
        }

        String sessionId = params.get("sessionId").asText();
        AcpSession session = sessions.get(sessionId);

        if (session == null) {
            throw AcpException.invalidParams("Session not found: " + sessionId);
        }

        String prompt = null;
        if (params.has("prompt")) {
            prompt = params.get("prompt").asText();
        } else if (params.has("parts")) {
            JsonNode parts = params.get("parts");
            if (parts.isArray() && parts.size() > 0 && parts.get(0).has("text")) {
                prompt = parts.get(0).get("text").asText();
            }
        }

        if (prompt == null || prompt.isEmpty()) {
            throw AcpException.invalidParams("Prompt is required");
        }

        log.info("Processing prompt for session {}: {}", sessionId, prompt);

        // 使用 AcpSession 处理 prompt，底层调用 AgentRunner
        return session.handlePrompt(prompt);
    }

    /**
     * 处理 session_cancel 方法
     */
    public JsonNode cancel(JsonNode params) throws Exception {
        String sessionId = params.get("sessionId").asText();
        AcpSession session = sessions.get(sessionId);
        if (session != null) {
            session.cancel();
        }
        return objectMapper.createObjectNode();
    }

    /**
     * 处理 session_set_model 方法
     */
    public JsonNode setModel(JsonNode params) throws Exception {
        String sessionId = params.get("sessionId").asText();
        String modelId = params.get("modelId").asText();

        AcpSession session = sessions.get(sessionId);
        if (session == null) {
            throw AcpException.invalidParams("Session not found: " + sessionId);
        }

        session.setModel(modelId);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("modelId", modelId);
        return result;
    }

    /**
     * 处理 authenticate 方法
     */
    public JsonNode authenticate(JsonNode params) throws Exception {
        String methodId = params.get("methodId").asText();
        log.info("Authenticating with method: {}", methodId);
        return objectMapper.createObjectNode();
    }

    /**
     * 关闭资源
     */
    public void close() {
        sessions.values().forEach(AcpSession::close);
        sessions.clear();
        if (agentRunner != null) {
            agentRunner.close();
        }
        log.info("AcpAgent closed");
    }

    /**
     * 获取 AgentRunner（用于测试）
     */
    public AgentRunner getAgentRunner() {
        return agentRunner;
    }
}
