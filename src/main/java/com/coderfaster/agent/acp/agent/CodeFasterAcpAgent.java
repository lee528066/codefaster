package com.coderfaster.agent.acp.agent;

import com.coderfaster.agent.acp.AcpException;
import com.coderfaster.agent.acp.protocol.AcpMethods;
import com.coderfaster.agent.config.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CodeFasterAcpAgent {
    
    private static final Logger log = LoggerFactory.getLogger(CodeFasterAcpAgent.class);
    
    private final ObjectMapper objectMapper;
    private final Map<String, AcpSession> sessions;
    private AgentConfig config;
    private boolean initialized;
    
    public CodeFasterAcpAgent() {
        this.objectMapper = new ObjectMapper();
        this.sessions = new ConcurrentHashMap<>();
        this.initialized = false;
    }
    
    public JsonNode initialize(JsonNode params) throws Exception {
        log.info("ACP initialize called");
        
        try {
            this.config = AgentConfig.fromLocalConfigWithInit();
        } catch (Exception e) {
            log.error("Failed to load config", e);
            throw AcpException.authRequired("Configuration required: " + e.getMessage());
        }
        
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
    
    public JsonNode newSession(JsonNode params) throws Exception {
        if (!initialized) throw AcpException.internalError("Agent not initialized");
        
        log.info("Creating new ACP session");
        
        AcpSession session = new AcpSession(config);
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
    
    public JsonNode loadSession(JsonNode params) throws Exception {
        String sessionId = params.get("sessionId").asText();
        log.info("Loading session: {}", sessionId);
        return objectMapper.createObjectNode();
    }
    
    public JsonNode listSessions(JsonNode params) throws Exception {
        log.info("Listing sessions");
        
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("items");
        
        sessions.keySet().forEach(id -> {
            ObjectNode item = items.addObject();
            item.put("sessionId", id);
            item.put("cwd", System.getProperty("user.dir"));
            item.put("startTime", System.currentTimeMillis());
            item.put("messageCount", 0);
        });
        
        result.put("hasMore", false);
        return result;
    }
    
    public JsonNode prompt(JsonNode params) throws Exception {
        String sessionId = params.get("sessionId").asText();
        AcpSession session = sessions.get(sessionId);
        
        if (session == null) throw AcpException.invalidParams("Session not found: " + sessionId);
        
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
        
        return session.handlePrompt(prompt);
    }
    
    public JsonNode cancel(JsonNode params) throws Exception {
        String sessionId = params.get("sessionId").asText();
        AcpSession session = sessions.get(sessionId);
        if (session != null) session.cancel();
        return objectMapper.createObjectNode();
    }
    
    public JsonNode setModel(JsonNode params) throws Exception {
        String sessionId = params.get("sessionId").asText();
        String modelId = params.get("modelId").asText();
        
        AcpSession session = sessions.get(sessionId);
        if (session == null) throw AcpException.invalidParams("Session not found: " + sessionId);
        
        session.setModel(modelId);
        
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("modelId", modelId);
        return result;
    }
    
    public JsonNode authenticate(JsonNode params) throws Exception {
        String methodId = params.get("methodId").asText();
        log.info("Authenticating with method: {}", methodId);
        return objectMapper.createObjectNode();
    }
    
    public void close() {
        sessions.values().forEach(AcpSession::close);
        sessions.clear();
        log.info("All sessions closed");
    }
}
