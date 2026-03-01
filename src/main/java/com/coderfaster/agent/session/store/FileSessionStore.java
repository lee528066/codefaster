package com.coderfaster.agent.session.store;

import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.session.SessionConfig;
import com.coderfaster.agent.session.SessionMessage;
import com.coderfaster.agent.session.SessionMetadata;
import com.coderfaster.agent.session.compaction.CompactionResult;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 基于文件的会话存储实现
 * 使用 JSONL 格式存储会话数据
 */
public class FileSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(FileSessionStore.class);

    private final SessionConfig config;
    private final ObjectMapper objectMapper;
    private final Path projectPath;

    private final Map<String, List<ToolSchema>> sessionTools = new ConcurrentHashMap<>();
    private final Map<String, SessionMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, List<SessionMessage>> messageCache = new ConcurrentHashMap<>();

    public FileSessionStore(Path projectPath) {
        this(projectPath, SessionConfig.defaults());
    }

    public FileSessionStore(Path projectPath, SessionConfig config) {
        this.projectPath = projectPath.toAbsolutePath().normalize();
        this.config = config;
        this.objectMapper = createObjectMapper();
        ensureDirectoriesExist();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return mapper;
    }

    private void ensureDirectoriesExist() {
        try {
            Files.createDirectories(SessionConfig.getProjectSessionsDir(projectPath));
            Files.createDirectories(SessionConfig.getToolResultsCacheDir(projectPath));
        } catch (IOException e) {
            log.error("Failed to create session directories", e);
        }
    }

    // ========== 会话生命周期 ==========

    @Override
    public String createSession(Path projectPath) {
        String sessionId = UUID.randomUUID().toString();
        Path sessionFile = SessionConfig.getSessionFilePath(this.projectPath, sessionId);

        try {
            Files.createDirectories(sessionFile.getParent());

            SessionMessage systemMsg = SessionMessage.system(sessionId, this.projectPath.toString());
            appendMessageToFile(sessionFile, systemMsg);

            SessionMetadata metadata = SessionMetadata.create(sessionId, this.projectPath.toString());
            metadataCache.put(sessionId, metadata);
            messageCache.put(sessionId, new ArrayList<>(List.of(systemMsg)));

            log.info("Created new session: {}", sessionId);
            return sessionId;
        } catch (IOException e) {
            log.error("Failed to create session file: {}", sessionFile, e);
            throw new RuntimeException("Failed to create session", e);
        }
    }

    @Override
    public void appendMessage(String sessionId, SessionMessage message) {
        Path sessionFile = SessionConfig.getSessionFilePath(projectPath, sessionId);

        if (!Files.exists(sessionFile)) {
            throw new IllegalStateException("Session not found: " + sessionId);
        }

        try {
            appendMessageToFile(sessionFile, message);

            List<SessionMessage> messages = messageCache.computeIfAbsent(sessionId,
                    k -> loadMessagesFromFile(sessionFile));
            messages.add(message);

            updateMetadataOnMessage(sessionId, message);

            log.debug("Appended message to session {}: type={}", sessionId, message.getType());
        } catch (IOException e) {
            log.error("Failed to append message to session: {}", sessionId, e);
            throw new RuntimeException("Failed to append message", e);
        }
    }

    private void appendMessageToFile(Path sessionFile, SessionMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message);
        try (BufferedWriter writer = Files.newBufferedWriter(sessionFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(json);
            writer.newLine();
        }
    }

    private void updateMetadataOnMessage(String sessionId, SessionMessage message) {
        SessionMetadata metadata = metadataCache.get(sessionId);
        if (metadata == null) {
            return;
        }

        metadata.setLastActivityAt(Instant.now());
        metadata.setMessageCount(metadata.getMessageCount() + 1);

        if (message.getType() == SessionMessage.MessageType.USER && metadata.getTitle() == null) {
            String content = message.getMessage() != null ? message.getMessage().getContent() : null;
            if (content != null && !content.isEmpty()) {
                metadata.setTitle(content.length() > 100 ? content.substring(0, 100) : content);
            }
        }

        if (message.getMessage() != null && message.getMessage().getUsage() != null) {
            var usage = message.getMessage().getUsage();
            if (usage.getTotalTokens() != null) {
                metadata.setEstimatedTokens(metadata.getEstimatedTokens() + usage.getTotalTokens());
            }
        }
    }

    @Override
    public List<SessionMessage> loadSession(String sessionId) {
        List<SessionMessage> cached = messageCache.get(sessionId);
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        Path sessionFile = SessionConfig.getSessionFilePath(projectPath, sessionId);
        if (!Files.exists(sessionFile)) {
            return Collections.emptyList();
        }

        List<SessionMessage> messages = loadMessagesFromFile(sessionFile);
        messageCache.put(sessionId, messages);
        return new ArrayList<>(messages);
    }

    private List<SessionMessage> loadMessagesFromFile(Path sessionFile) {
        List<SessionMessage> messages = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    SessionMessage message = objectMapper.readValue(line, SessionMessage.class);
                    messages.add(message);
                } catch (JsonProcessingException e) {
                    log.warn("Failed to parse message at line {}: {}", lineNumber, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to load session file: {}", sessionFile, e);
        }

        return messages;
    }

    @Override
    public boolean deleteSession(String sessionId) {
        Path sessionFile = SessionConfig.getSessionFilePath(projectPath, sessionId);

        try {
            if (Files.deleteIfExists(sessionFile)) {
                messageCache.remove(sessionId);
                metadataCache.remove(sessionId);
                sessionTools.remove(sessionId);
                log.info("Deleted session: {}", sessionId);
                return true;
            }
            return false;
        } catch (IOException e) {
            log.error("Failed to delete session: {}", sessionId, e);
            return false;
        }
    }

    @Override
    public boolean sessionExists(String sessionId) {
        Path sessionFile = SessionConfig.getSessionFilePath(projectPath, sessionId);
        return Files.exists(sessionFile);
    }

    // ========== 会话查询 ==========

    @Override
    public List<SessionMetadata> listSessions(Path projectPath) {
        Path sessionsDir = SessionConfig.getProjectSessionsDir(this.projectPath);

        if (!Files.exists(sessionsDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> stream = Files.list(sessionsDir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .map(this::extractMetadataFromFile)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SessionMetadata::getLastActivityAt).reversed())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to list sessions", e);
            return Collections.emptyList();
        }
    }

    private SessionMetadata extractMetadataFromFile(Path sessionFile) {
        String filename = sessionFile.getFileName().toString();
        String sessionId = filename.replace(".jsonl", "");

        SessionMetadata cached = metadataCache.get(sessionId);
        if (cached != null) {
            return cached;
        }

        try {
            long fileSize = Files.size(sessionFile);
            Instant lastModified = Files.getLastModifiedTime(sessionFile).toInstant();

            List<SessionMessage> messages = loadMessagesFromFile(sessionFile);
            if (messages.isEmpty()) {
                return null;
            }

            String title = null;
            Instant createdAt = null;
            int tokenCount = 0;
            boolean compacted = false;
            int compactionCount = 0;

            for (SessionMessage msg : messages) {
                if (msg.getType() == SessionMessage.MessageType.SYSTEM && createdAt == null) {
                    createdAt = msg.getTimestamp();
                }
                if (msg.getType() == SessionMessage.MessageType.USER && title == null) {
                    String content = msg.getMessage() != null ? msg.getMessage().getContent() : null;
                    if (content != null && !content.isEmpty()) {
                        title = content.length() > 100 ? content.substring(0, 100) : content;
                    }
                }
                if (msg.getType() == SessionMessage.MessageType.SUMMARY) {
                    compacted = true;
                    compactionCount++;
                }
                if (msg.getMessage() != null && msg.getMessage().getUsage() != null) {
                    var usage = msg.getMessage().getUsage();
                    if (usage.getTotalTokens() != null) {
                        tokenCount += usage.getTotalTokens();
                    }
                }
            }

            SessionMetadata metadata = SessionMetadata.builder()
                    .sessionId(sessionId)
                    .title(title)
                    .projectPath(projectPath.toString())
                    .createdAt(createdAt != null ? createdAt : lastModified)
                    .lastActivityAt(lastModified)
                    .messageCount(messages.size())
                    .estimatedTokens(tokenCount)
                    .compacted(compacted)
                    .compactionCount(compactionCount)
                    .status(SessionMetadata.SessionStatus.ACTIVE)
                    .fileSizeBytes(fileSize)
                    .build();

            metadataCache.put(sessionId, metadata);
            messageCache.put(sessionId, messages);

            return metadata;
        } catch (IOException e) {
            log.error("Failed to extract metadata from: {}", sessionFile, e);
            return null;
        }
    }

    @Override
    public Optional<SessionMetadata> getSessionMetadata(String sessionId) {
        SessionMetadata cached = metadataCache.get(sessionId);
        if (cached != null) {
            return Optional.of(cached);
        }

        Path sessionFile = SessionConfig.getSessionFilePath(projectPath, sessionId);
        if (!Files.exists(sessionFile)) {
            return Optional.empty();
        }

        return Optional.ofNullable(extractMetadataFromFile(sessionFile));
    }

    @Override
    public List<SessionMetadata> findSessionsByPrefix(Path projectPath, String idPrefix) {
        return listSessions(projectPath).stream()
                .filter(m -> m.getSessionId().startsWith(idPrefix))
                .collect(Collectors.toList());
    }

    // ========== LLM 消息转换 ==========

    @Override
    public List<Message> getLlmMessages(String sessionId) {
        List<SessionMessage> sessionMessages = loadSession(sessionId);
        List<Message> llmMessages = new ArrayList<>();

        for (SessionMessage sm : sessionMessages) {
            Message llmMsg = convertToLlmMessage(sm);
            if (llmMsg != null) {
                llmMessages.add(llmMsg);
            }
        }

        return llmMessages;
    }

    private Message convertToLlmMessage(SessionMessage sm) {
        if (sm.getMessage() == null) {
            return null;
        }

        SessionMessage.MessageContent content = sm.getMessage();
        Message message = new Message();

        switch (sm.getType()) {
            case USER:
                message.setRole(Role.USER.getValue());
                message.setContent(content.getContent());
                break;

            case ASSISTANT:
                message.setRole(Role.ASSISTANT.getValue());
                message.setContent(content.getContent());
                break;

            case TOOL_RESULT:
                message.setRole(Role.TOOL.getValue());
                if ("offloaded".equals(content.getStorageStatus())) {
                    Optional<String> offloadedContent = loadOffloadedResult(content.getStoragePath());
                    message.setContent(offloadedContent.orElse(content.getPreview()));
                } else {
                    message.setContent(content.getContent());
                }
                message.setToolCallId(content.getToolCallId());
                break;

            case SUMMARY:
                message.setRole(Role.USER.getValue());
                if (content.getCompaction() != null && content.getCompaction().getSummary() != null) {
                    message.setContent(buildContinuationMessage(content.getCompaction().getSummary()));
                }
                break;

            case SYSTEM:
            case TOOL_CALL:
            case RESULT:
            default:
                return null;
        }

        return message;
    }

    private String buildContinuationMessage(String summary) {
        return "This session is being continued from a previous conversation that ran out " +
               "of context. The summary below covers the earlier portion of the conversation.\n\n" +
               summary + "\n\n" +
               "Please continue the conversation from where we left it off without asking " +
               "the user any further questions. Continue with the last task that you were " +
               "asked to work on.";
    }

    @Override
    public List<ToolSchema> getSessionTools(String sessionId) {
        return sessionTools.getOrDefault(sessionId, Collections.emptyList());
    }

    @Override
    public void setSessionTools(String sessionId, List<ToolSchema> tools) {
        sessionTools.put(sessionId, new ArrayList<>(tools));
    }

    // ========== 会话维护 ==========

    @Override
    public int cleanupExpiredSessions(Path projectPath, int retentionDays) {
        if (retentionDays < 0) {
            return 0;
        }

        List<SessionMetadata> sessions = listSessions(projectPath);
        int cleaned = 0;

        for (SessionMetadata metadata : sessions) {
            if (metadata.isExpired(retentionDays)) {
                if (deleteSession(metadata.getSessionId())) {
                    cleaned++;
                    log.info("Cleaned up expired session: {} (last activity: {})",
                            metadata.getSessionId(), metadata.getRelativeTime());
                }
            }
        }

        if (cleaned > 0) {
            log.info("Cleaned up {} expired sessions (retention: {} days)", cleaned, retentionDays);
        }

        return cleaned;
    }

    @Override
    public void updateMetadata(String sessionId, SessionMetadata metadata) {
        metadataCache.put(sessionId, metadata);
    }

    // ========== 压缩相关 ==========

    @Override
    public String microcompact(String sessionId, String toolResultId, String content) {
        Path cacheDir = SessionConfig.getToolResultsCacheDir(projectPath);
        String filename = toolResultId + ".json";
        Path storagePath = cacheDir.resolve(filename);

        try {
            Files.createDirectories(cacheDir);
            Files.writeString(storagePath, content, StandardCharsets.UTF_8);
            log.debug("Microcompacted tool result {} to {}", toolResultId, storagePath);
            return storagePath.toString();
        } catch (IOException e) {
            log.error("Failed to microcompact tool result: {}", toolResultId, e);
            throw new RuntimeException("Microcompaction failed", e);
        }
    }

    @Override
    public Optional<String> loadOffloadedResult(String storagePath) {
        if (storagePath == null || storagePath.isEmpty()) {
            return Optional.empty();
        }

        try {
            Path path = Path.of(storagePath);
            if (Files.exists(path)) {
                return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.error("Failed to load offloaded result: {}", storagePath, e);
        }

        return Optional.empty();
    }

    @Override
    public CompactionResult autocompact(String sessionId, String focusHint) {
        ContextStats stats = getContextStats(sessionId);

        if (!stats.needsCompaction(
                config.getAutocompactThresholdPercent(),
                config.getAutocompactOutputHeadroom(),
                config.getAutocompactCompactionHeadroom())) {
            return CompactionResult.skipped("Context usage below threshold");
        }

        List<SessionMessage> messages = loadSession(sessionId);
        if (messages.size() < 10) {
            return CompactionResult.skipped("Too few messages to compact");
        }

        int keepRecent = Math.min(20, messages.size() / 3);
        List<SessionMessage> toCompact = messages.subList(0, messages.size() - keepRecent);
        List<SessionMessage> toKeep = messages.subList(messages.size() - keepRecent, messages.size());

        String summary = generateSummary(toCompact, focusHint);

        SessionMessage.CompactionInfo compactionInfo = SessionMessage.CompactionInfo.builder()
                .compactedCount(toCompact.size())
                .compactedTokens(stats.getEstimatedInputTokens())
                .summary(summary)
                .build();

        SessionMessage summaryMsg = SessionMessage.summary(sessionId, compactionInfo,
                toCompact.get(toCompact.size() - 1).getUuid());

        rewriteSessionFile(sessionId, summaryMsg, toKeep);

        List<String> rehydratedFiles = rehydrateRecentFiles(messages);

        return CompactionResult.success(
                toCompact.size(),
                stats.getEstimatedInputTokens(),
                estimateTokens(List.of(summaryMsg)) + estimateTokens(toKeep),
                summaryMsg,
                toKeep,
                rehydratedFiles,
                focusHint != null ? CompactionResult.CompactionType.MANUAL : CompactionResult.CompactionType.AUTO
        );
    }

    private String generateSummary(List<SessionMessage> messages, String focusHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Session Summary\n\n");

        if (focusHint != null) {
            sb.append("**Focus**: ").append(focusHint).append("\n\n");
        }

        sb.append("### Conversation Overview\n");
        int userMsgCount = 0;
        int toolCallCount = 0;
        Set<String> toolsUsed = new HashSet<>();

        for (SessionMessage msg : messages) {
            if (msg.getType() == SessionMessage.MessageType.USER) {
                userMsgCount++;
                if (userMsgCount <= 3 && msg.getMessage() != null) {
                    sb.append("- User: ").append(truncate(msg.getMessage().getContent(), 100)).append("\n");
                }
            }
            if (msg.getType() == SessionMessage.MessageType.TOOL_CALL) {
                toolCallCount++;
                if (msg.getMessage() != null && msg.getMessage().getToolName() != null) {
                    toolsUsed.add(msg.getMessage().getToolName());
                }
            }
        }

        sb.append("\n### Statistics\n");
        sb.append("- Total messages: ").append(messages.size()).append("\n");
        sb.append("- User messages: ").append(userMsgCount).append("\n");
        sb.append("- Tool calls: ").append(toolCallCount).append("\n");
        sb.append("- Tools used: ").append(String.join(", ", toolsUsed)).append("\n");

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private void rewriteSessionFile(String sessionId, SessionMessage summaryMsg, List<SessionMessage> toKeep) {
        Path sessionFile = SessionConfig.getSessionFilePath(projectPath, sessionId);
        Path tempFile = sessionFile.resolveSibling(sessionId + ".jsonl.tmp");

        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            SessionMessage systemMsg = SessionMessage.system(sessionId, projectPath.toString());
            writer.write(objectMapper.writeValueAsString(systemMsg));
            writer.newLine();

            writer.write(objectMapper.writeValueAsString(summaryMsg));
            writer.newLine();

            for (SessionMessage msg : toKeep) {
                writer.write(objectMapper.writeValueAsString(msg));
                writer.newLine();
            }

            Files.move(tempFile, sessionFile, StandardCopyOption.REPLACE_EXISTING);

            List<SessionMessage> newMessages = new ArrayList<>();
            newMessages.add(systemMsg);
            newMessages.add(summaryMsg);
            newMessages.addAll(toKeep);
            messageCache.put(sessionId, newMessages);

            log.info("Rewrote session file after compaction: {}", sessionId);
        } catch (IOException e) {
            log.error("Failed to rewrite session file: {}", sessionId, e);
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}
            throw new RuntimeException("Compaction failed", e);
        }
    }

    private List<String> rehydrateRecentFiles(List<SessionMessage> messages) {
        Set<String> recentFiles = new LinkedHashSet<>();

        for (int i = messages.size() - 1; i >= 0 && recentFiles.size() < config.getAutocompactRehydrateFileCount(); i--) {
            SessionMessage msg = messages.get(i);
            if (msg.getType() == SessionMessage.MessageType.TOOL_CALL && msg.getMessage() != null) {
                String toolName = msg.getMessage().getToolName();
                if ("Read".equals(toolName) || "Write".equals(toolName) || "Edit".equals(toolName)) {
                    var input = msg.getMessage().getToolInput();
                    if (input != null && input.has("path")) {
                        recentFiles.add(input.get("path").asText());
                    }
                }
            }
        }

        return new ArrayList<>(recentFiles);
    }

    @Override
    public ContextStats getContextStats(String sessionId) {
        List<SessionMessage> messages = loadSession(sessionId);

        int userCount = 0, assistantCount = 0, toolCallCount = 0, toolResultCount = 0, offloadedCount = 0;
        int compactionCount = 0, messagesSinceCompaction = 0;
        boolean foundCompaction = false;

        for (SessionMessage msg : messages) {
            switch (msg.getType()) {
                case USER:
                    userCount++;
                    break;
                case ASSISTANT:
                    assistantCount++;
                    break;
                case TOOL_CALL:
                    toolCallCount++;
                    break;
                case TOOL_RESULT:
                    toolResultCount++;
                    if (msg.getMessage() != null && "offloaded".equals(msg.getMessage().getStorageStatus())) {
                        offloadedCount++;
                    }
                    break;
                case SUMMARY:
                    compactionCount++;
                    foundCompaction = true;
                    messagesSinceCompaction = 0;
                    break;
                default:
                    break;
            }
            if (foundCompaction) {
                messagesSinceCompaction++;
            }
        }

        int estimatedTokens = estimateTokens(messages);

        return ContextStats.builder()
                .messageCount(messages.size())
                .userMessageCount(userCount)
                .assistantMessageCount(assistantCount)
                .toolCallCount(toolCallCount)
                .toolResultCount(toolResultCount)
                .offloadedToolResultCount(offloadedCount)
                .estimatedInputTokens(estimatedTokens)
                .maxContextTokens(config.getMaxContextTokens())
                .compactionCount(compactionCount)
                .messagesSinceLastCompaction(foundCompaction ? messagesSinceCompaction : messages.size())
                .build();
    }

    private int estimateTokens(List<SessionMessage> messages) {
        int tokens = 0;
        for (SessionMessage msg : messages) {
            if (msg.getMessage() == null) continue;

            String content = msg.getMessage().getContent();
            if (content != null) {
                tokens += content.length() / 4;
            }

            var usage = msg.getMessage().getUsage();
            if (usage != null && usage.getTotalTokens() != null) {
                tokens = Math.max(tokens, usage.getTotalTokens());
            }
        }
        return tokens;
    }

    // ========== 会话状态 ==========

    @Override
    public void markCompleted(String sessionId, String summary) {
        SessionMessage resultMsg = SessionMessage.result(sessionId, summary, true, null);
        appendMessage(sessionId, resultMsg);

        SessionMetadata metadata = metadataCache.get(sessionId);
        if (metadata != null) {
            metadata.setStatus(SessionMetadata.SessionStatus.COMPLETED);
        }
    }

    @Override
    public void markError(String sessionId, String error) {
        SessionMessage resultMsg = SessionMessage.result(sessionId, error, false, null);
        appendMessage(sessionId, resultMsg);

        SessionMetadata metadata = metadataCache.get(sessionId);
        if (metadata != null) {
            metadata.setStatus(SessionMetadata.SessionStatus.ERROR);
        }
    }

    // ========== 导出 ==========

    @Override
    public String exportToMarkdown(String sessionId) {
        List<SessionMessage> messages = loadSession(sessionId);
        Optional<SessionMetadata> metadata = getSessionMetadata(sessionId);

        StringBuilder sb = new StringBuilder();
        sb.append("# Session Export\n\n");

        if (metadata.isPresent()) {
            SessionMetadata m = metadata.get();
            sb.append("- **Session ID**: ").append(m.getSessionId()).append("\n");
            sb.append("- **Title**: ").append(m.getTitle() != null ? m.getTitle() : "(No title)").append("\n");
            sb.append("- **Created**: ").append(m.getCreatedAt()).append("\n");
            sb.append("- **Last Activity**: ").append(m.getLastActivityAt()).append("\n");
            sb.append("- **Messages**: ").append(m.getMessageCount()).append("\n");
            sb.append("\n---\n\n");
        }

        for (SessionMessage msg : messages) {
            switch (msg.getType()) {
                case USER:
                    sb.append("## User\n\n");
                    sb.append(msg.getMessage().getContent()).append("\n\n");
                    break;
                case ASSISTANT:
                    sb.append("## Assistant\n\n");
                    sb.append(msg.getMessage().getContent()).append("\n\n");
                    break;
                case TOOL_CALL:
                    sb.append("### Tool Call: ").append(msg.getMessage().getToolName()).append("\n\n");
                    sb.append("```json\n").append(msg.getMessage().getToolInput()).append("\n```\n\n");
                    break;
                case TOOL_RESULT:
                    sb.append("### Tool Result\n\n");
                    String content = msg.getMessage().getContent();
                    if (content != null && content.length() > 500) {
                        content = content.substring(0, 500) + "...(truncated)";
                    }
                    sb.append("```\n").append(content).append("\n```\n\n");
                    break;
                case SUMMARY:
                    sb.append("## [Compaction Summary]\n\n");
                    if (msg.getMessage().getCompaction() != null) {
                        sb.append(msg.getMessage().getCompaction().getSummary()).append("\n\n");
                    }
                    break;
                default:
                    break;
            }
        }

        return sb.toString();
    }

    // ========== 资源管理 ==========

    @Override
    public void close() {
        messageCache.clear();
        metadataCache.clear();
        sessionTools.clear();
        log.info("FileSessionStore closed");
    }
}
