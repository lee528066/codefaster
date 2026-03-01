package com.coderfaster.agent.session.compaction;

import com.coderfaster.agent.session.SessionConfig;
import com.coderfaster.agent.session.SessionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 压缩服务
 * 实现三层压缩机制中的自动压缩和手动压缩
 *
 * 压缩流程 = 摘要 + 恢复（Summarization + Rehydration）:
 * 1. 生成结构化 Working State 摘要
 * 2. 重新读取最近访问的文件（Rehydration）
 * 3. 注入 Continuation Message
 */
public class CompactionService {

    private static final Logger log = LoggerFactory.getLogger(CompactionService.class);

    /**
     * Continuation Message 模板
     */
    private static final String CONTINUATION_TEMPLATE =
            "This session is being continued from a previous conversation that ran out\n" +
            "of context. The summary below covers the earlier portion of the conversation.\n" +
            "\n" +
            "%s\n" +
            "\n" +
            "Please continue the conversation from where we left it off without asking\n" +
            "the user any further questions. Continue with the last task that you were\n" +
            "asked to work on.";

    private final SessionConfig config;
    private final Path projectPath;

    public CompactionService(Path projectPath, SessionConfig config) {
        this.projectPath = projectPath;
        this.config = config;
    }

    /**
     * 检查是否需要压缩
     */
    public boolean needsCompaction(ContextStats stats) {
        return stats.needsCompaction(
                config.getAutocompactThresholdPercent(),
                config.getAutocompactOutputHeadroom(),
                config.getAutocompactCompactionHeadroom()
        );
    }

    /**
     * 执行自动压缩
     */
    public CompactionResult compact(String sessionId, List<SessionMessage> messages, String focusHint) {
        if (messages.size() < 10) {
            return CompactionResult.skipped("Too few messages to compact");
        }

        int tokensBefore = estimateTokens(messages);

        int keepRecent = calculateKeepRecentCount(messages);
        int compactIndex = messages.size() - keepRecent;

        List<SessionMessage> toCompact = new ArrayList<>(messages.subList(0, compactIndex));
        List<SessionMessage> toKeep = new ArrayList<>(messages.subList(compactIndex, messages.size()));

        SessionMessage.WorkingState workingState = extractWorkingState(toCompact, toKeep, focusHint);

        String summaryText = formatWorkingState(workingState, focusHint);

        SessionMessage.CompactionInfo compactionInfo = SessionMessage.CompactionInfo.builder()
                .compactedCount(toCompact.size())
                .compactedTokens(estimateTokens(toCompact))
                .workingState(workingState)
                .summary(summaryText)
                .build();

        String parentUuid = toCompact.isEmpty() ? null : toCompact.get(toCompact.size() - 1).getUuid();
        SessionMessage summaryMsg = SessionMessage.summary(sessionId, compactionInfo, parentUuid);

        List<String> rehydratedFiles = extractRecentFiles(messages);

        int tokensAfter = estimateTokens(List.of(summaryMsg)) + estimateTokens(toKeep);

        log.info("Compacted session {}: {} messages -> {} summary + {} retained, tokens {} -> {}",
                sessionId, toCompact.size(), 1, toKeep.size(), tokensBefore, tokensAfter);

        return CompactionResult.builder()
                .success(true)
                .compactedMessageCount(toCompact.size())
                .tokensBefore(tokensBefore)
                .tokensAfter(tokensAfter)
                .tokensSaved(tokensBefore - tokensAfter)
                .summaryMessage(summaryMsg)
                .retainedMessages(toKeep)
                .rehydratedFiles(rehydratedFiles)
                .type(focusHint != null ? CompactionResult.CompactionType.MANUAL : CompactionResult.CompactionType.AUTO)
                .focusHint(focusHint)
                .build();
    }

    /**
     * 计算保留的最近消息数
     */
    private int calculateKeepRecentCount(List<SessionMessage> messages) {
        int minKeep = 5;
        int maxKeep = 30;

        int calculated = messages.size() / 3;

        return Math.max(minKeep, Math.min(maxKeep, calculated));
    }

    /**
     * 提取工作状态
     */
    private SessionMessage.WorkingState extractWorkingState(List<SessionMessage> compacted,
                                                            List<SessionMessage> retained,
                                                            String focusHint) {
        String userIntent = extractUserIntent(compacted);
        List<String> pendingTasks = extractPendingTasks(compacted, retained);
        String currentState = extractCurrentState(retained);
        List<SessionMessage.ErrorAndFix> errorsAndFixes = extractErrorsAndFixes(compacted);
        List<SessionMessage.TouchedFile> touchedFiles = extractTouchedFiles(compacted);
        List<String> keyDecisions = extractKeyDecisions(compacted, focusHint);
        String nextStep = extractNextStep(retained);

        return SessionMessage.WorkingState.builder()
                .userIntent(userIntent)
                .pendingTasks(pendingTasks)
                .currentState(currentState)
                .errorsAndFixes(errorsAndFixes)
                .touchedFiles(touchedFiles)
                .keyDecisions(keyDecisions)
                .nextStep(nextStep)
                .build();
    }

    /**
     * 提取用户意图
     */
    private String extractUserIntent(List<SessionMessage> messages) {
        StringBuilder intent = new StringBuilder();

        for (SessionMessage msg : messages) {
            if (msg.getType() == SessionMessage.MessageType.USER && msg.getMessage() != null) {
                String content = msg.getMessage().getContent();
                if (content != null && !content.isEmpty()) {
                    if (intent.length() == 0) {
                        intent.append("Initial request: ").append(truncate(content, 200));
                    }
                    break;
                }
            }
        }

        int userMsgCount = 0;
        for (SessionMessage msg : messages) {
            if (msg.getType() == SessionMessage.MessageType.USER) {
                userMsgCount++;
            }
        }

        if (userMsgCount > 1) {
            intent.append(" (").append(userMsgCount).append(" follow-up messages)");
        }

        return intent.toString();
    }

    /**
     * 提取待处理任务
     */
    private List<String> extractPendingTasks(List<SessionMessage> compacted, List<SessionMessage> retained) {
        List<String> tasks = new ArrayList<>();

        for (SessionMessage msg : retained) {
            if (msg.getType() == SessionMessage.MessageType.ASSISTANT && msg.getMessage() != null) {
                String content = msg.getMessage().getContent();
                if (content != null) {
                    if (content.contains("TODO") || content.contains("todo") ||
                        content.contains("next") || content.contains("Then")) {
                        String taskHint = extractTaskFromContent(content);
                        if (taskHint != null) {
                            tasks.add(taskHint);
                        }
                    }
                }
            }
        }

        return tasks.stream().distinct().limit(5).collect(Collectors.toList());
    }

    private String extractTaskFromContent(String content) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- [ ]") || trimmed.startsWith("* [ ]")) {
                return trimmed.substring(5).trim();
            }
            if (trimmed.toLowerCase().startsWith("todo:")) {
                return trimmed.substring(5).trim();
            }
            if (trimmed.toLowerCase().startsWith("next:")) {
                return trimmed.substring(5).trim();
            }
        }
        return null;
    }

    /**
     * 提取当前状态
     */
    private String extractCurrentState(List<SessionMessage> retained) {
        for (int i = retained.size() - 1; i >= 0; i--) {
            SessionMessage msg = retained.get(i);
            if (msg.getType() == SessionMessage.MessageType.ASSISTANT && msg.getMessage() != null) {
                String content = msg.getMessage().getContent();
                if (content != null && !content.isEmpty()) {
                    return "Last assistant response: " + truncate(content, 150);
                }
            }
        }
        return "No recent assistant response";
    }

    /**
     * 提取错误和修复
     */
    private List<SessionMessage.ErrorAndFix> extractErrorsAndFixes(List<SessionMessage> messages) {
        List<SessionMessage.ErrorAndFix> results = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            SessionMessage msg = messages.get(i);
            if (msg.getType() == SessionMessage.MessageType.TOOL_RESULT && msg.getMessage() != null) {
                if (Boolean.TRUE.equals(msg.getMessage().getIsError())) {
                    String errorContent = msg.getMessage().getContent();

                    String fix = null;
                    for (int j = i + 1; j < Math.min(i + 5, messages.size()); j++) {
                        SessionMessage nextMsg = messages.get(j);
                        if (nextMsg.getType() == SessionMessage.MessageType.ASSISTANT && nextMsg.getMessage() != null) {
                            fix = truncate(nextMsg.getMessage().getContent(), 100);
                            break;
                        }
                    }

                    results.add(SessionMessage.ErrorAndFix.builder()
                            .error(truncate(errorContent, 100))
                            .fix(fix != null ? fix : "Not resolved in compacted messages")
                            .build());
                }
            }
        }

        return results.stream().limit(5).collect(Collectors.toList());
    }

    /**
     * 提取已修改的文件
     */
    private List<SessionMessage.TouchedFile> extractTouchedFiles(List<SessionMessage> messages) {
        Map<String, String> fileReasons = new LinkedHashMap<>();

        for (SessionMessage msg : messages) {
            if (msg.getType() == SessionMessage.MessageType.TOOL_CALL && msg.getMessage() != null) {
                String toolName = msg.getMessage().getToolName();
                var input = msg.getMessage().getToolInput();

                if (input != null && input.has("path")) {
                    String path = input.get("path").asText();
                    String reason = getToolReason(toolName);
                    fileReasons.merge(path, reason, (old, newReason) -> old + ", " + newReason);
                }
            }
        }

        return fileReasons.entrySet().stream()
                .limit(10)
                .map(e -> SessionMessage.TouchedFile.builder()
                        .path(e.getKey())
                        .reason(e.getValue())
                        .build())
                .collect(Collectors.toList());
    }

    private String getToolReason(String toolName) {
        if (toolName == null) return "accessed";
        switch (toolName) {
            case "Read":
                return "read";
            case "Write":
                return "created";
            case "Edit":
            case "StrReplace":
                return "modified";
            case "Delete":
                return "deleted";
            default:
                return "accessed";
        }
    }

    /**
     * 提取关键决策
     */
    private List<String> extractKeyDecisions(List<SessionMessage> messages, String focusHint) {
        List<String> decisions = new ArrayList<>();

        Set<String> toolsUsed = new HashSet<>();
        for (SessionMessage msg : messages) {
            if (msg.getType() == SessionMessage.MessageType.TOOL_CALL && msg.getMessage() != null) {
                String toolName = msg.getMessage().getToolName();
                if (toolName != null) {
                    toolsUsed.add(toolName);
                }
            }
        }

        if (!toolsUsed.isEmpty()) {
            decisions.add("Tools used: " + String.join(", ", toolsUsed));
        }

        if (focusHint != null) {
            decisions.add("Focus: " + focusHint);
        }

        return decisions;
    }

    /**
     * 提取下一步操作
     */
    private String extractNextStep(List<SessionMessage> retained) {
        for (int i = retained.size() - 1; i >= 0; i--) {
            SessionMessage msg = retained.get(i);
            if (msg.getType() == SessionMessage.MessageType.USER && msg.getMessage() != null) {
                String content = msg.getMessage().getContent();
                if (content != null && !content.isEmpty()) {
                    return "Continue with: " + truncate(content, 100);
                }
            }
        }
        return "Continue with the previous task";
    }

    /**
     * 格式化工作状态为摘要文本
     */
    private String formatWorkingState(SessionMessage.WorkingState state, String focusHint) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Session Working State\n\n");

        if (focusHint != null) {
            sb.append("**Focus**: ").append(focusHint).append("\n\n");
        }

        sb.append("### User Intent\n");
        sb.append(state.getUserIntent()).append("\n\n");

        if (state.getPendingTasks() != null && !state.getPendingTasks().isEmpty()) {
            sb.append("### Pending Tasks\n");
            for (String task : state.getPendingTasks()) {
                sb.append("- ").append(task).append("\n");
            }
            sb.append("\n");
        }

        sb.append("### Current State\n");
        sb.append(state.getCurrentState()).append("\n\n");

        if (state.getErrorsAndFixes() != null && !state.getErrorsAndFixes().isEmpty()) {
            sb.append("### Errors Encountered and Fixes\n");
            for (SessionMessage.ErrorAndFix ef : state.getErrorsAndFixes()) {
                sb.append("- **Error**: ").append(ef.getError()).append("\n");
                sb.append("  **Fix**: ").append(ef.getFix()).append("\n");
            }
            sb.append("\n");
        }

        if (state.getTouchedFiles() != null && !state.getTouchedFiles().isEmpty()) {
            sb.append("### Files Touched\n");
            for (SessionMessage.TouchedFile tf : state.getTouchedFiles()) {
                sb.append("- `").append(tf.getPath()).append("` (").append(tf.getReason()).append(")\n");
            }
            sb.append("\n");
        }

        if (state.getKeyDecisions() != null && !state.getKeyDecisions().isEmpty()) {
            sb.append("### Key Decisions\n");
            for (String decision : state.getKeyDecisions()) {
                sb.append("- ").append(decision).append("\n");
            }
            sb.append("\n");
        }

        sb.append("### Next Step\n");
        sb.append(state.getNextStep()).append("\n");

        return sb.toString();
    }

    /**
     * 生成 Continuation Message
     */
    public String buildContinuationMessage(String summary) {
        return String.format(CONTINUATION_TEMPLATE, summary);
    }

    /**
     * 提取最近访问的文件路径（用于 Rehydration）
     */
    public List<String> extractRecentFiles(List<SessionMessage> messages) {
        Set<String> recentFiles = new LinkedHashSet<>();

        for (int i = messages.size() - 1; i >= 0 && recentFiles.size() < config.getAutocompactRehydrateFileCount(); i--) {
            SessionMessage msg = messages.get(i);
            if (msg.getType() == SessionMessage.MessageType.TOOL_CALL && msg.getMessage() != null) {
                String toolName = msg.getMessage().getToolName();
                if ("Read".equals(toolName) || "Write".equals(toolName) ||
                    "Edit".equals(toolName) || "StrReplace".equals(toolName)) {
                    var input = msg.getMessage().getToolInput();
                    if (input != null && input.has("path")) {
                        String path = input.get("path").asText();
                        if (Files.exists(Path.of(path))) {
                            recentFiles.add(path);
                        }
                    }
                }
            }
        }

        return new ArrayList<>(recentFiles);
    }

    /**
     * 执行 Rehydration：重新读取最近的文件
     */
    public Map<String, String> rehydrateFiles(List<String> filePaths) {
        Map<String, String> contents = new LinkedHashMap<>();

        for (String filePath : filePaths) {
            try {
                Path path = Path.of(filePath);
                if (Files.exists(path) && Files.isRegularFile(path)) {
                    long size = Files.size(path);
                    if (size < 50000) {
                        String content = Files.readString(path, StandardCharsets.UTF_8);
                        contents.put(filePath, content);
                        log.debug("Rehydrated file: {} ({} bytes)", filePath, size);
                    } else {
                        log.debug("Skipped large file for rehydration: {} ({} bytes)", filePath, size);
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to rehydrate file: {}", filePath, e);
            }
        }

        return contents;
    }

    /**
     * 估算 token 数量
     */
    private int estimateTokens(List<SessionMessage> messages) {
        int tokens = 0;
        for (SessionMessage msg : messages) {
            if (msg.getMessage() == null) continue;

            String content = msg.getMessage().getContent();
            if (content != null) {
                tokens += content.length() / 4;
            }

            var compaction = msg.getMessage().getCompaction();
            if (compaction != null && compaction.getSummary() != null) {
                tokens += compaction.getSummary().length() / 4;
            }
        }
        return tokens;
    }

    /**
     * 截断文本
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
