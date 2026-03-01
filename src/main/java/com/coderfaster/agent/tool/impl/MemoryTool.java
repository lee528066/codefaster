package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Memory 工具 (save_memory) - 将特定的信息或事实保存到长期记忆中。
 * 
 * 基于 coderfaster-agent memoryTool 实现
 * 
 * 当用户明确要求你记住某些内容时，或者当他们陈述一个清晰、
 * 简洁的事实且该事实似乎值得在未来的交互中保留时使用此工具。
 */
public class MemoryTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(MemoryTool.class);
    
    /** 全局记忆配置目录 */
    private static final String CODERFASTER_CONFIG_DIR = ".coderfaster";
    
    /** 默认记忆文件名 */
    private static final String DEFAULT_MEMORY_FILENAME = "CODERFASTER.md";
    
    /** 记忆区域头部 */
    private static final String MEMORY_SECTION_HEADER = "## Codemate Added Memories";
    
    private static final String DESCRIPTION = 
        "Saves a specific piece of information or fact to your long-term memory.\n\n" +
        "Use this tool:\n\n" +
        "- When the user explicitly asks you to remember something (e.g., \"Remember that I like pineapple on pizza\", \"Please save this: my cat's name is Whiskers\").\n" +
        "- When the user states a clear, concise fact about themselves, their preferences, or their environment that seems important for you to retain for future interactions to provide a more personalized and effective assistance.\n\n" +
        "Do NOT use this tool:\n\n" +
        "- To remember conversational context that is only relevant for the current session.\n" +
        "- To save long, complex, or rambling pieces of text. The fact should be relatively short and to the point.\n" +
        "- If you are unsure whether the information is a fact worth remembering long-term. If in doubt, you can ask the user, \"Should I remember that for you?\"\n\n" +
        "## Parameters\n\n" +
        "- `fact` (string, required): The specific fact or piece of information to remember. This should be a clear, self-contained statement.\n" +
        "- `scope` (string, optional): Where to save the memory:\n" +
        "  - \"global\": Saves to user-level ~/.coderfaster/CODERFASTER.md (shared across all projects)\n" +
        "  - \"project\": Saves to current project's CODERFASTER.md (project-specific)\n" +
        "  - If not specified, the tool will default to global scope.";
    
    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("fact", "string",
                "The specific fact or piece of information to remember. " +
                "Should be a clear, self-contained statement.")
            .addEnumProperty("scope", Arrays.asList("global", "project"),
                "Where to save the memory: 'global' saves to user-level ~/.coderfaster/CODERFASTER.md " +
                "(shared across all projects), 'project' saves to current project's CODERFASTER.md " +
                "(project-specific). If not specified, defaults to global.")
            .setRequired(Arrays.asList("fact"))
            .build();
        
        return new ToolSchema(
            ToolNames.MEMORY,
            DESCRIPTION,
            ToolKind.THINK,
            parameters,
            "1.0.0"
        );
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String fact = params.path("fact").asText("");
        String scope = params.has("scope") && !params.path("scope").isNull() 
            ? params.path("scope").asText("global") : "global";
        
        logger.debug("Memory: fact={}, scope={}", fact, scope);
        
        // 验证事实
        if (fact.trim().isEmpty()) {
            return ToolResult.failure("Parameter 'fact' must be a non-empty string.");
        }
        
        // 验证范围
        if (!scope.equals("global") && !scope.equals("project")) {
            scope = "global";
        }
        
        // 确定记忆文件路径
        Path memoryFilePath;
        if (scope.equals("project")) {
            Path workingDir = context != null && context.getWorkingDirectory() != null 
                ? context.getWorkingDirectory() 
                : Paths.get(System.getProperty("user.dir"));
            memoryFilePath = workingDir.resolve(DEFAULT_MEMORY_FILENAME);
        } else {
            String homeDir = System.getProperty("user.home");
            memoryFilePath = Paths.get(homeDir, CODERFASTER_CONFIG_DIR, DEFAULT_MEMORY_FILENAME);
        }
        
        try {
            // 确保父目录存在
            Path parentDir = memoryFilePath.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            // 读取当前内容
            String currentContent = "";
            if (Files.exists(memoryFilePath)) {
                currentContent = new String(Files.readAllBytes(memoryFilePath), StandardCharsets.UTF_8);
            }
            
            // 计算添加事实后的新内容
            String newContent = computeNewContent(currentContent, fact);
            
            // 写入更新后的内容
            Files.write(memoryFilePath, newContent.getBytes(StandardCharsets.UTF_8));
            
            String successMessage = "Okay, I've remembered that in " + scope + " memory: \"" + fact + "\"";
            
            return ToolResult.builder()
                .success(true)
                .content(successMessage)
                .displayData("Memory saved to " + memoryFilePath.toString())
                .build();
            
        } catch (IOException e) {
            logger.error("Error saving memory: {}", e.getMessage(), e);
            return ToolResult.failure("Error saving memory: " + e.getMessage());
        }
    }
    
    /**
     * 通过将事实添加到记忆区域来计算新内容。
     */
    private String computeNewContent(String currentContent, String fact) {
        // 处理事实：移除前导短横线并修剪
        String processedFact = fact.trim();
        processedFact = processedFact.replaceAll("^(-+\\s*)+", "").trim();
        String newMemoryItem = "- " + processedFact;
        
        int headerIndex = currentContent.indexOf(MEMORY_SECTION_HEADER);
        
        if (headerIndex == -1) {
            // 未找到头部，追加头部然后追加条目
            String separator = ensureNewlineSeparation(currentContent);
            return currentContent + separator + MEMORY_SECTION_HEADER + "\n" + newMemoryItem + "\n";
        } else {
            // 找到头部，确定插入新记忆条目的位置
            int startOfSectionContent = headerIndex + MEMORY_SECTION_HEADER.length();
            
            // 查找此区域的结尾（下一个 ## 或文件末尾）
            int endOfSectionIndex = currentContent.indexOf("\n## ", startOfSectionContent);
            if (endOfSectionIndex == -1) {
                endOfSectionIndex = currentContent.length();
            }
            
            String beforeSection = currentContent.substring(0, startOfSectionContent).trim();
            String sectionContent = currentContent.substring(startOfSectionContent, endOfSectionIndex).trim();
            String afterSection = currentContent.substring(endOfSectionIndex);
            
            // 添加新条目
            sectionContent = sectionContent + "\n" + newMemoryItem;
            
            return beforeSection + "\n" + sectionContent.trim() + "\n" + afterSection;
        }
    }
    
    /**
     * 在追加内容之前确保正确的换行分隔。
     */
    private String ensureNewlineSeparation(String currentContent) {
        if (currentContent.isEmpty()) {
            return "";
        }
        if (currentContent.endsWith("\n\n")) {
            return "";
        }
        if (currentContent.endsWith("\n")) {
            return "\n";
        }
        return "\n\n";
    }
    
    /**
     * 获取全局记忆文件路径。
     */
    public static Path getGlobalMemoryFilePath() {
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, CODERFASTER_CONFIG_DIR, DEFAULT_MEMORY_FILENAME);
    }
    
    /**
     * 获取项目记忆文件路径。
     */
    public static Path getProjectMemoryFilePath(String projectDir) {
        return Paths.get(projectDir, DEFAULT_MEMORY_FILENAME);
    }
    
    /**
     * 从文件读取记忆内容。
     */
    public static String readMemoryContent(Path memoryFilePath) {
        if (!Files.exists(memoryFilePath)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(memoryFilePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Error reading memory file: {}", e.getMessage());
            return "";
        }
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("fact") || params.path("fact").asText("").trim().isEmpty()) {
            return "Parameter 'fact' must be a non-empty string.";
        }
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return true; // 记忆保存需要确认
    }
}
