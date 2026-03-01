package com.coderfaster.agent.http;

import com.coderfaster.agent.model.TokenUsage;
import com.coderfaster.agent.model.ToolCall;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 响应
 * Server 返回的统一响应格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 响应类型
     */
    private ResponseType type;
    
    /**
     * 文本消息内容（当 type 为 MESSAGE 或 PARTIAL 时）
     */
    private String message;
    
    /**
     * Tool 调用列表（当 type 为 TOOL_CALLS 时）
     */
    private List<ToolCall> toolCalls;
    
    /**
     * 错误码（当 type 为 ERROR 时）
     */
    private String errorCode;
    
    /**
     * 错误信息（当 type 为 ERROR 时）
     */
    private String errorMessage;
    
    /**
     * 是否完成（当前 Agent 会话是否结束）
     */
    private boolean done;

    /**
     * Token 使用信息（用于 debug 模式）
     */
    private TokenUsage tokenUsage;

    /**
     * LLM 调用耗时（毫秒）
     */
    private Long llmDurationMs;
    
    /**
     * 响应类型枚举
     */
    public enum ResponseType {
        /**
         * 文本消息（最终回复）
         */
        MESSAGE,
        
        /**
         * 部分消息（流式输出）
         */
        PARTIAL,
        
        /**
         * Tool 调用请求
         */
        TOOL_CALLS,
        
        /**
         * 错误
         */
        ERROR,
        
        /**
         * 任务完成
         */
        DONE
    }
    
    /**
     * 检查是否有 Tool 调用
     */
    public boolean hasToolCalls() {
        return type == ResponseType.TOOL_CALLS && toolCalls != null && !toolCalls.isEmpty();
    }
    
    /**
     * 检查是否是最终响应
     */
    public boolean isFinalResponse() {
        return type == ResponseType.MESSAGE || type == ResponseType.DONE || type == ResponseType.ERROR;
    }
    
    /**
     * 检查是否是错误响应
     */
    public boolean isError() {
        return type == ResponseType.ERROR;
    }
    
    /**
     * 创建错误响应
     */
    public static AgentResponse error(String sessionId, String errorCode, String errorMessage) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .type(ResponseType.ERROR)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .done(true)
                .build();
    }
    
    /**
     * 创建消息响应
     */
    public static AgentResponse message(String sessionId, String message, boolean done) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .type(ResponseType.MESSAGE)
                .message(message)
                .done(done)
                .build();
    }

    /**
     * 创建消息响应（带 token 和耗时信息）
     */
    public static AgentResponse message(String sessionId, String message, boolean done,
                                        TokenUsage tokenUsage, long durationMs) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .type(ResponseType.MESSAGE)
                .message(message)
                .done(done)
                .tokenUsage(tokenUsage)
                .llmDurationMs(durationMs)
                .build();
    }
    
    /**
     * 创建 Tool 调用响应
     */
    public static AgentResponse toolCalls(String sessionId, List<ToolCall> toolCalls) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .type(ResponseType.TOOL_CALLS)
                .toolCalls(toolCalls)
                .done(false)
                .build();
    }

    /**
     * 创建 Tool 调用响应（带 token 和耗时信息）
     */
    public static AgentResponse toolCalls(String sessionId, List<ToolCall> toolCalls,
                                          TokenUsage tokenUsage, long durationMs) {
        return AgentResponse.builder()
                .sessionId(sessionId)
                .type(ResponseType.TOOL_CALLS)
                .toolCalls(toolCalls)
                .done(false)
                .tokenUsage(tokenUsage)
                .llmDurationMs(durationMs)
                .build();
    }
}
