package com.coderfaster.agent.session;

import com.coderfaster.agent.model.ToolCall;
import com.coderfaster.agent.model.ToolCallResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 会话
 * 管理单次会话的状态和历史
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 创建时间
     */
    @Builder.Default
    private Instant createdAt = Instant.now();
    
    /**
     * 最后活动时间
     */
    @Builder.Default
    private Instant lastActivityAt = Instant.now();
    
    /**
     * 会话状态
     */
    @Builder.Default
    private SessionState state = SessionState.ACTIVE;
    
    /**
     * 用户消息
     */
    private String userMessage;
    
    /**
     * 迭代次数
     */
    @Builder.Default
    private int iterationCount = 0;
    
    /**
     * 消息历史
     */
    @Builder.Default
    private List<Message> messages = new ArrayList<>();
    
    /**
     * Tool 调用历史
     */
    @Builder.Default
    private List<ToolCallRecord> toolCallHistory = new ArrayList<>();
    
    /**
     * 会话元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new ConcurrentHashMap<>();
    
    /**
     * 会话状态枚举
     */
    public enum SessionState {
        /**
         * 活跃中
         */
        ACTIVE,
        
        /**
         * 等待用户确认
         */
        WAITING_CONFIRMATION,
        
        /**
         * 执行中
         */
        EXECUTING,
        
        /**
         * 已完成
         */
        COMPLETED,
        
        /**
         * 已取消
         */
        CANCELLED,
        
        /**
         * 错误
         */
        ERROR
    }
    
    /**
     * 消息记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        public enum Role { USER, ASSISTANT, TOOL }
        
        private Role role;
        private String content;
        private Instant timestamp;
        
        public static Message user(String content) {
            return Message.builder()
                    .role(Role.USER)
                    .content(content)
                    .timestamp(Instant.now())
                    .build();
        }
        
        public static Message assistant(String content) {
            return Message.builder()
                    .role(Role.ASSISTANT)
                    .content(content)
                    .timestamp(Instant.now())
                    .build();
        }
        
        public static Message tool(String content) {
            return Message.builder()
                    .role(Role.TOOL)
                    .content(content)
                    .timestamp(Instant.now())
                    .build();
        }
    }
    
    /**
     * Tool 调用记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallRecord {
        private ToolCall call;
        private ToolCallResult result;
        private Instant startTime;
        private Instant endTime;
        private long durationMs;
    }
    
    /**
     * 添加用户消息
     */
    public void addUserMessage(String content) {
        messages.add(Message.user(content));
        updateActivity();
    }
    
    /**
     * 添加助手消息
     */
    public void addAssistantMessage(String content) {
        messages.add(Message.assistant(content));
        updateActivity();
    }
    
    /**
     * 添加 Tool 调用记录
     */
    public void addToolCallRecord(ToolCall call, ToolCallResult result, long durationMs) {
        toolCallHistory.add(ToolCallRecord.builder()
                .call(call)
                .result(result)
                .startTime(Instant.now().minusMillis(durationMs))
                .endTime(Instant.now())
                .durationMs(durationMs)
                .build());
        updateActivity();
    }
    
    /**
     * 增加迭代计数
     */
    public void incrementIteration() {
        iterationCount++;
        updateActivity();
    }
    
    /**
     * 更新活动时间
     */
    public void updateActivity() {
        lastActivityAt = Instant.now();
    }
    
    /**
     * 完成会话
     */
    public void complete() {
        state = SessionState.COMPLETED;
        updateActivity();
    }
    
    /**
     * 取消会话
     */
    public void cancel() {
        state = SessionState.CANCELLED;
        updateActivity();
    }
    
    /**
     * 设置错误状态
     */
    public void setError(String error) {
        state = SessionState.ERROR;
        metadata.put("error", error);
        updateActivity();
    }
    
    /**
     * 获取会话持续时间（毫秒）
     */
    public long getDurationMs() {
        return java.time.Duration.between(createdAt, lastActivityAt).toMillis();
    }
}
