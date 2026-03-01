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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Grep 工具 - 用于在文件中查找模式的强大搜索工具。
 * 
 * 基于 coderfaster-agent grep 实现
 * 
 * 使用方法：
 * - 始终使用 Grep 进行搜索任务。永远不要将 `grep` 或 `rg` 作为 Bash 命令调用。
 * - 支持完整的正则表达式语法（例如，"log.*Error", "function\\s+\\w+"）
 * - 使用 glob 参数过滤文件（例如，"*.js", "**\/*.tsx"）
 * - 默认不区分大小写
 * - 对于需要多轮搜索的开放式搜索，使用 Task 工具
 */
public class GrepTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(GrepTool.class);
    
    /** 默认返回的最大匹配行数 */
    private static final int DEFAULT_MAX_LINES = 500;
    
    /** 输出的最大字符数限制 */
    private static final int MAX_OUTPUT_CHARS = 100000;
    
    /** 默认忽略的目录 */
    private static final Set<String> IGNORED_DIRECTORIES = new HashSet<>(Arrays.asList(
        "node_modules", ".git", ".svn", ".hg", "target", "build", "dist",
        "__pycache__", ".idea", ".vscode", "vendor", ".gradle", "out"
    ));
    
    /** 要跳过的二进制文件扩展名 */
    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(Arrays.asList(
        ".exe", ".dll", ".so", ".dylib", ".bin", ".obj", ".o",
        ".class", ".jar", ".war", ".ear",
        ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar",
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg",
        ".mp3", ".mp4", ".avi", ".mov", ".wmv", ".flv",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
    ));
    
    private static final String DESCRIPTION = 
        "A powerful search tool for finding patterns in files\n\n" +
        "Usage:\n" +
        "- ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. " +
        "The Grep tool has been optimized for correct permissions and access.\n" +
        "- Supports full regex syntax (e.g., \"log.*Error\", \"function\\\\s+\\\\w+\")\n" +
        "- Filter files with glob parameter (e.g., \"*.js\", \"**/*.tsx\")\n" +
        "- Case-insensitive by default\n" +
        "- Use Task tool for open-ended searches requiring multiple rounds";
    
    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("pattern", "string",
                "The regular expression pattern to search for in file contents")
            .addProperty("glob", "string",
                "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\")")
            .addProperty("path", "string",
                "File or directory to search in. Defaults to current working directory.")
            .addProperty("limit", "number",
                "Limit output to first N matching lines. Optional - shows all matches if not specified.")
            .setRequired(Arrays.asList("pattern"))
            .build();
        
        return new ToolSchema(
            ToolNames.GREP,
            DESCRIPTION,
            ToolKind.SEARCH,
            parameters,
            "1.0.0"
        );
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String patternStr = params.path("pattern").asText("");
        String glob = params.has("glob") && !params.path("glob").isNull() 
            ? params.path("glob").asText("") : null;
        String searchPath = params.has("path") && !params.path("path").isNull() 
            ? params.path("path").asText("") : null;
        Integer limit = params.has("limit") && !params.path("limit").isNull() 
            ? params.path("limit").asInt() : null;
        
        logger.debug("Grep: pattern={}, glob={}, path={}, limit={}", patternStr, glob, searchPath, limit);
        
        // 验证模式
        if (patternStr == null || patternStr.isEmpty()) {
            return ToolResult.failure("The 'pattern' parameter is required.");
        }
        
        // 编译正则表达式模式（默认不区分大小写）
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure(
                "Invalid regular expression pattern: " + patternStr + ". Error: " + e.getMessage()
            );
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
            return ToolResult.failure("Path not found: " + searchDir);
        }
        
        String searchLocationDescription = searchPath != null && !searchPath.isEmpty()
            ? "in path \"" + searchPath + "\""
            : "in the workspace directory";
        
        String filterDescription = glob != null && !glob.isEmpty()
            ? " (filter: \"" + glob + "\")"
            : "";
        
        try {
            // 执行搜索
            List<GrepMatch> matches = performSearch(searchDir, pattern, glob, limit);
            
            if (matches.isEmpty()) {
                return ToolResult.success(
                    "No matches found for pattern \"" + patternStr + "\" " + 
                    searchLocationDescription + filterDescription + "."
                );
            }
            
            // 应用行数限制
            int effectiveLimit = limit != null ? limit : DEFAULT_MAX_LINES;
            boolean truncatedByLineLimit = matches.size() > effectiveLimit;
            
            List<GrepMatch> matchesToInclude = truncatedByLineLimit 
                ? matches.subList(0, effectiveLimit) 
                : matches;
            
            int totalMatches = matches.size();
            String matchTerm = totalMatches == 1 ? "match" : "matches";
            
            // 构建头部
            String header = "Found " + totalMatches + " " + matchTerm + " for pattern \"" + 
                patternStr + "\" " + searchLocationDescription + filterDescription + ":\n---\n";
            
            // 按文件分组匹配结果
            Map<String, List<GrepMatch>> matchesByFile = new LinkedHashMap<>();
            for (GrepMatch match : matchesToInclude) {
                matchesByFile.computeIfAbsent(match.filePath, k -> new ArrayList<>()).add(match);
            }
            
            // 构建 grep 输出
            StringBuilder grepOutput = new StringBuilder();
            for (Map.Entry<String, List<GrepMatch>> entry : matchesByFile.entrySet()) {
                grepOutput.append("File: ").append(entry.getKey()).append("\n");
                
                // 按行号排序
                List<GrepMatch> fileMatches = entry.getValue();
                fileMatches.sort(Comparator.comparingInt(m -> m.lineNumber));
                
                for (GrepMatch match : fileMatches) {
                    String trimmedLine = match.line.trim();
                    grepOutput.append("L").append(match.lineNumber).append(": ").append(trimmedLine).append("\n");
                }
                grepOutput.append("---\n");
            }
            
            // 应用字符限制作为安全网
            String outputStr = grepOutput.toString();
            boolean truncatedByCharLimit = outputStr.length() > MAX_OUTPUT_CHARS;
            if (truncatedByCharLimit) {
                outputStr = outputStr.substring(0, MAX_OUTPUT_CHARS) + "...";
            }
            
            // 构建结果
            StringBuilder result = new StringBuilder(header);
            result.append(outputStr);
            
            // 添加截断通知
            if (truncatedByLineLimit || truncatedByCharLimit) {
                int includedLines = matchesToInclude.size();
                int omittedMatches = totalMatches - includedLines;
                String lineTerm = omittedMatches == 1 ? "line" : "lines";
                result.append(" [").append(omittedMatches).append(" ").append(lineTerm).append(" truncated] ...");
            }
            
            // 构建展示消息
            String displayMessage = "Found " + totalMatches + " " + matchTerm;
            if (truncatedByLineLimit || truncatedByCharLimit) {
                displayMessage += " (truncated)";
            }
            
            return ToolResult.builder()
                .success(true)
                .content(result.toString().trim())
                .displayData(displayMessage)
                .build();
            
        } catch (Exception e) {
            logger.error("Error during grep search: {}", e.getMessage(), e);
            return ToolResult.failure("Error during grep search operation: " + e.getMessage());
        }
    }
    
    /**
     * 执行实际的 grep 搜索。
     */
    private List<GrepMatch> performSearch(Path searchDir, Pattern pattern, String glob, Integer limit) throws IOException {
        List<GrepMatch> results = new ArrayList<>();
        
        // 如果指定了 glob，创建 glob 匹配器
        PathMatcher globMatcher = null;
        if (glob != null && !glob.isEmpty()) {
            try {
                String globPattern = "glob:" + (glob.contains("/") ? glob : "**/" + glob);
                globMatcher = FileSystems.getDefault().getPathMatcher(globPattern);
            } catch (Exception e) {
                logger.warn("Invalid glob pattern, searching all files: {}", glob);
            }
        }
        
        final PathMatcher finalGlobMatcher = globMatcher;
        int effectiveLimit = limit != null ? limit : DEFAULT_MAX_LINES;
        
        // 搜索单个文件
        if (Files.isRegularFile(searchDir)) {
            searchFile(searchDir, searchDir.getParent(), pattern, results, effectiveLimit);
            return results;
        }
        
        // 遍历目录树
        Files.walkFileTree(searchDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (results.size() >= effectiveLimit * 2) { // 如果已有足够结果则提前停止
                    return FileVisitResult.TERMINATE;
                }
                
                String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                if (IGNORED_DIRECTORIES.contains(dirName)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (results.size() >= effectiveLimit * 2) {
                    return FileVisitResult.TERMINATE;
                }
                
                // 跳过二进制文件
                String fileName = file.getFileName().toString();
                String extension = getFileExtension(fileName);
                if (BINARY_EXTENSIONS.contains(extension.toLowerCase())) {
                    return FileVisitResult.CONTINUE;
                }
                
                // 检查 glob 过滤器
                if (finalGlobMatcher != null) {
                    Path relativePath = searchDir.relativize(file);
                    if (!finalGlobMatcher.matches(relativePath) && !finalGlobMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                }
                
                try {
                    searchFile(file, searchDir, pattern, results, effectiveLimit);
                } catch (IOException e) {
                    logger.debug("Could not read file: {}", file);
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
     * 在单个文件中搜索匹配的行。
     */
    private void searchFile(Path file, Path baseDir, Pattern pattern, List<GrepMatch> results, int limit) throws IOException {
        // 跳过大文件
        if (Files.size(file) > 5 * 1024 * 1024) { // 5MB
            return;
        }
        
        String relativePath = baseDir != null 
            ? baseDir.relativize(file).toString() 
            : file.getFileName().toString();
        
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            
            while ((line = reader.readLine()) != null && results.size() < limit * 2) {
                lineNumber++;
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    results.add(new GrepMatch(relativePath, lineNumber, line));
                }
            }
        } catch (Exception e) {
            // 可能是编码问题，跳过文件
            logger.debug("Could not read/process file {}: {}", file, e.getMessage());
        }
    }
    
    /**
     * 获取包含点号的文件扩展名。
     */
    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot);
    }
    
    /**
     * 用于保存 grep 匹配结果的辅助类。
     */
    private static class GrepMatch {
        final String filePath;
        final int lineNumber;
        final String line;
        
        GrepMatch(String filePath, int lineNumber, String line) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.line = line;
        }
    }
    
    @Override
    public String validateParams(JsonNode params) {
        String pattern = params.path("pattern").asText("");
        if (pattern.isEmpty()) {
            return "The 'pattern' parameter is required.";
        }
        
        // 验证正则表达式可以编译
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Invalid regular expression pattern: " + pattern + ". Error: " + e.getMessage();
        }
        
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 搜索操作不需要确认
    }
}
