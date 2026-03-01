package com.coderfaster.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 事件
 * 用于通知上层应用 Agent 执行过程中的各种状态变化
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent {
    
    /**
     * 事件类型
     */
    private EventType type;
    
    /**
     * 事件时间
     */
    @Builder.Default
    private Instant timestamp = Instant.now();
    
    /**
     * 消息内容
     */
    private String message;
    
    /**
     * Tool 名称（Tool 相关事件）
     */
    private String toolName;
    
    /**
     * Tool 调用 ID（用于配对 TOOL_CALL 和 TOOL_RESULT）
     */
    private String callId;
    
    /**
     * Tool 参数（Tool 相关事件）
     */
    private JsonNode toolParams;
    
    /**
     * 是否成功（Tool 执行结束事件）
     */
    private Boolean success;
    
    /**
     * 迭代次数（迭代事件）
     */
    private Integer iteration;
    
    /**
     * 事件类型枚举
     */
    public enum EventType {
        /**
         * Agent 开始执行
         */
        STARTED,
        
        /**
         * 迭代开始
         */
        ITERATION_START,
        
        /**
         * 迭代结束
         */
        ITERATION_END,
        
        /**
         * 收到消息
         */
        MESSAGE,
        
        /**
         * 进度更新
         */
        PROGRESS,
        
        /**
         * Tool 调用开始
         */
        TOOL_CALL_START,
        
        /**
         * Tool 调用结束
         */
        TOOL_CALL_END,
        
        /**
         * 执行完成
         */
        COMPLETED,
        
        /**
         * 执行错误
         */
        ERROR,
        
        /**
         * 执行取消
         */
        CANCELLED
    }
    
    // ========== 工厂方法 ==========
    
    public static AgentEvent started(String userMessage) {
        return AgentEvent.builder()
                .type(EventType.STARTED)
                .message(userMessage)
                .build();
    }
    
    public static AgentEvent iterationStart(int iteration) {
        return AgentEvent.builder()
                .type(EventType.ITERATION_START)
                .iteration(iteration)
                .build();
    }
    
    public static AgentEvent iterationEnd(int iteration) {
        return AgentEvent.builder()
                .type(EventType.ITERATION_END)
                .iteration(iteration)
                .build();
    }
    
    public static AgentEvent message(String message) {
        return AgentEvent.builder()
                .type(EventType.MESSAGE)
                .message(message)
                .build();
    }
    
    public static AgentEvent progress(String message) {
        return AgentEvent.builder()
                .type(EventType.PROGRESS)
                .message(message)
                .build();
    }
    
    public static AgentEvent toolCallStart(String toolName, String callId, JsonNode params) {
        return AgentEvent.builder()
                .type(EventType.TOOL_CALL_START)
                .toolName(toolName)
                .callId(callId)
                .toolParams(params)
                .build();
    }
    
    public static AgentEvent toolCallEnd(String toolName, String callId, boolean success, String result) {
        return AgentEvent.builder()
                .type(EventType.TOOL_CALL_END)
                .toolName(toolName)
                .callId(callId)
                .success(success)
                .message(result)
                .build();
    }
    
    public static AgentEvent completed(String content) {
        return AgentEvent.builder()
                .type(EventType.COMPLETED)
                .message(content)
                .build();
    }
    
    public static AgentEvent error(String error) {
        return AgentEvent.builder()
                .type(EventType.ERROR)
                .message(error)
                .build();
    }
    
    public static AgentEvent cancelled() {
        return AgentEvent.builder()
                .type(EventType.CANCELLED)
                .build();
    }
}
