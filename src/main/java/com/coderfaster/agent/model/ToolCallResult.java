package com.coderfaster.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool 调用结果
 * 用于向 Server 上报 Tool 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResult {
    
    /**
     * 对应的 Tool Call ID
     */
    private String callId;
    
    /**
     * Tool 名称
     */
    private String toolName;
    
    /**
     * 是否执行成功
     */
    private boolean success;
    
    /**
     * 返回给 LLM 的内容
     */
    private String content;
    
    /**
     * 错误信息（如果失败）
     */
    private String error;
    
    /**
     * 从 ToolResult 创建
     */
    public static ToolCallResult from(String callId, ToolResult result) {
        return ToolCallResult.builder()
                .callId(callId)
                .success(result.isSuccess())
                .content(result.getContent())
                .error(result.getError())
                .build();
    }

    /**
     * 从 ToolResult 创建（带 Tool 名称）
     */
    public static ToolCallResult from(String callId, String toolName, ToolResult result) {
        return ToolCallResult.builder()
                .callId(callId)
                .toolName(toolName)
                .success(result.isSuccess())
                .content(result.getContent())
                .error(result.getError())
                .build();
    }
}
