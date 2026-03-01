package com.coderfaster.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {
    
    /**
     * 是否执行成功
     */
    private boolean success;
    
    /**
     * 返回给 LLM 的内容
     */
    private String content;
    
    /**
     * 展示给用户的数据（可选，用于 UI 展示）
     */
    private Object displayData;
    
    /**
     * 错误信息（如果失败）
     */
    private String error;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;
    
    /**
     * 创建成功结果
     */
    public static ToolResult success(String content) {
        return ToolResult.builder()
                .success(true)
                .content(content)
                .build();
    }
    
    /**
     * 创建带展示数据的成功结果
     */
    public static ToolResult success(String content, Object displayData) {
        return ToolResult.builder()
                .success(true)
                .content(content)
                .displayData(displayData)
                .build();
    }
    
    /**
     * 创建错误结果
     */
    public static ToolResult error(String error) {
        return ToolResult.builder()
                .success(false)
                .error(error)
                .content("Error: " + error)
                .build();
    }
    
    /**
     * 创建失败结果（error 的别名，用于兼容）
     */
    public static ToolResult failure(String errorMessage) {
        return error(errorMessage);
    }
}
