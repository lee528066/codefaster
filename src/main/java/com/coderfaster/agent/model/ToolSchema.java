package com.coderfaster.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool Schema 定义
 * 描述工具的元信息，用于上报给 Server 再转发给 LLM
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSchema {
    
    /**
     * 工具唯一标识（如：read_file, edit_file）
     */
    private String name;
    
    /**
     * 工具描述（给 LLM 看的，非常重要！）
     * 应该详细说明工具的用途、使用场景、参数说明和示例
     */
    private String description;
    
    /**
     * 工具类型
     */
    private ToolKind kind;
    
    /**
     * JSON Schema 格式的参数定义
     */
    private JsonNode parameters;
    
    /**
     * 工具版本
     */
    @Builder.Default
    private String version = "1.0.0";
}
