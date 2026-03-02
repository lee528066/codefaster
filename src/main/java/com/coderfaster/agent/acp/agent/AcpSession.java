package com.coderfaster.agent.acp.agent;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.core.AgentEvent;
import com.coderfaster.agent.core.AgentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACP Session 实现
 * 使用 AgentRunner 作为底层执行引擎，支持完整的 ReAct 循环
 */
public class AcpSession {

    private static final Logger log = LoggerFactory.getLogger(AcpSession.class);

    private final String sessionId;
    private final AgentRunner agentRunner;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean cancelled;
    private final AtomicReference<String> currentModelId;
    private final List<String> messageHistory;

    public AcpSession(AgentRunner agentRunner) {
        this.sessionId = UUID.randomUUID().toString();
        this.agentRunner = agentRunner;
        this.objectMapper = new ObjectMapper();
        this.cancelled = new AtomicBoolean(false);
        this.currentModelId = new AtomicReference<>(agentRunner.getConfig().getModelName());
        this.messageHistory = new ArrayList<>();
        log.info("ACP Session created: {}", sessionId);
    }

    /**
     * 从现有会话 ID 恢复会话
     */
    public AcpSession(AgentRunner agentRunner, String sessionId) {
        this.sessionId = sessionId;
        this.agentRunner = agentRunner;
        this.objectMapper = new ObjectMapper();
        this.cancelled = new AtomicBoolean(false);
        this.currentModelId = new AtomicReference<>(agentRunner.getConfig().getModelName());
        this.messageHistory = new ArrayList<>();
        log.info("ACP Session restored: {}", sessionId);
    }

    public String getSessionId() {
        return sessionId;
    }

    /**
     * 处理用户 prompt
     * 使用 AgentRunner 执行完整的 ReAct 循环
     */
    public JsonNode handlePrompt(String prompt) throws Exception {
        if (cancelled.get()) {
            throw new IllegalStateException("Session cancelled");
        }

        log.info("Processing prompt for session {}: {}", sessionId, prompt);

        // 保存消息历史
        messageHistory.add("User: " + prompt);

        // 构建最终消息（包含上下文历史）
        String fullPrompt = buildFullPrompt();

        // 使用 AgentRunner 执行任务（这将执行完整的 ReAct 循环）
        AgentResult result = agentRunner.run(fullPrompt, sessionId);

        // 构建响应
        ObjectNode responseNode = objectMapper.createObjectNode();
        responseNode.put("sessionId", sessionId);
        responseNode.put("status", result.isSuccess() ? "completed" : "error");

        if (result.isSuccess()) {
            String message = result.getContent();
            responseNode.put("message", message);
            messageHistory.add("Assistant: " + message);
        } else {
            responseNode.put("error", result.getError());
        }

        return responseNode;
    }

    /**
     * 构建完整的 prompt（包含历史消息）
     */
    private String buildFullPrompt() {
        // 简单实现：只使用最新的用户消息
        // TODO: 可以实现更复杂的上下文管理
        return messageHistory.get(messageHistory.size() - 1);
    }

    public void cancel() {
        cancelled.set(true);
        log.info("Session cancelled: {}", sessionId);
    }

    public void setModel(String modelId) {
        currentModelId.set(modelId);
        log.info("Switching model for session {} to: {}", sessionId, modelId);
    }

    public String getCurrentModelId() {
        return currentModelId.get();
    }

    public List<String> getMessageHistory() {
        return new ArrayList<>(messageHistory);
    }

    public void close() {
        log.info("Session closed: {}", sessionId);
    }
}
