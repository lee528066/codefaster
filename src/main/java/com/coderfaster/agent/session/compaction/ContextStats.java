package com.coderfaster.agent.session.compaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文统计信息
 * 用于监控 token 使用情况和触发压缩
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextStats {

    /**
     * 总消息数
     */
    private int messageCount;

    /**
     * 用户消息数
     */
    private int userMessageCount;

    /**
     * 助手消息数
     */
    private int assistantMessageCount;

    /**
     * 工具调用数
     */
    private int toolCallCount;

    /**
     * 工具结果数
     */
    private int toolResultCount;

    /**
     * 已落盘的工具结果数
     */
    private int offloadedToolResultCount;

    /**
     * 估算的输入 token 数
     */
    private int estimatedInputTokens;

    /**
     * 累计输出 token 数
     */
    private int totalOutputTokens;

    /**
     * 最大上下文窗口大小
     */
    private int maxContextTokens;

    /**
     * 压缩次数
     */
    private int compactionCount;

    /**
     * 上次压缩后的消息数
     */
    private int messagesSinceLastCompaction;

    /**
     * 获取上下文使用率（百分比）
     */
    public int getUsagePercent() {
        if (maxContextTokens <= 0) {
            return 0;
        }
        return (int) ((estimatedInputTokens * 100L) / maxContextTokens);
    }

    /**
     * 获取剩余可用 token 数
     */
    public int getRemainingTokens() {
        return Math.max(0, maxContextTokens - estimatedInputTokens);
    }

    /**
     * 判断是否需要压缩
     */
    public boolean needsCompaction(int thresholdPercent, int outputHeadroom, int compactionHeadroom) {
        int requiredHeadroom = outputHeadroom + compactionHeadroom;
        int availableTokens = maxContextTokens - estimatedInputTokens;
        
        if (availableTokens < requiredHeadroom) {
            return true;
        }
        
        return getUsagePercent() >= thresholdPercent;
    }

    /**
     * 格式化显示信息
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Context Usage: %d%% (%,d / %,d tokens)\n", 
                getUsagePercent(), estimatedInputTokens, maxContextTokens));
        sb.append(String.format("Messages: %d (User: %d, Assistant: %d)\n", 
                messageCount, userMessageCount, assistantMessageCount));
        sb.append(String.format("Tool Calls: %d, Results: %d (Offloaded: %d)\n", 
                toolCallCount, toolResultCount, offloadedToolResultCount));
        if (compactionCount > 0) {
            sb.append(String.format("Compactions: %d, Messages since last: %d\n", 
                    compactionCount, messagesSinceLastCompaction));
        }
        return sb.toString();
    }

    /**
     * 创建空的统计信息
     */
    public static ContextStats empty(int maxContextTokens) {
        return ContextStats.builder()
                .maxContextTokens(maxContextTokens)
                .build();
    }
}
