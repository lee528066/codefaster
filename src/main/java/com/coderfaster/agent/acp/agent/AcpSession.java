package com.coderfaster.agent.acp.agent;

import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.http.AgentResponse;
import com.coderfaster.agent.http.ChatRequest;
import com.coderfaster.agent.mock.DirectLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class AcpSession {
    
    private static final Logger log = LoggerFactory.getLogger(AcpSession.class);
    
    private final String sessionId;
    private final AgentConfig config;
    private final DirectLlmClient client;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean cancelled;
    
    public AcpSession(AgentConfig config) {
        this.sessionId = UUID.randomUUID().toString();
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.cancelled = new AtomicBoolean(false);
        this.client = new DirectLlmClient(config, config.getWorkingDirectory());
        log.info("ACP Session created: {}", sessionId);
    }
    
    public String getSessionId() { return sessionId; }
    
    public JsonNode handlePrompt(String prompt) throws Exception {
        if (cancelled.get()) throw new IllegalStateException("Session cancelled");
        
        log.info("Processing prompt for session {}: {}", sessionId, prompt);
        
        ChatRequest request = ChatRequest.builder()
                .sessionId(sessionId)
                .message(prompt)
                .build();
        
        AgentResponse response = client.chat(request);
        
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("status", response.isDone() ? "completed" : "in_progress");
        
        if (response.getToolCalls() != null && !response.getToolCalls().isEmpty()) {
            ArrayNode toolCallsNode = result.putArray("toolCalls");
            response.getToolCalls().forEach(tc -> {
                ObjectNode tcNode = toolCallsNode.addObject();
                tcNode.put("id", tc.getCallId());
                tcNode.put("name", tc.getName());
                tcNode.set("arguments", objectMapper.valueToTree(tc.getArguments()));
            });
        }
        
        if (response.getMessage() != null) {
            result.put("message", response.getMessage());
        }
        
        return result;
    }
    
    public void cancel() {
        cancelled.set(true);
        log.info("Session cancelled: {}", sessionId);
    }
    
    public void setModel(String modelId) {
        log.info("Switching model for session {}: {}", sessionId, modelId);
    }
    
    public void close() {
        try {
            if (client != null) client.close();
            log.info("Session closed: {}", sessionId);
        } catch (Exception e) {
            log.error("Error closing session: {}", sessionId, e);
        }
    }
}
