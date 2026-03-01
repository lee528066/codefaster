package com.coderfaster.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool 调用请求
 * 表示 LLM 返回的一次工具调用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {
    
    /**
     * 调用 ID（用于关联请求和响应）
     */
    private String callId;
    
    /**
     * 工具名称
     */
    private String name;
    
    /**
     * 工具参数（JSON 格式）
     */
    private JsonNode arguments;
}
