package com.coderfaster.agent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 会话消息模型
 * 用于 JSONL 格式的会话持久化，每行一个 JSON 对象
 * 
 * 参考 Claude Code 的消息格式设计
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionMessage {

    /**
     * 消息类型
     */
    public enum MessageType {
        /**
         * 系统消息（会话初始化信息）
         */
        SYSTEM,
        
        /**
         * 用户消息
         */
        USER,
        
        /**
         * 助手消息
         */
        ASSISTANT,
        
        /**
         * 工具调用
         */
        TOOL_CALL,
        
        /**
         * 工具结果
         */
        TOOL_RESULT,
        
        /**
         * 压缩摘要
         */
        SUMMARY,
        
        /**
         * 会话结果（完成标记）
         */
        RESULT
    }

    /**
     * 消息类型
     */
    private MessageType type;

    /**
     * 消息唯一标识
     */
    @Builder.Default
    private String uuid = UUID.randomUUID().toString();

    /**
     * 父消息 UUID（用于构建消息链）
     */
    private String parentUuid;

    /**
     * 时间戳
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 当前工作目录
     */
    private String cwd;

    /**
     * 消息内容
     */
    private MessageContent message;

    /**
     * 消息内容结构
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MessageContent {
        /**
         * 角色（user/assistant/tool）
         */
        private String role;

        /**
         * 文本内容
         */
        private String content;

        /**
         * 模型名称（仅 assistant 消息）
         */
        private String model;

        /**
         * 工具调用列表（仅 assistant 消息）
         */
        private List<ToolCallInfo> toolCalls;

        /**
         * Token 使用信息
         */
        private TokenUsageInfo usage;

        /**
         * 工具调用 ID（仅 tool_result）
         */
        private String toolCallId;

        /**
         * 工具名称
         */
        private String toolName;

        /**
         * 工具参数（仅 tool_call）
         */
        private JsonNode toolInput;

        /**
         * 是否为错误结果（仅 tool_result）
         */
        private Boolean isError;

        /**
         * 存储状态（inline/offloaded）
         */
        private String storageStatus;

        /**
         * 离线存储路径（微压缩后）
         */
        private String storagePath;

        /**
         * 内容预览（微压缩后保留）
         */
        private String preview;

        /**
         * 压缩信息（仅 summary）
         */
        private CompactionInfo compaction;
    }

    /**
     * 工具调用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallInfo {
        private String id;
        private String name;
        private JsonNode input;
    }

    /**
     * Token 使用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TokenUsageInfo {
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer totalTokens;
        private Integer cacheReadTokens;
        private Integer cacheWriteTokens;
    }

    /**
     * 压缩信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CompactionInfo {
        /**
         * 压缩的消息数量
         */
        private Integer compactedCount;

        /**
         * 压缩的 token 数量
         */
        private Integer compactedTokens;

        /**
         * 工作状态
         */
        private WorkingState workingState;

        /**
         * 摘要文本
         */
        private String summary;
    }

    /**
     * 工作状态（压缩后的结构化信息）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkingState {
        /**
         * 用户意图
         */
        private String userIntent;

        /**
         * 待处理任务
         */
        private List<String> pendingTasks;

        /**
         * 当前状态
         */
        private String currentState;

        /**
         * 错误及修复
         */
        private List<ErrorAndFix> errorsAndFixes;

        /**
         * 已修改的文件
         */
        private List<TouchedFile> touchedFiles;

        /**
         * 关键决策
         */
        private List<String> keyDecisions;

        /**
         * 下一步操作
         */
        private String nextStep;
    }

    /**
     * 错误及修复记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorAndFix {
        private String error;
        private String fix;
    }

    /**
     * 已修改文件记录
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TouchedFile {
        private String path;
        private String reason;
    }

    // ========== 工厂方法 ==========

    /**
     * 创建系统消息（会话初始化）
     */
    public static SessionMessage system(String sessionId, String cwd) {
        return SessionMessage.builder()
                .type(MessageType.SYSTEM)
                .sessionId(sessionId)
                .cwd(cwd)
                .message(MessageContent.builder()
                        .role("system")
                        .content("Session initialized")
                        .build())
                .build();
    }

    /**
     * 创建用户消息
     */
    public static SessionMessage user(String sessionId, String content, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.USER)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("user")
                        .content(content)
                        .build())
                .build();
    }

    /**
     * 创建助手消息
     */
    public static SessionMessage assistant(String sessionId, String content, String model, 
                                           TokenUsageInfo usage, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.ASSISTANT)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("assistant")
                        .content(content)
                        .model(model)
                        .usage(usage)
                        .build())
                .build();
    }

    /**
     * 创建工具调用消息
     */
    public static SessionMessage toolCall(String sessionId, String callId, String toolName, 
                                          JsonNode input, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.TOOL_CALL)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("assistant")
                        .toolCallId(callId)
                        .toolName(toolName)
                        .toolInput(input)
                        .build())
                .build();
    }

    /**
     * 创建工具结果消息（内联）
     */
    public static SessionMessage toolResult(String sessionId, String callId, String toolName,
                                            String content, boolean isError, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.TOOL_RESULT)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("tool")
                        .toolCallId(callId)
                        .toolName(toolName)
                        .content(content)
                        .isError(isError)
                        .storageStatus("inline")
                        .build())
                .build();
    }

    /**
     * 创建工具结果消息（已落盘）
     */
    public static SessionMessage toolResultOffloaded(String sessionId, String callId, String toolName,
                                                     String storagePath, String preview, 
                                                     boolean isError, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.TOOL_RESULT)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("tool")
                        .toolCallId(callId)
                        .toolName(toolName)
                        .isError(isError)
                        .storageStatus("offloaded")
                        .storagePath(storagePath)
                        .preview(preview)
                        .build())
                .build();
    }

    /**
     * 创建摘要消息
     */
    public static SessionMessage summary(String sessionId, CompactionInfo compaction, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.SUMMARY)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("system")
                        .compaction(compaction)
                        .build())
                .build();
    }

    /**
     * 创建会话结果消息
     */
    public static SessionMessage result(String sessionId, String content, boolean success, String parentUuid) {
        return SessionMessage.builder()
                .type(MessageType.RESULT)
                .sessionId(sessionId)
                .parentUuid(parentUuid)
                .message(MessageContent.builder()
                        .role("system")
                        .content(content)
                        .isError(!success)
                        .build())
                .build();
    }
}
