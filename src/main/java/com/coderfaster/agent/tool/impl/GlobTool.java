package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.PathResolver;
import com.coderfaster.agent.tool.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Glob 工具 - 适用于任意大小代码库的快速文件模式匹配工具。
 *
 * 基于 coderfaster-agent glob 实现
 *
 * - 支持 glob 模式，如 "**\/*.js" 或 "src/**\/*.ts"
 * - 返回按修改时间排序的匹配文件路径
 * - 当需要按名称模式查找文件时使用此工具
 */
public class GlobTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(GlobTool.class);

    /** 返回的最大文件数量 */
    private static final int MAX_FILE_COUNT = 100;

    /** 最近时间阈值（毫秒）（24 小时） */
    private static final long RECENCY_THRESHOLD_MS = 24 * 60 * 60 * 1000;

    /** 默认忽略的目录 */
    private static final Set<String> IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList(
        "node_modules", ".git", ".svn", ".hg", "target", "build", "dist",
        "__pycache__", ".idea", ".vscode", "vendor", ".gradle", "out"
    ));

    private static final String DESCRIPTION =
        "Fast file pattern matching tool that works with any codebase size\n" +
        "- Supports glob patterns for finding files by name or path patterns\n" +
        "- Returns matching file paths sorted by modification time\n" +
        "- Use this tool when you need to find files by name patterns\n" +
        "\n" +
        "Common usage patterns by language:\n" +
        "- Java: \"**/*Service.java\" (find all Service classes), \"**/UserService.java\" (find specific class)\n" +
        "- JavaScript/TypeScript: \"**/*.js\", \"src/**/*.ts\"\n" +
        "- Python: \"**/*_test.py\", \"**/models/*.py\"\n" +
        "- Go: \"**/*_handler.go\", \"**/main.go\"\n" +
        "\n" +
        "Tips:\n" +
        "- For Java projects, use class name patterns like \"**/ClassName.java\" to quickly locate specific classes\n" +
        "- Use \"**/\" prefix to search in any subdirectory\n" +
        "- When you are doing an open ended search that may require multiple rounds of globbing and grepping, use the Agent tool instead\n" +
        "- You have the capability to call multiple tools in a single response. It is always better to speculatively perform multiple searches as a batch that are potentially useful.";

    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("pattern", "string",
                "The glob pattern to match files against")
            .addProperty("path", "string",
                "The directory to search in. If not specified, the current working directory will be used. " +
                "IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - " +
                "simply omit it for the default behavior. Must be a valid directory path if provided.")
            .setRequired(Arrays.asList("pattern"))
            .build();

        return new ToolSchema(
            ToolNames.GLOB,
            DESCRIPTION,
            ToolKind.SEARCH,
            parameters,
            "1.0.0"
        );
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String pattern = params.path("pattern").asText("");
        String searchPath = params.has("path") && !params.path("path").isNull()
            && !params.path("path").asText("").isEmpty()
            ? params.path("path").asText("") : null;

        logger.debug("Glob: pattern={}, path={}", pattern, searchPath);

        // 验证模式
        if (pattern == null || pattern.trim().isEmpty()) {
            return ToolResult.failure("The 'pattern' parameter cannot be empty.");
        }

        // 确定搜索目录
        Path searchDir;
        if (searchPath != null && !searchPath.isEmpty()) {
            searchDir = PathResolver.resolve(searchPath, context);
        } else {
            searchDir = PathResolver.getWorkingDirectory(context);
        }

        // 验证搜索目录存在
        if (!Files.exists(searchDir)) {
            return ToolResult.failure("Directory not found: " + searchDir);
        }
        if (!Files.isDirectory(searchDir)) {
            return ToolResult.failure("Path is not a directory: " + searchDir);
        }

        String searchLocationDescription = searchPath != null
            ? "within " + searchDir.toAbsolutePath()
            : "in the workspace directory";

        try {
            // 查找匹配的文件
            List<FileEntry> matchingFiles = findMatchingFiles(searchDir, pattern);

            if (matchingFiles.isEmpty()) {
                return ToolResult.success(
                    "No files found matching pattern \"" + pattern + "\" " + searchLocationDescription
                );
            }

            // 排序文件：最近的文件优先，然后按字母顺序
            long now = System.currentTimeMillis();
            sortFileEntries(matchingFiles, now, RECENCY_THRESHOLD_MS);

            int totalFileCount = matchingFiles.size();
            boolean truncated = totalFileCount > MAX_FILE_COUNT;

            // 限制结果数量
            List<FileEntry> entriesToShow = truncated
                ? matchingFiles.subList(0, MAX_FILE_COUNT)
                : matchingFiles;

            // 构建结果消息
            String fileList = entriesToShow.stream()
                .map(entry -> entry.path.toAbsolutePath().toString())
                .collect(Collectors.joining("\n"));

            StringBuilder resultMessage = new StringBuilder();
            resultMessage.append("Found ")
                .append(totalFileCount)
                .append(" file(s) matching \"")
                .append(pattern)
                .append("\" ")
                .append(searchLocationDescription)
                .append(", sorted by modification time (newest first):\n---\n")
                .append(fileList);

            if (truncated) {
                int omittedFiles = totalFileCount - MAX_FILE_COUNT;
                String fileTerm = omittedFiles == 1 ? "file" : "files";
                resultMessage.append("\n---\n[")
                    .append(omittedFiles)
                    .append(" ")
                    .append(fileTerm)
                    .append(" truncated] ...");
            }

            String displayMessage = "Found " + totalFileCount + " matching file(s)" +
                (truncated ? " (truncated)" : "");

            return ToolResult.builder()
                .success(true)
                .content(resultMessage.toString())
                .displayData(displayMessage)
                .build();

        } catch (Exception e) {
            logger.error("Error during glob search: {}", e.getMessage(), e);
            return ToolResult.failure("Error during glob search operation: " + e.getMessage());
        }
    }

    /**
     * 查找匹配 glob 模式的文件。
     */
    private List<FileEntry> findMatchingFiles(Path searchDir, String pattern) throws IOException {
        List<FileEntry> results = new ArrayList<>();

        // 规范化模式
        String normalizedPattern = pattern;
        if (!normalizedPattern.startsWith("**/") && !normalizedPattern.startsWith("/")) {
            // 如果模式不以 **/ 开头，允许在任意位置匹配
            if (!normalizedPattern.contains("/")) {
                normalizedPattern = "**/" + normalizedPattern;
            }
        }

        // 创建 glob 模式匹配器
        final String globPattern = "glob:" + normalizedPattern;
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher(globPattern);
        } catch (Exception e) {
            logger.warn("Invalid glob pattern, trying as literal: {}", pattern);
            // 尝试作为字面文件名
            final String literalPattern = pattern;
            matcher = path -> path.getFileName().toString().equals(literalPattern);
        }

        final PathMatcher finalMatcher = matcher;

        Files.walkFileTree(searchDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (IGNORED_DIRECTORIES.contains(dirName)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relativePath = searchDir.relativize(file);
                if (finalMatcher.matches(relativePath) || finalMatcher.matches(file.getFileName())) {
                    results.add(new FileEntry(file, attrs.lastModifiedTime().toMillis()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                logger.debug("Could not access file: {}", file);
                return FileVisitResult.CONTINUE;
            }
        });

        return results;
    }

    /**
     * 按最近程度（最近优先）然后按字母顺序对文件条目进行排序。
     */
    private void sortFileEntries(List<FileEntry> entries, long nowTimestamp, long recencyThresholdMs) {
        entries.sort((a, b) -> {
            boolean aIsRecent = (nowTimestamp - a.modifiedTime) < recencyThresholdMs;
            boolean bIsRecent = (nowTimestamp - b.modifiedTime) < recencyThresholdMs;

            if (aIsRecent && bIsRecent) {
                // 都是最近的：按修改时间排序（最新优先）
                return Long.compare(b.modifiedTime, a.modifiedTime);
            } else if (aIsRecent) {
                return -1;
            } else if (bIsRecent) {
                return 1;
            } else {
                // 都是旧的：按字母顺序排序
                return a.path.toString().compareTo(b.path.toString());
            }
        });
    }

    /**
     * 用于保存文件路径和修改时间的辅助类。
     */
    private static class FileEntry {
        final Path path;
        final long modifiedTime;

        FileEntry(Path path, long modifiedTime) {
            this.path = path;
            this.modifiedTime = modifiedTime;
        }
    }

    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("pattern") || params.path("pattern").asText("").isEmpty()) {
            return "The 'pattern' parameter cannot be empty.";
        }
        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 搜索操作不需要确认
    }
}
