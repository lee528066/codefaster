package com.coderfaster.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    
    /**
     * 执行状态
     */
    private Status status;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 输出内容
     */
    private String content;
    
    /**
     * 错误信息
     */
    private String error;
    
    /**
     * 执行状态枚举
     */
    public enum Status {
        /**
         * 成功
         */
        SUCCESS,
        
        /**
         * 失败
         */
        ERROR,
        
        /**
         * 已取消
         */
        CANCELLED
    }
    
    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
    
    /**
     * 是否失败
     */
    public boolean isError() {
        return status == Status.ERROR;
    }
    
    /**
     * 是否取消
     */
    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }
    
    /**
     * 创建成功结果
     */
    public static AgentResult success(String sessionId, String content) {
        return AgentResult.builder()
                .status(Status.SUCCESS)
                .sessionId(sessionId)
                .content(content)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static AgentResult error(String error) {
        return AgentResult.builder()
                .status(Status.ERROR)
                .error(error)
                .build();
    }
    
    /**
     * 创建取消结果
     */
    public static AgentResult cancelled() {
        return AgentResult.builder()
                .status(Status.CANCELLED)
                .build();
    }
}
