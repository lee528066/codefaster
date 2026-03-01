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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edit 工具 - 替换文件中的文本。
 * 
 * 基于 coderfaster-agent edit 实现
 * 
 * 默认情况下替换单个匹配项。当你想要修改 `old_string` 的所有实例时，
 * 将 `replace_all` 设置为 true。此工具需要提供足够的上下文以确保精确定位。
 */
public class EditTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(EditTool.class);
    
    private static final String DESCRIPTION = 
        "Replaces text within a file. By default, replaces a single occurrence. " +
        "Set `replace_all` to true when you intend to modify every instance of `old_string`. " +
        "This tool requires providing significant context around the change to ensure precise targeting. " +
        "Always use the read_file tool to examine the file's current content before attempting a text replacement.\n\n" +
        "The user has the ability to modify the `new_string` content. If modified, this will be stated in the response.\n\n" +
        "Expectation for required parameters:\n" +
        "1. `file_path` MUST be an absolute path; otherwise an error will be thrown.\n" +
        "2. `old_string` MUST be the exact literal text to replace (including all whitespace, indentation, newlines, and surrounding code etc.).\n" +
        "3. `new_string` MUST be the exact literal text to replace `old_string` with (also including all whitespace, indentation, newlines, and surrounding code etc.). Ensure the resulting code is correct and idiomatic.\n" +
        "4. NEVER escape `old_string` or `new_string`, that would break the exact literal text requirement.\n\n" +
        "**Important:** If ANY of the above are not satisfied, the tool will fail. " +
        "CRITICAL for `old_string`: Must uniquely identify the single instance to change. " +
        "Include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace and indentation precisely. " +
        "If this string matches multiple locations, or does not match exactly, the tool will fail.\n\n" +
        "**Multiple replacements:** Set `replace_all` to true when you want to replace every occurrence that matches `old_string`.";
    
    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("file_path", "string",
                "The absolute path to the file to modify. Must start with '/'.")
            .addProperty("old_string", "string",
                "The exact literal text to replace, preferably unescaped. For single replacements (default), " +
                "include at least 3 lines of context BEFORE and AFTER the target text, matching whitespace " +
                "and indentation precisely. If this string is not the exact literal text (i.e. you escaped it) " +
                "or does not match exactly, the tool will fail.")
            .addProperty("new_string", "string",
                "The exact literal text to replace `old_string` with, preferably unescaped. " +
                "Provide the EXACT text. Ensure the resulting code is correct and idiomatic.")
            .addProperty("replace_all", "boolean",
                "Replace all occurrences of old_string (default false).")
            .setRequired(Arrays.asList("file_path", "old_string", "new_string"))
            .build();
        
        return new ToolSchema(
            ToolNames.EDIT,
            DESCRIPTION,
            ToolKind.EDIT,
            parameters,
            "1.0.0"
        );
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String filePathParam = params.path("file_path").asText("");
        String oldString = params.path("old_string").asText("");
        String newString = params.path("new_string").asText("");
        boolean replaceAll = params.has("replace_all") && params.path("replace_all").asBoolean(false);
        
        logger.debug("Edit: path={}, oldString.length={}, newString.length={}, replaceAll={}", 
            filePathParam, oldString.length(), newString.length(), replaceAll);
        
        if (filePathParam.isEmpty()) {
            return ToolResult.failure("The 'file_path' parameter must be non-empty.");
        }
        
        // 解析路径（支持相对路径）
        Path path = PathResolver.resolve(filePathParam, context);
        String filePath = path.toAbsolutePath().toString();
        
        // 验证工作空间边界
        if (!PathResolver.isWithinWorkspace(path, context)) {
            return ToolResult.failure(
                "File path must be within workspace directory: " + 
                PathResolver.getWorkingDirectory(context)
            );
        }
        
        // 标准化行尾
        oldString = normalizeLineEndings(oldString);
        newString = normalizeLineEndings(newString);
        
        try {
            // 检查是否正在创建新文件（old_string 为空）
            boolean isNewFile = oldString.isEmpty();
            
            if (isNewFile) {
                // 创建新文件
                if (Files.exists(path)) {
                    return ToolResult.failure(
                        "Failed to edit. Attempted to create a file that already exists: " + filePath
                    );
                }
                
                // 如果需要，创建父目录
                Path parent = path.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                }
                
                // 写入新文件
                Files.write(path, newString.getBytes(StandardCharsets.UTF_8));
                
                return ToolResult.success(
                    "Created new file: " + filePath + " with provided content."
                );
            }
            
            // 编辑现有文件
            if (!Files.exists(path)) {
                return ToolResult.failure(
                    "File not found. Cannot apply edit. Use an empty old_string to create a new file. " +
                    "File path: " + filePath
                );
            }
            
            // 读取当前内容
            String currentContent = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            currentContent = normalizeLineEndings(currentContent);
            
            // 处理删除增强（如果 new_string 为空且 old_string 以换行符结尾）
            oldString = maybeAugmentOldStringForDeletion(currentContent, oldString, newString);
            
            // 计算出现次数
            int occurrences = countOccurrences(currentContent, oldString);
            
            if (occurrences == 0) {
                return ToolResult.failure(
                    "Failed to edit, could not find the string to replace. " +
                    "0 occurrences found for old_string in " + filePath + ". No edits made. " +
                    "The exact text in old_string was not found. " +
                    "Ensure you're not escaping content incorrectly and check whitespace, indentation, and context. " +
                    "Use read_file tool to verify."
                );
            }
            
            if (!replaceAll && occurrences > 1) {
                return ToolResult.failure(
                    "Failed to edit because the text matches multiple locations (" + occurrences + " occurrences). " +
                    "Provide more context or set replace_all to true."
                );
            }
            
            if (oldString.equals(newString)) {
                return ToolResult.failure(
                    "No changes to apply. The old_string and new_string are identical."
                );
            }
            
            // 应用替换
            String newContent;
            if (replaceAll) {
                newContent = currentContent.replace(oldString, newString);
            } else {
                // 仅替换第一个匹配项（安全处理替换中的 $ 符号）
                newContent = safeLiteralReplace(currentContent, oldString, newString);
            }
            
            if (currentContent.equals(newContent)) {
                return ToolResult.failure(
                    "No changes to apply. The new content is identical to the current content."
                );
            }
            
            // 写入修改后的内容
            Files.write(path, newContent.getBytes(StandardCharsets.UTF_8));
            
            // 生成结果消息
            String successMessage = "The file: " + filePath + " has been updated.";
            
            // 提取编辑片段以提供上下文
            String snippet = extractEditSnippet(currentContent, newContent);
            if (snippet != null && !snippet.isEmpty()) {
                successMessage += " " + snippet;
            }
            
            return ToolResult.success(successMessage);
            
        } catch (IOException e) {
            logger.error("Error editing file: {}", filePath, e);
            return ToolResult.failure("Error executing edit: " + e.getMessage());
        }
    }
    
    /**
     * 将行尾标准化为 LF。
     */
    private String normalizeLineEndings(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }
    
    /**
     * 计算子字符串的出现次数。
     */
    private int countOccurrences(String text, String search) {
        if (search == null || search.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
    
    /**
     * 安全的字面量替换，可处理 $ 序列。
     */
    private String safeLiteralReplace(String content, String oldString, String newString) {
        int index = content.indexOf(oldString);
        if (index == -1) {
            return content;
        }
        return content.substring(0, index) + newString + content.substring(index + oldString.length());
    }
    
    /**
     * 增强删除时的 old_string 以包含周围的空行。
     */
    private String maybeAugmentOldStringForDeletion(String content, String oldString, String newString) {
        // 如果正在删除（new_string 为空）且 old_string 以换行符结尾，
        // 包含任何后续空行以进行清理
        if (newString.isEmpty() && oldString.endsWith("\n")) {
            int index = content.indexOf(oldString);
            if (index != -1) {
                int endIndex = index + oldString.length();
                if (endIndex < content.length() && content.charAt(endIndex) == '\n') {
                    return oldString + "\n";
                }
            }
        }
        return oldString;
    }
    
    /**
     * 提取编辑周围的片段以提供上下文。
     */
    private String extractEditSnippet(String originalContent, String newContent) {
        // 简单的差异比较：找到更改的区域并显示上下文
        String[] oldLines = originalContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);
        
        // 找到第一个差异
        int firstDiff = 0;
        int minLen = Math.min(oldLines.length, newLines.length);
        while (firstDiff < minLen && oldLines[firstDiff].equals(newLines[firstDiff])) {
            firstDiff++;
        }
        
        if (firstDiff >= minLen && oldLines.length == newLines.length) {
            return null; // 未找到差异
        }
        
        // 显示更改周围的上下文
        int startLine = Math.max(0, firstDiff - 2);
        int endLine = Math.min(newLines.length, firstDiff + 5);
        
        StringBuilder snippet = new StringBuilder();
        snippet.append(String.format("Showing lines %d-%d of %d from the edited file:%n%n---%n%n",
            startLine + 1, endLine, newLines.length));
        
        for (int i = startLine; i < endLine; i++) {
            snippet.append(String.format("%6d|%s%n", i + 1, newLines[i]));
        }
        
        return snippet.toString();
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("file_path") || params.path("file_path").asText("").isEmpty()) {
            return "The 'file_path' parameter is required and must be non-empty.";
        }
        if (!params.has("old_string")) {
            return "The 'old_string' parameter is required.";
        }
        if (!params.has("new_string")) {
            return "The 'new_string' parameter is required.";
        }
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return true; // 编辑操作默认需要确认
    }
}
