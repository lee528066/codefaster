package com.coderfaster.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 使用信息
 * 记录 LLM 调用的 token 消耗
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    /**
     * 输入 token 数量
     */
    private int inputTokens;

    /**
     * 输出 token 数量
     */
    private int outputTokens;

    /**
     * 总 token 数量
     */
    private int totalTokens;

    /**
     * 创建空的 TokenUsage
     */
    public static TokenUsage empty() {
        return TokenUsage.builder()
                .inputTokens(0)
                .outputTokens(0)
                .totalTokens(0)
                .build();
    }

    /**
     * 累加 token 使用量
     */
    public TokenUsage add(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return TokenUsage.builder()
                .inputTokens(this.inputTokens + other.inputTokens)
                .outputTokens(this.outputTokens + other.outputTokens)
                .totalTokens(this.totalTokens + other.totalTokens)
                .build();
    }

    @Override
    public String toString() {
        return String.format("TokenUsage{input=%d, output=%d, total=%d}",
                inputTokens, outputTokens, totalTokens);
    }
}
