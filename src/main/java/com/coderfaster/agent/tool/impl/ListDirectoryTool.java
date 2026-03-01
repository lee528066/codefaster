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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ListDirectory 工具 (ls) - 列出指定目录路径下直接包含的文件和子目录名称。
 * 
 * 基于 coderfaster-agent ls 实现
 * 
 * 可以选择性地忽略匹配指定 glob 模式的条目。
 */
public class ListDirectoryTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(ListDirectoryTool.class);
    
    /** 默认忽略的目录（类似 gitignore） */
    private static final Set<String> DEFAULT_IGNORED_DIRS = new HashSet<>(Arrays.asList(
        "node_modules", ".git", ".svn", ".hg", "__pycache__", ".idea", 
        ".vscode", "target", "build", "dist", "vendor", ".gradle", "out"
    ));
    
    private static final String DESCRIPTION = 
        "Lists the names of files and subdirectories directly within a specified directory path. " +
        "Can optionally ignore entries matching provided glob patterns.";
    
    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("path", "string",
                "The absolute path to the directory to list (must be absolute, not relative)")
            .addArrayProperty("ignore", "string",
                "List of glob patterns to ignore")
            .addObjectProperty("file_filtering_options",
                "Optional: Whether to respect ignore patterns from .gitignore or .coderfasterignore",
                new JsonSchemaBuilder()
                    .addProperty("respect_git_ignore", "boolean",
                        "Optional: Whether to respect .gitignore patterns when listing files. " +
                        "Only available in git repositories. Defaults to true.")
                    .addProperty("respect_coderfaster_ignore", "boolean",
                        "Optional: Whether to respect .coderfasterignore patterns when listing files. Defaults to true.")
                    .build())
            .setRequired(Arrays.asList("path"))
            .build();
        
        return new ToolSchema(
            ToolNames.LS,
            DESCRIPTION,
            ToolKind.SEARCH,
            parameters,
            "1.0.0"
        );
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String pathStr = params.path("path").asText("");
        
        logger.debug("ListDirectory: path={}", pathStr);
        
        if (pathStr.isEmpty()) {
            return ToolResult.failure("The 'path' parameter must be non-empty.");
        }
        
        // 解析路径（支持相对路径）
        Path dirPath = PathResolver.resolve(pathStr, context);
        
        // 检查路径是否存在
        if (!Files.exists(dirPath)) {
            return ToolResult.failure("Directory not found or inaccessible: " + dirPath);
        }
        
        // 检查是否是目录
        if (!Files.isDirectory(dirPath)) {
            return ToolResult.failure("Path is not a directory: " + dirPath);
        }
        
        // 解析忽略模式
        List<String> ignorePatterns = new ArrayList<>();
        if (params.has("ignore") && params.path("ignore").isArray()) {
            for (JsonNode pattern : params.path("ignore")) {
                ignorePatterns.add(pattern.asText(""));
            }
        }
        
        // 解析过滤选项
        boolean respectGitIgnore = true;
        boolean respectCoderfasterIgnore = true;
        if (params.has("file_filtering_options")) {
            JsonNode options = params.path("file_filtering_options");
            if (options.has("respect_git_ignore")) {
                respectGitIgnore = options.path("respect_git_ignore").asBoolean(true);
            }
            if (options.has("respect_coderfaster_ignore")) {
                respectCoderfasterIgnore = options.path("respect_coderfaster_ignore").asBoolean(true);
            }
        }
        
        try {
            // 列出目录内容
            List<FileEntry> entries = new ArrayList<>();
            int gitIgnoredCount = 0;
            int patternIgnoredCount = 0;
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                for (Path entry : stream) {
                    String fileName = entry.getFileName().toString();
                    
                    // 检查自定义忽略模式
                    if (shouldIgnore(fileName, ignorePatterns)) {
                        patternIgnoredCount++;
                        continue;
                    }
                    
                    // 检查默认忽略的目录
                    if (Files.isDirectory(entry) && DEFAULT_IGNORED_DIRS.contains(fileName)) {
                        if (respectGitIgnore) {
                            gitIgnoredCount++;
                            continue;
                        }
                    }
                    
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                        entries.add(new FileEntry(
                            fileName,
                            entry.toAbsolutePath().toString(),
                            attrs.isDirectory(),
                            attrs.isDirectory() ? 0 : attrs.size(),
                            attrs.lastModifiedTime().toMillis()
                        ));
                    } catch (IOException e) {
                        logger.debug("Could not read attributes for: {}", entry);
                    }
                }
            }
            
            // 处理空目录
            if (entries.isEmpty()) {
                return ToolResult.success("Directory " + pathStr + " is empty.");
            }
            
            // 排序条目：目录优先，然后按字母顺序
            entries.sort((a, b) -> {
                if (a.isDirectory && !b.isDirectory) return -1;
                if (!a.isDirectory && b.isDirectory) return 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            
            // 构建输出
            String directoryContent = entries.stream()
                .map(entry -> (entry.isDirectory ? "[DIR] " : "") + entry.name)
                .collect(Collectors.joining("\n"));
            
            StringBuilder resultMessage = new StringBuilder();
            resultMessage.append("Directory listing for ").append(pathStr).append(":\n");
            resultMessage.append(directoryContent);
            
            // 添加忽略计数
            List<String> ignoredMessages = new ArrayList<>();
            if (gitIgnoredCount > 0) {
                ignoredMessages.add(gitIgnoredCount + " git-ignored");
            }
            if (patternIgnoredCount > 0) {
                ignoredMessages.add(patternIgnoredCount + " pattern-ignored");
            }
            if (!ignoredMessages.isEmpty()) {
                resultMessage.append("\n\n(").append(String.join(", ", ignoredMessages)).append(")");
            }
            
            // 构建显示消息
            String displayMessage = "Listed " + entries.size() + " item(s).";
            if (!ignoredMessages.isEmpty()) {
                displayMessage += " (" + String.join(", ", ignoredMessages) + ")";
            }
            
            return ToolResult.builder()
                .success(true)
                .content(resultMessage.toString())
                .displayData(displayMessage)
                .build();
            
        } catch (IOException e) {
            logger.error("Error listing directory: {}", pathStr, e);
            return ToolResult.failure("Error listing directory: " + e.getMessage());
        }
    }
    
    /**
     * 检查文件名是否匹配任何忽略模式。
     */
    private boolean shouldIgnore(String filename, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                continue;
            }
            
            // 将 glob 模式转换为正则表达式
            String regexPattern = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
            
            try {
                if (filename.matches("^" + regexPattern + "$")) {
                    return true;
                }
            } catch (Exception e) {
                // 无效模式，跳过
                logger.debug("Invalid ignore pattern: {}", pattern);
            }
        }
        
        return false;
    }
    
    /**
     * 文件条目的辅助类。
     */
    private static class FileEntry {
        final String name;
        final String path;
        final boolean isDirectory;
        final long size;
        final long modifiedTime;
        
        FileEntry(String name, String path, boolean isDirectory, long size, long modifiedTime) {
            this.name = name;
            this.path = path;
            this.isDirectory = isDirectory;
            this.size = size;
            this.modifiedTime = modifiedTime;
        }
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("path") || params.path("path").asText("").isEmpty()) {
            return "The 'path' parameter is required and must be non-empty.";
        }
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 列表操作不需要确认
    }
}
