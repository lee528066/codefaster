package com.coderfaster.agent.http;

import com.coderfaster.agent.model.ToolCallResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tool 结果请求
 * 向 Server 提交 Tool 执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultRequest {
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * Tool 调用结果列表（支持批量）
     */
    private List<ToolCallResult> results;
}
