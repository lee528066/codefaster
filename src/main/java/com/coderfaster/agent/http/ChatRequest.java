package com.coderfaster.agent.http;

import com.coderfaster.agent.model.ToolSchema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 聊天请求
 * 发送给 Server 的消息请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    
    /**
     * 会话 ID（首次为空，后续请求携带）
     */
    private String sessionId;
    
    /**
     * 用户消息
     */
    private String message;
    
    /**
     * Client 端支持的 Tool 列表
     */
    private List<ToolSchema> tools;
    
    /**
     * 用户 ID / 工号
     */
    private String uid;
    
    /**
     * 客户端类型（如：idea, cli, web）
     */
    private String clientType;
    
    /**
     * 客户端版本
     */
    private String clientVersion;
    
    /**
     * 额外的上下文信息
     */
    private Map<String, Object> context;
}
