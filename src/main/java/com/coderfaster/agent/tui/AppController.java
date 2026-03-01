package com.coderfaster.agent.tui;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.core.AgentEvent;
import com.coderfaster.agent.core.AgentLoopController;
import com.coderfaster.agent.core.AgentResult;
import com.coderfaster.agent.session.SessionMessage;
import com.coderfaster.agent.session.SessionMetadata;
import com.coderfaster.agent.session.compaction.CompactionResult;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.coderfaster.agent.session.store.SessionStore;
import com.coderfaster.agent.tui.command.CommandRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TUI 控制器（MVC Controller）
 * 持有所有 UI 状态，提供状态查询和命令方法。
 * View 层通过查询方法读取状态，通过命令方法修改状态。
 */
public class AppController implements UIActions {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ========== State ==========

    public enum SessionState {
        IDLE, THINKING, EXECUTING, CONFIRMING, ERROR, COMPLETED
    }

    public enum DialogMode {
        NONE, HELP
    }

    public static class HistoryItem {
        public enum HistoryItemType {
            USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_CALL, TOOL_RESULT, SYSTEM_MESSAGE, ERROR
        }

        private final HistoryItemType type;
        private final String content;
        private final String toolName;
        private final String callId;  // 用于配对 TOOL_CALL 和 TOOL_RESULT
        private final boolean toolSuccess;
        private final Instant timestamp;

        public HistoryItem(HistoryItemType type, String content, String toolName, boolean toolSuccess, Instant timestamp) {
            this(type, content, toolName, null, toolSuccess, timestamp);
        }

        public HistoryItem(HistoryItemType type, String content, String toolName, String callId, boolean toolSuccess, Instant timestamp) {
            this.type = type;
            this.content = content;
            this.toolName = toolName;
            this.callId = callId;
            this.toolSuccess = toolSuccess;
            this.timestamp = timestamp;
        }

        public HistoryItemType type() { return type; }
        public String content() { return content; }
        public String toolName() { return toolName; }
        public String callId() { return callId; }
        public boolean toolSuccess() { return toolSuccess; }
        public Instant timestamp() { return timestamp; }
    }

    public static class ConfirmDialogData {
        private final String title;
        private final String toolName;
        private final String toolParams;

        public ConfirmDialogData(String title, String toolName, String toolParams) {
            this.title = title;
            this.toolName = toolName;
            this.toolParams = toolParams;
        }

        public String title() { return title; }
        public String toolName() { return toolName; }
        public String toolParams() { return toolParams; }
    }

    private volatile SessionState sessionState = SessionState.IDLE;
    private final List<HistoryItem> history = new ArrayList<>();
    private volatile String currentToolName;
    private volatile int currentIteration;
    private volatile String statusMessage;
    private volatile String sessionId;
    private volatile String errorMessage;

    private volatile DialogMode dialogMode = DialogMode.NONE;
    private volatile ConfirmDialogData confirmDialogData;

    private volatile int historyScrollOffset = Integer.MAX_VALUE;
    private volatile int totalRenderLines;
    private volatile int visibleLines;

    private volatile boolean quitting;

    // ========== Dependencies ==========

    private final AgentRunner agentRunner;
    private final CommandRegistry commandRegistry;
    private final ExecutorService executorService;
    private final AtomicBoolean taskRunning = new AtomicBoolean(false);
    private volatile Future<?> currentTask;

    private final Object confirmLock = new Object();
    private volatile Boolean confirmResult;

    private Runnable quitCallback;

    public AppController(AgentRunner agentRunner) {
        this.agentRunner = agentRunner;
        this.commandRegistry = new CommandRegistry(this);
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "agent-task-executor");
            t.setDaemon(true);
            return t;
        });

        setupConfirmationHandler();
    }

    // ========== Queries (read state) ==========

    public SessionState sessionState() { return sessionState; }
    public List<HistoryItem> history() { return Collections.unmodifiableList(new ArrayList<>(history)); }
    public String currentToolName() { return currentToolName; }
    public int currentIteration() { return currentIteration; }
    public String statusMessage() { return statusMessage; }
    public String sessionId() { return sessionId; }
    public String errorMessage() { return errorMessage; }
    public DialogMode dialogMode() { return dialogMode; }
    public ConfirmDialogData confirmDialogData() { return confirmDialogData; }
    public int historyScrollOffset() { return historyScrollOffset; }
    public int totalRenderLines() { return totalRenderLines; }
    public int visibleLines() { return visibleLines; }
    public boolean isQuitting() { return quitting; }
    public boolean isTaskRunning() { return taskRunning.get(); }
    public CommandRegistry commandRegistry() { return commandRegistry; }

    public boolean isLockedToBottom() {
        return historyScrollOffset == Integer.MAX_VALUE;
    }

    // ========== Commands (modify state) ==========

    public void setQuitCallback(Runnable callback) {
        this.quitCallback = callback;
    }

    public void updateRenderMetrics(int totalLines, int visible) {
        this.totalRenderLines = totalLines;
        this.visibleLines = visible;
    }

    // ========== Agent event handlers ==========

    public void onAgentStarted(AgentEvent event) {
        sessionState = SessionState.THINKING;
        addUserMessage(event.getMessage());
    }

    public void onIterationStart(AgentEvent event) {
        currentIteration = event.getIteration();
        statusMessage = "Iteration " + event.getIteration();
    }

    public void onAgentMessage(AgentEvent event) {
        if (event.getMessage() != null && !event.getMessage().isEmpty()) {
            addAssistantMessage(event.getMessage());
        }
    }

    public void onProgress(AgentEvent event) {
        statusMessage = event.getMessage();
    }

    public void onToolCallStart(AgentEvent event) {
        // 如果已经处于 CONFIRMING 状态，不要覆盖它
        // 这是因为确认流程可能在 TOOL_CALL_START 事件被处理之前就已经开始了
        if (sessionState != SessionState.CONFIRMING) {
            sessionState = SessionState.EXECUTING;
        }
        currentToolName = event.getToolName();
        addToolCall(event.getToolName(), event.getCallId(), formatToolParams(event.getToolParams()));
    }

    public void onToolCallEnd(AgentEvent event) {
        // 如果已经处于 CONFIRMING 状态，不要覆盖它
        if (sessionState != SessionState.CONFIRMING) {
            sessionState = SessionState.THINKING;
        }
        currentToolName = null;
        addToolResult(event.getToolName(), event.getCallId(), event.getSuccess(), event.getMessage());
    }

    public void onAgentCompleted(AgentEvent event) {
        sessionState = SessionState.IDLE;
        statusMessage = "Task completed";
        taskRunning.set(false);
    }

    public void onAgentError(AgentEvent event) {
        errorMessage = event.getMessage();
        addError(event.getMessage());
        taskRunning.set(false);
        sessionState = SessionState.IDLE;
    }

    public void onAgentCancelled() {
        sessionState = SessionState.IDLE;
        addSystemMessage("Task cancelled");
        taskRunning.set(false);
    }

    // ========== History ==========

    private void addUserMessage(String message) {
        history.add(new HistoryItem(
                HistoryItem.HistoryItemType.USER_MESSAGE, message, null, false, Instant.now()));
    }

    private void addAssistantMessage(String message) {
        history.add(new HistoryItem(
                HistoryItem.HistoryItemType.ASSISTANT_MESSAGE, message, null, false, Instant.now()));
    }

    private void addToolCall(String toolName, String callId, String params) {
        history.add(new HistoryItem(
                HistoryItem.HistoryItemType.TOOL_CALL, params, toolName, callId, false, Instant.now()));
    }

    private void addToolResult(String toolName, String callId, boolean success, String result) {
        history.add(new HistoryItem(
                HistoryItem.HistoryItemType.TOOL_RESULT, result, toolName, callId, success, Instant.now()));
    }

    public void addSystemMessage(String message) {
        history.add(new HistoryItem(
                HistoryItem.HistoryItemType.SYSTEM_MESSAGE, message, null, false, Instant.now()));
    }

    public void addError(String error) {
        history.add(new HistoryItem(
                HistoryItem.HistoryItemType.ERROR, error, null, false, Instant.now()));
    }

    // ========== Scrolling ==========

    public void scrollUp(int lines) {
        if (historyScrollOffset == Integer.MAX_VALUE) {
            historyScrollOffset = Math.max(0, totalRenderLines - visibleLines);
        }
        historyScrollOffset = Math.max(0, historyScrollOffset - lines);
    }

    public void scrollDown(int lines) {
        if (historyScrollOffset == Integer.MAX_VALUE) return;
        int maxOffset = Math.max(0, totalRenderLines - visibleLines);
        historyScrollOffset = Math.min(maxOffset, historyScrollOffset + lines);
        if (historyScrollOffset >= maxOffset) {
            historyScrollOffset = Integer.MAX_VALUE;
        }
    }

    // ========== Dialog ==========

    public void showHelpDialog() {
        dialogMode = DialogMode.HELP;
    }

    public void hideDialog() {
        dialogMode = DialogMode.NONE;
        confirmDialogData = null;
    }

    // ========== Confirmation flow ==========

    private void setupConfirmationHandler() {
        agentRunner.setConfirmationHandler(new AgentLoopController.ConfirmationHandler() {
            @Override
            public boolean confirm(String toolName, JsonNode params) {
                return showConfirmAndWait(toolName, params);
            }
        });
    }

    private boolean showConfirmAndWait(String toolName, JsonNode params) {
        confirmResult = null;
        sessionState = SessionState.CONFIRMING;
        confirmDialogData = new ConfirmDialogData(
                "Confirm Operation", toolName, formatToolParams(params));
        // 不再使用弹窗模式，改用内联确认

        synchronized (confirmLock) {
            while (confirmResult == null && !quitting) {
                try {
                    confirmLock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        sessionState = SessionState.EXECUTING;
        confirmDialogData = null;
        return confirmResult != null && confirmResult;
    }

    // ========== UIActions implementation ==========

    @Override
    public void submitInput(String input) {
        if (input == null || input.trim().isEmpty()) return;

        if (input.startsWith("/")) {
            // 如果只输入了 "/"，不做任何处理（用户可能在查看命令列表）
            if (input.trim().equals("/")) {
                return;
            }
            if (commandRegistry.parseAndExecute(input)) return;
            addError("Unknown command: " + input + ". Type /help for available commands.");
            return;
        }

        if (taskRunning.getAndSet(true)) {
            addSystemMessage("Please wait for the current task to complete.");
            taskRunning.set(false);
            return;
        }

        sessionState = SessionState.THINKING;
        currentIteration = 0;
        statusMessage = "Processing...";
        String sid = sessionId;

        currentTask = executorService.submit(() -> {
            try {
                AgentResult result = sid != null
                        ? agentRunner.run(input, sid)
                        : agentRunner.run(input);
                if (result.getSessionId() != null) {
                    sessionId = result.getSessionId();
                }
            } catch (Exception e) {
                log.error("Error executing agent task", e);
                addError("Task error: " + e.getMessage());
                sessionState = SessionState.IDLE;
            } finally {
                taskRunning.set(false);
            }
        });
    }

    @Override
    public void cancelTask() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
            sessionState = SessionState.IDLE;
            addSystemMessage("Task cancellation requested.");
            taskRunning.set(false);
        }
    }

    @Override
    public void confirmAction(boolean confirmed) {
        synchronized (confirmLock) {
            confirmResult = confirmed;
            confirmLock.notifyAll();
        }
    }

    @Override
    public void showHelp() {
        showHelpDialog();
    }

    @Override
    public void hideHelp() {
        hideDialog();
    }

    @Override
    public void clearHistory() {
        history.clear();
        historyScrollOffset = Integer.MAX_VALUE;
        addSystemMessage("History cleared.");
    }

    @Override
    public void quit() {
        quitting = true;
        if (quitCallback != null) {
            quitCallback.run();
        }
    }

    @Override
    public void scrollUp() {
        int halfPage = Math.max(1, visibleLines / 2);
        scrollUp(halfPage);
    }

    @Override
    public void scrollDown() {
        int halfPage = Math.max(1, visibleLines / 2);
        scrollDown(halfPage);
    }

    @Override
    public void scrollToTop() {
        historyScrollOffset = 0;
    }

    @Override
    public void scrollToBottom() {
        historyScrollOffset = Integer.MAX_VALUE;
    }

    @Override
    public void toggleFocus() {
    }

    @Override
    public void refresh() {
    }

    // ========== 会话管理实现 ==========

    @Override
    public void listSessions() {
        List<SessionMetadata> sessions = agentRunner.listSessions();
        if (sessions.isEmpty()) {
            addSystemMessage("No sessions found for this project.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Sessions (").append(sessions.size()).append("):\n");
        for (int i = 0; i < Math.min(sessions.size(), 10); i++) {
            SessionMetadata m = sessions.get(i);
            String marker = m.getSessionId().equals(sessionId) ? " * " : "   ";
            sb.append(marker)
              .append(m.getShortId())
              .append("  ")
              .append(m.getRelativeTime())
              .append("  ")
              .append(m.getTitlePreview(40))
              .append("\n");
        }
        if (sessions.size() > 10) {
            sb.append("   ... and ").append(sessions.size() - 10).append(" more\n");
        }
        sb.append("\nUse /resume <id-prefix> to resume a session.");
        addSystemMessage(sb.toString());
    }

    @Override
    public void resumeSession(String sessionIdOrPrefix) {
        if (sessionIdOrPrefix == null || sessionIdOrPrefix.isEmpty()) {
            List<SessionMetadata> sessions = agentRunner.listSessions();
            if (sessions.isEmpty()) {
                addSystemMessage("No sessions to resume.");
                return;
            }
            SessionMetadata latest = sessions.get(0);
            sessionIdOrPrefix = latest.getSessionId();
            addSystemMessage("Resuming latest session: " + latest.getShortId());
        }

        List<SessionMetadata> matches = agentRunner.findSessionsByPrefix(sessionIdOrPrefix);
        if (matches.isEmpty()) {
            addSystemMessage("No session found matching: " + sessionIdOrPrefix);
            return;
        }
        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Multiple sessions match '").append(sessionIdOrPrefix).append("':\n");
            for (SessionMetadata m : matches) {
                sb.append("  ").append(m.getShortId()).append("  ").append(m.getTitlePreview(40)).append("\n");
            }
            sb.append("Please provide a more specific prefix.");
            addSystemMessage(sb.toString());
            return;
        }

        SessionMetadata target = matches.get(0);
        sessionId = target.getSessionId();
        history.clear();
        historyScrollOffset = Integer.MAX_VALUE;
        
        // 从 SessionStore 加载历史消息
        loadSessionHistory(sessionId);
        
        addSystemMessage("Resumed session: " + target.getShortId() + " (" + target.getTitlePreview(50) + ")");
    }

    @Override
    public void newSession() {
        sessionId = null;
        history.clear();
        historyScrollOffset = Integer.MAX_VALUE;
        addSystemMessage("Started new session. Enter your message to begin.");
    }

    @Override
    public void deleteSession(String sessionIdOrPrefix) {
        if (sessionIdOrPrefix == null || sessionIdOrPrefix.isEmpty()) {
            addSystemMessage("Usage: /delete <session-id-prefix>");
            return;
        }

        List<SessionMetadata> matches = agentRunner.findSessionsByPrefix(sessionIdOrPrefix);
        if (matches.isEmpty()) {
            addSystemMessage("No session found matching: " + sessionIdOrPrefix);
            return;
        }
        if (matches.size() > 1) {
            addSystemMessage("Multiple sessions match. Please provide a more specific prefix.");
            return;
        }

        SessionMetadata target = matches.get(0);
        if (target.getSessionId().equals(sessionId)) {
            addSystemMessage("Cannot delete the current session. Use /new first.");
            return;
        }

        if (agentRunner.deleteSession(target.getSessionId())) {
            addSystemMessage("Deleted session: " + target.getShortId());
        } else {
            addSystemMessage("Failed to delete session: " + target.getShortId());
        }
    }

    @Override
    public void compactSession(String focusHint) {
        if (sessionId == null) {
            addSystemMessage("No active session. Start a conversation first.");
            return;
        }

        addSystemMessage("Compacting session" + (focusHint != null ? " (focus: " + focusHint + ")" : "") + "...");

        CompactionResult result = agentRunner.compact(sessionId, focusHint);
        if (result.isSuccess()) {
            if (result.getCompactedMessageCount() > 0) {
                addSystemMessage(result.format());
            } else {
                addSystemMessage("No compaction needed: " + 
                    (result.getErrorMessage() != null ? result.getErrorMessage() : "context usage is low"));
            }
        } else {
            addSystemMessage("Compaction failed: " + result.getErrorMessage());
        }
    }

    @Override
    public void showContextStats() {
        if (sessionId == null) {
            addSystemMessage("No active session.");
            return;
        }

        ContextStats stats = agentRunner.getContextStats(sessionId);
        addSystemMessage("Context Statistics:\n" + stats.format());
    }

    @Override
    public void exportSession(String sessionIdOrPrefix) {
        String targetSessionId = sessionId;

        if (sessionIdOrPrefix != null && !sessionIdOrPrefix.isEmpty()) {
            List<SessionMetadata> matches = agentRunner.findSessionsByPrefix(sessionIdOrPrefix);
            if (matches.isEmpty()) {
                addSystemMessage("No session found matching: " + sessionIdOrPrefix);
                return;
            }
            if (matches.size() > 1) {
                addSystemMessage("Multiple sessions match. Please provide a more specific prefix.");
                return;
            }
            targetSessionId = matches.get(0).getSessionId();
        }

        if (targetSessionId == null) {
            addSystemMessage("No session to export. Start a conversation first.");
            return;
        }

        String markdown = agentRunner.exportSession(targetSessionId);
        if (markdown == null || markdown.isEmpty()) {
            addSystemMessage("Failed to export session.");
            return;
        }

        String shortId = targetSessionId.length() > 8 ? targetSessionId.substring(0, 8) : targetSessionId;
        String filename = "session-" + shortId + ".md";
        Path exportPath = agentRunner.getConfig().getWorkingDirectory().resolve(filename);

        try {
            Files.writeString(exportPath, markdown);
            addSystemMessage("Session exported to: " + exportPath);
        } catch (IOException e) {
            addSystemMessage("Failed to write export file: " + e.getMessage());
        }
    }

    /**
     * 设置初始会话 ID（用于 --resume 参数）
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 从 SessionStore 加载历史消息到 UI
     * 用于恢复会话时显示之前的对话记录
     */
    public void loadSessionHistory(String sessionId) {
        SessionStore store = agentRunner.getSessionStore();
        if (store == null) {
            log.warn("SessionStore not available, cannot load history");
            return;
        }

        List<SessionMessage> messages = store.loadSession(sessionId);
        if (messages.isEmpty()) {
            log.info("No messages found for session: {}", sessionId);
            return;
        }

        for (SessionMessage msg : messages) {
            if (msg.getMessage() == null) continue;
            
            SessionMessage.MessageContent content = msg.getMessage();
            Instant timestamp = msg.getTimestamp() != null ? msg.getTimestamp() : Instant.now();
            
            switch (msg.getType()) {
                case USER:
                    if (content.getContent() != null) {
                        history.add(new HistoryItem(
                                HistoryItem.HistoryItemType.USER_MESSAGE,
                                content.getContent(), null, false, timestamp));
                    }
                    break;
                case ASSISTANT:
                    if (content.getContent() != null && !content.getContent().isEmpty()) {
                        history.add(new HistoryItem(
                                HistoryItem.HistoryItemType.ASSISTANT_MESSAGE,
                                content.getContent(), null, false, timestamp));
                    }
                    break;
                case TOOL_CALL:
                    String toolName = content.getToolName() != null ? content.getToolName() : "unknown_tool";
                    String toolInput = content.getToolInput() != null 
                            ? content.getToolInput().toPrettyString() : "";
                    history.add(new HistoryItem(
                            HistoryItem.HistoryItemType.TOOL_CALL,
                            toolInput, toolName, content.getToolCallId(), false, timestamp));
                    break;
                case TOOL_RESULT:
                    String resultToolName = content.getToolName() != null ? content.getToolName() : "unknown_tool";
                    String resultContent = content.getContent();
                    if (resultContent == null && content.getPreview() != null) {
                        resultContent = content.getPreview() + " (offloaded)";
                    }
                    boolean isError = content.getIsError() != null && content.getIsError();
                    history.add(new HistoryItem(
                            HistoryItem.HistoryItemType.TOOL_RESULT,
                            resultContent != null ? resultContent : "",
                            resultToolName, content.getToolCallId(), !isError, timestamp));
                    break;
                case SUMMARY:
                    if (content.getCompaction() != null && content.getCompaction().getSummary() != null) {
                        history.add(new HistoryItem(
                                HistoryItem.HistoryItemType.SYSTEM_MESSAGE,
                                "[Context compacted] " + content.getCompaction().getSummary(),
                                null, false, timestamp));
                    }
                    break;
                case SYSTEM:
                case RESULT:
                    break;
            }
        }
        
        log.info("Loaded {} history items for session: {}", history.size(), sessionId);
    }

    // ========== Utilities ==========

    private String formatToolParams(JsonNode params) {
        if (params == null) return "";
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(params);
        } catch (Exception e) {
            return params.toString();
        }
    }

    public void close() {
        // 强制关闭线程池，中断正在执行的任务
        if (executorService != null) {
            executorService.shutdownNow();
            try {
                // 等待线程池在 5 秒内终止
                if (!executorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("ExecutorService did not terminate within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for ExecutorService to terminate");
            }
        }

        // 关闭 agentRunner
        try {
            agentRunner.close();
        } catch (Exception e) {
            log.debug("Error closing agentRunner (ignored)", e);
        }

        log.info("AppController closed");
    }
}
