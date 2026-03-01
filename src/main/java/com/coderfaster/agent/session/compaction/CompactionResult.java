package com.coderfaster.agent.session.compaction;

import com.coderfaster.agent.session.SessionMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 压缩结果
 * 包含压缩后的摘要和恢复的上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompactionResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;

    /**
     * 压缩的消息数量
     */
    private int compactedMessageCount;

    /**
     * 压缩前的 token 数
     */
    private int tokensBefore;

    /**
     * 压缩后的 token 数
     */
    private int tokensAfter;

    /**
     * 节省的 token 数
     */
    private int tokensSaved;

    /**
     * 生成的摘要消息
     */
    private SessionMessage summaryMessage;

    /**
     * 恢复的消息列表（压缩后保留的消息）
     */
    private List<SessionMessage> retainedMessages;

    /**
     * 重新读取的文件路径
     */
    private List<String> rehydratedFiles;

    /**
     * 压缩时间
     */
    @Builder.Default
    private Instant compactedAt = Instant.now();

    /**
     * 压缩类型
     */
    private CompactionType type;

    /**
     * Focus hint（手动压缩时使用）
     */
    private String focusHint;

    /**
     * 压缩类型枚举
     */
    public enum CompactionType {
        /**
         * 自动压缩
         */
        AUTO,
        
        /**
         * 手动压缩
         */
        MANUAL,
        
        /**
         * 微压缩（工具结果落盘）
         */
        MICRO
    }

    /**
     * 创建成功结果
     */
    public static CompactionResult success(int compactedCount, int tokensBefore, int tokensAfter,
                                           SessionMessage summary, List<SessionMessage> retained,
                                           List<String> rehydratedFiles, CompactionType type) {
        return CompactionResult.builder()
                .success(true)
                .compactedMessageCount(compactedCount)
                .tokensBefore(tokensBefore)
                .tokensAfter(tokensAfter)
                .tokensSaved(tokensBefore - tokensAfter)
                .summaryMessage(summary)
                .retainedMessages(retained)
                .rehydratedFiles(rehydratedFiles)
                .type(type)
                .build();
    }

    /**
     * 创建失败结果
     */
    public static CompactionResult failure(String errorMessage) {
        return CompactionResult.builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 创建跳过结果（无需压缩）
     */
    public static CompactionResult skipped(String reason) {
        return CompactionResult.builder()
                .success(true)
                .errorMessage(reason)
                .compactedMessageCount(0)
                .tokensSaved(0)
                .build();
    }

    /**
     * 格式化显示信息
     */
    public String format() {
        if (!success) {
            return "Compaction failed: " + errorMessage;
        }
        
        if (compactedMessageCount == 0) {
            return "No compaction needed" + (errorMessage != null ? ": " + errorMessage : "");
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Compaction completed (%s)\n", type));
        sb.append(String.format("  Compacted %d messages\n", compactedMessageCount));
        sb.append(String.format("  Tokens: %,d -> %,d (saved %,d)\n", 
                tokensBefore, tokensAfter, tokensSaved));
        
        if (rehydratedFiles != null && !rehydratedFiles.isEmpty()) {
            sb.append(String.format("  Rehydrated %d files\n", rehydratedFiles.size()));
        }
        
        if (focusHint != null) {
            sb.append(String.format("  Focus: %s\n", focusHint));
        }
        
        return sb.toString();
    }
}
