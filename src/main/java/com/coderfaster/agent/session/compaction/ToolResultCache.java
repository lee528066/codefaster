package com.coderfaster.agent.session.compaction;

import com.coderfaster.agent.session.SessionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具结果缓存
 * 实现微压缩策略：大型工具输出落盘，保持 Hot Tail 可见
 *
 * 核心思想：
 * - Hot Tail: 最近 N 个工具结果保持完整可见（默认 5 个）
 * - Cold Storage: 旧的/大的结果落盘，仅保留预览和路径引用
 */
public class ToolResultCache {

    private static final Logger log = LoggerFactory.getLogger(ToolResultCache.class);

    /**
     * 预览长度（字符数）
     */
    private static final int PREVIEW_LENGTH = 200;

    private final Path projectPath;
    private final SessionConfig config;

    /**
     * Hot Tail: sessionId -> LinkedList<ToolResultEntry>
     * 使用 LinkedList 便于从头部移除最旧的条目
     */
    private final Map<String, LinkedList<ToolResultEntry>> hotTails = new ConcurrentHashMap<>();

    /**
     * 工具结果条目
     */
    public static class ToolResultEntry {
        private final String toolResultId;
        private final String toolName;
        private final String content;
        private final int contentLength;
        private final long timestamp;

        public ToolResultEntry(String toolResultId, String toolName, String content) {
            this.toolResultId = toolResultId;
            this.toolName = toolName;
            this.content = content;
            this.contentLength = content != null ? content.length() : 0;
            this.timestamp = System.currentTimeMillis();
        }

        public String getToolResultId() {
            return toolResultId;
        }

        public String getToolName() {
            return toolName;
        }

        public String getContent() {
            return content;
        }

        public int getContentLength() {
            return contentLength;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * 微压缩结果
     */
    public static class MicrocompactResult {
        private final boolean offloaded;
        private final String storagePath;
        private final String preview;
        private final String content;

        private MicrocompactResult(boolean offloaded, String storagePath, String preview, String content) {
            this.offloaded = offloaded;
            this.storagePath = storagePath;
            this.preview = preview;
            this.content = content;
        }

        public static MicrocompactResult inline(String content) {
            return new MicrocompactResult(false, null, null, content);
        }

        public static MicrocompactResult offloaded(String storagePath, String preview) {
            return new MicrocompactResult(true, storagePath, preview, null);
        }

        public boolean isOffloaded() {
            return offloaded;
        }

        public String getStoragePath() {
            return storagePath;
        }

        public String getPreview() {
            return preview;
        }

        public String getContent() {
            return content;
        }
    }

    public ToolResultCache(Path projectPath, SessionConfig config) {
        this.projectPath = projectPath;
        this.config = config;
    }

    /**
     * 处理工具结果，决定是否需要微压缩
     *
     * @param sessionId 会话 ID
     * @param toolResultId 工具结果 ID
     * @param toolName 工具名称
     * @param content 工具结果内容
     * @return 微压缩结果
     */
    public MicrocompactResult process(String sessionId, String toolResultId, String toolName, String content) {
        if (content == null) {
            return MicrocompactResult.inline("");
        }

        LinkedList<ToolResultEntry> hotTail = hotTails.computeIfAbsent(sessionId, k -> new LinkedList<>());

        boolean shouldOffload = shouldOffload(content, toolName);

        if (shouldOffload) {
            String storagePath = offloadToDisk(sessionId, toolResultId, content);
            String preview = generatePreview(content);
            log.debug("Offloaded tool result {} ({} chars) to {}", toolResultId, content.length(), storagePath);
            return MicrocompactResult.offloaded(storagePath, preview);
        }

        ToolResultEntry entry = new ToolResultEntry(toolResultId, toolName, content);
        hotTail.addLast(entry);

        while (hotTail.size() > config.getMicrocompactHotTailSize()) {
            ToolResultEntry oldest = hotTail.removeFirst();
            offloadEntry(sessionId, oldest);
        }

        return MicrocompactResult.inline(content);
    }

    /**
     * 判断是否应该落盘
     */
    private boolean shouldOffload(String content, String toolName) {
        if (content.length() > config.getMicrocompactMaxResultSize()) {
            return true;
        }

        if (isLargeOutputTool(toolName) && content.length() > config.getMicrocompactMaxResultSize() / 2) {
            return true;
        }

        return false;
    }

    /**
     * 判断是否是通常产生大输出的工具
     */
    private boolean isLargeOutputTool(String toolName) {
        return toolName != null && (
                toolName.equals("Read") ||
                toolName.equals("Bash") ||
                toolName.equals("Grep") ||
                toolName.equals("Glob") ||
                toolName.equals("WebSearch") ||
                toolName.equals("WebFetch")
        );
    }

    /**
     * 将内容落盘
     */
    private String offloadToDisk(String sessionId, String toolResultId, String content) {
        Path cacheDir = SessionConfig.getToolResultsCacheDir(projectPath).resolve(sessionId);
        String filename = toolResultId + ".txt";
        Path storagePath = cacheDir.resolve(filename);

        try {
            Files.createDirectories(cacheDir);
            Files.writeString(storagePath, content, StandardCharsets.UTF_8);
            return storagePath.toString();
        } catch (IOException e) {
            log.error("Failed to offload tool result to disk: {}", storagePath, e);
            throw new RuntimeException("Microcompaction failed", e);
        }
    }

    /**
     * 将 Hot Tail 中的条目落盘
     */
    private void offloadEntry(String sessionId, ToolResultEntry entry) {
        try {
            offloadToDisk(sessionId, entry.getToolResultId(), entry.getContent());
            log.debug("Evicted tool result {} from hot tail", entry.getToolResultId());
        } catch (Exception e) {
            log.warn("Failed to offload evicted entry: {}", entry.getToolResultId(), e);
        }
    }

    /**
     * 生成预览
     */
    private String generatePreview(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String cleaned = content.replaceAll("\\s+", " ").trim();

        if (cleaned.length() <= PREVIEW_LENGTH) {
            return cleaned;
        }

        return cleaned.substring(0, PREVIEW_LENGTH) + "...";
    }

    /**
     * 从磁盘加载已落盘的内容
     */
    public Optional<String> loadOffloaded(String storagePath) {
        if (storagePath == null || storagePath.isEmpty()) {
            return Optional.empty();
        }

        try {
            Path path = Path.of(storagePath);
            if (Files.exists(path)) {
                return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.error("Failed to load offloaded content: {}", storagePath, e);
        }

        return Optional.empty();
    }

    /**
     * 获取 Hot Tail 中的内容（如果存在）
     */
    public Optional<String> getFromHotTail(String sessionId, String toolResultId) {
        LinkedList<ToolResultEntry> hotTail = hotTails.get(sessionId);
        if (hotTail == null) {
            return Optional.empty();
        }

        return hotTail.stream()
                .filter(e -> e.getToolResultId().equals(toolResultId))
                .findFirst()
                .map(ToolResultEntry::getContent);
    }

    /**
     * 获取内容（优先从 Hot Tail，然后从磁盘）
     */
    public Optional<String> getContent(String sessionId, String toolResultId, String storagePath) {
        Optional<String> fromHotTail = getFromHotTail(sessionId, toolResultId);
        if (fromHotTail.isPresent()) {
            return fromHotTail;
        }

        return loadOffloaded(storagePath);
    }

    /**
     * 获取 Hot Tail 统计信息
     */
    public Map<String, Object> getStats(String sessionId) {
        LinkedList<ToolResultEntry> hotTail = hotTails.get(sessionId);
        Map<String, Object> stats = new HashMap<>();

        if (hotTail == null || hotTail.isEmpty()) {
            stats.put("hotTailSize", 0);
            stats.put("hotTailBytes", 0);
            return stats;
        }

        int totalBytes = hotTail.stream().mapToInt(ToolResultEntry::getContentLength).sum();

        stats.put("hotTailSize", hotTail.size());
        stats.put("hotTailBytes", totalBytes);
        stats.put("maxHotTailSize", config.getMicrocompactHotTailSize());
        stats.put("entries", hotTail.stream()
                .map(e -> Map.of(
                        "id", e.getToolResultId(),
                        "tool", e.getToolName(),
                        "bytes", e.getContentLength()
                ))
                .collect(Collectors.toList()));

        return stats;
    }

    /**
     * 清理会话的 Hot Tail
     */
    public void clearSession(String sessionId) {
        hotTails.remove(sessionId);
    }

    /**
     * 清理会话的磁盘缓存
     */
    public void clearSessionDiskCache(String sessionId) {
        Path cacheDir = SessionConfig.getToolResultsCacheDir(projectPath).resolve(sessionId);

        if (Files.exists(cacheDir)) {
            try {
                Files.walk(cacheDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                log.warn("Failed to delete: {}", path, e);
                            }
                        });
                log.info("Cleared disk cache for session: {}", sessionId);
            } catch (IOException e) {
                log.error("Failed to clear disk cache: {}", cacheDir, e);
            }
        }
    }

    /**
     * 关闭缓存，清理资源
     */
    public void close() {
        hotTails.clear();
    }
}
