package com.coderfaster.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 会话元数据
 * 用于列表展示和快速查询，无需加载完整会话内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionMetadata {

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 会话标题（从首条用户消息提取）
     */
    private String title;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 最后活动时间
     */
    private Instant lastActivityAt;

    /**
     * 消息数量
     */
    private int messageCount;

    /**
     * 估算的 token 数量
     */
    private int estimatedTokens;

    /**
     * 是否已压缩
     */
    private boolean compacted;

    /**
     * 压缩次数
     */
    private int compactionCount;

    /**
     * 会话状态
     */
    private SessionStatus status;

    /**
     * 会话文件大小（字节）
     */
    private long fileSizeBytes;

    /**
     * 会话状态枚举
     */
    public enum SessionStatus {
        /**
         * 活跃中
         */
        ACTIVE,
        
        /**
         * 已完成
         */
        COMPLETED,
        
        /**
         * 已取消
         */
        CANCELLED,
        
        /**
         * 错误
         */
        ERROR
    }

    /**
     * 获取会话文件路径
     */
    public Path getSessionFilePath() {
        if (projectPath == null) {
            return null;
        }
        return SessionConfig.getSessionFilePath(Path.of(projectPath), sessionId);
    }

    /**
     * 获取简短的会话 ID（前 8 位）
     */
    public String getShortId() {
        if (sessionId == null || sessionId.length() < 8) {
            return sessionId;
        }
        return sessionId.substring(0, 8);
    }

    /**
     * 获取标题预览（截断到指定长度）
     */
    public String getTitlePreview(int maxLength) {
        if (title == null || title.isEmpty()) {
            return "(No title)";
        }
        if (title.length() <= maxLength) {
            return title;
        }
        return title.substring(0, maxLength - 3) + "...";
    }

    /**
     * 判断会话是否过期
     */
    public boolean isExpired(int retentionDays) {
        if (retentionDays < 0) {
            return false;
        }
        if (lastActivityAt == null) {
            return true;
        }
        Instant threshold = Instant.now().minusSeconds(retentionDays * 24L * 60 * 60);
        return lastActivityAt.isBefore(threshold);
    }

    /**
     * 获取相对时间描述
     */
    public String getRelativeTime() {
        if (lastActivityAt == null) {
            return "Unknown";
        }
        
        long seconds = Instant.now().getEpochSecond() - lastActivityAt.getEpochSecond();
        
        if (seconds < 60) {
            return "Just now";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + " minute" + (minutes > 1 ? "s" : "") + " ago";
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        } else if (seconds < 604800) {
            long days = seconds / 86400;
            return days + " day" + (days > 1 ? "s" : "") + " ago";
        } else if (seconds < 2592000) {
            long weeks = seconds / 604800;
            return weeks + " week" + (weeks > 1 ? "s" : "") + " ago";
        } else {
            long months = seconds / 2592000;
            return months + " month" + (months > 1 ? "s" : "") + " ago";
        }
    }

    /**
     * 创建新会话的元数据
     */
    public static SessionMetadata create(String sessionId, String projectPath) {
        Instant now = Instant.now();
        return SessionMetadata.builder()
                .sessionId(sessionId)
                .projectPath(projectPath)
                .createdAt(now)
                .lastActivityAt(now)
                .messageCount(0)
                .estimatedTokens(0)
                .compacted(false)
                .compactionCount(0)
                .status(SessionStatus.ACTIVE)
                .fileSizeBytes(0)
                .build();
    }
}
