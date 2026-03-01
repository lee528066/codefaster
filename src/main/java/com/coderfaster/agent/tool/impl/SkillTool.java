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
import java.nio.file.*;
import java.util.*;

/**
 * Skill 工具 - 在主对话中执行技能。
 * 
 * 基于 coderfaster-agent skill 实现
 * 
 * 该工具动态加载可用的技能，并将它们包含在描述中供模型选择。
 * 
 * 技能提供专门的能力和领域知识。
 */
public class SkillTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);
    
    /** 项目级技能目录 */
    private static final String PROJECT_SKILLS_DIR = ".coderfaster/skills";
    
    /** 用户级技能目录 */
    private static final String USER_SKILLS_DIR = ".coderfaster/skills";
    
    /** 技能文件名 */
    private static final String SKILL_FILE = "SKILL.md";
    
    /** 可用技能 */
    private List<SkillConfig> availableSkills = new ArrayList<>();
    
    /** 技能管理器回调 */
    private SkillManager skillManager;
    
    private static final String BASE_DESCRIPTION = 
        "Execute a skill within the main conversation.\n\n" +
        "When users ask you to perform tasks, check if any of the available skills below can help " +
        "complete the task more effectively. Skills provide specialized capabilities and domain knowledge.\n\n" +
        "How to invoke:\n" +
        "- Use this tool with the skill name only (no arguments)\n" +
        "- Examples:\n" +
        "  - `skill: \"pdf\"` - invoke the pdf skill\n" +
        "  - `skill: \"xlsx\"` - invoke the xlsx skill\n\n" +
        "Important:\n" +
        "- When a skill is relevant, you must invoke this tool IMMEDIATELY as your first action\n" +
        "- NEVER just announce or mention a skill in your text response without actually calling this tool\n" +
        "- This is a BLOCKING REQUIREMENT: invoke the relevant Skill tool BEFORE generating any other response\n" +
        "- Only use skills listed below\n" +
        "- Do not invoke a skill that is already running\n" +
        "- When executing scripts or loading referenced files, ALWAYS resolve absolute paths from skill's base directory";
    
    @Override
    public ToolSchema getSchema() {
        // 构建技能描述
        String skillDescriptions = buildSkillDescriptions();
        String fullDescription = BASE_DESCRIPTION + "\n\nAvailable skills:\n" + skillDescriptions;
        
        // 构建技能枚举值
        List<String> skillNames = new ArrayList<>();
        for (SkillConfig config : availableSkills) {
            skillNames.add(config.name);
        }
        
        JsonSchemaBuilder builder = new JsonSchemaBuilder()
            .setRequired(Arrays.asList("skill"));
        
        if (!skillNames.isEmpty()) {
            builder.addEnumProperty("skill", skillNames,
                "The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"");
        } else {
            builder.addProperty("skill", "string",
                "The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"");
        }
        
        JsonNode parameters = builder.build();
        
        return new ToolSchema(
            ToolNames.SKILL,
            fullDescription,
            ToolKind.READ,
            parameters,
            "1.0.0"
        );
    }
    
    /**
     * 设置技能管理器以处理技能操作。
     */
    public void setSkillManager(SkillManager manager) {
        this.skillManager = manager;
        refreshSkills();
    }
    
    /**
     * 刷新可用技能列表。
     */
    public void refreshSkills() {
        if (skillManager != null) {
            try {
                availableSkills = skillManager.listSkills();
            } catch (Exception e) {
                logger.warn("Failed to load skills: {}", e.getMessage());
                availableSkills = new ArrayList<>();
            }
        } else {
            // 尝试从默认位置加载技能
            availableSkills = loadSkillsFromDefaultLocations();
        }
    }
    
    /**
     * 从默认位置加载技能。
     */
    private List<SkillConfig> loadSkillsFromDefaultLocations() {
        List<SkillConfig> skills = new ArrayList<>();
        
        // 从用户主目录加载
        String homeDir = System.getProperty("user.home");
        Path userSkillsDir = Paths.get(homeDir, USER_SKILLS_DIR);
        loadSkillsFromDirectory(userSkillsDir, skills, "user");
        
        // 从当前项目目录加载
        String workDir = System.getProperty("user.dir");
        Path projectSkillsDir = Paths.get(workDir, PROJECT_SKILLS_DIR);
        loadSkillsFromDirectory(projectSkillsDir, skills, "project");
        
        return skills;
    }
    
    /**
     * 从目录加载技能。
     */
    private void loadSkillsFromDirectory(Path skillsDir, List<SkillConfig> skills, String level) {
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            return;
        }
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path skillFile = entry.resolve(SKILL_FILE);
                    if (Files.exists(skillFile)) {
                        try {
                            String content = new String(Files.readAllBytes(skillFile), StandardCharsets.UTF_8);
                            SkillConfig config = parseSkillFile(entry.getFileName().toString(), content, 
                                skillFile.toString(), level);
                            if (config != null) {
                                skills.add(config);
                            }
                        } catch (IOException e) {
                            logger.warn("Failed to read skill file: {}", skillFile);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to list skills directory: {}", skillsDir);
        }
    }
    
    /**
     * 解析技能文件并提取配置。
     */
    private SkillConfig parseSkillFile(String name, String content, String filePath, String level) {
        // 从第一段或标题提取描述
        String description = name;
        String body = content;
        
        // 尝试提取标题（# Title）
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.startsWith("# ")) {
                description = line.substring(2).trim();
                break;
            }
        }
        
        return new SkillConfig(name, description, level, filePath, body);
    }
    
    /**
     * 为工具描述构建技能描述。
     */
    private String buildSkillDescriptions() {
        if (availableSkills.isEmpty()) {
            return "No skills are currently configured. Skills can be created by adding directories " +
                "with SKILL.md files to .coderfaster/skills/ or ~/.coderfaster/skills/.";
        }
        
        StringBuilder sb = new StringBuilder();
        for (SkillConfig config : availableSkills) {
            sb.append("<skill>\n");
            sb.append("<name>").append(config.name).append("</name>\n");
            sb.append("<description>").append(config.description).append(" (").append(config.level).append(")</description>\n");
            sb.append("<level>").append(config.level).append("</level>\n");
            sb.append("</skill>\n");
        }
        return sb.toString();
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String skillName = params.path("skill").asText("");
        
        logger.debug("Skill: name={}", skillName);
        
        // 验证参数
        if (skillName.trim().isEmpty()) {
            return ToolResult.failure("Parameter 'skill' must be a non-empty string.");
        }
        
        // 查找技能配置
        SkillConfig skillConfig = null;
        for (SkillConfig config : availableSkills) {
            if (config.name.equals(skillName)) {
                skillConfig = config;
                break;
            }
        }
        
        if (skillConfig == null) {
            List<String> availableNames = new ArrayList<>();
            for (SkillConfig config : availableSkills) {
                availableNames.add(config.name);
            }
            
            if (availableNames.isEmpty()) {
                return ToolResult.failure(
                    "Skill '" + skillName + "' not found. No skills are currently available."
                );
            }
            return ToolResult.failure(
                "Skill '" + skillName + "' not found. Available skills: " + 
                String.join(", ", availableNames)
            );
        }
        
        // 加载并返回技能内容
        try {
            String baseDir = Paths.get(skillConfig.filePath).getParent().toString();
            
            // 构建包含基础目录信息的 LLM 内容
            String llmContent = "Base directory for this skill: " + baseDir + "\n" +
                "Important: ALWAYS resolve absolute paths from this base directory when working with skills.\n\n" +
                skillConfig.body + "\n";
            
            return ToolResult.builder()
                .success(true)
                .content(llmContent)
                .displayData(skillConfig.description)
                .build();
                
        } catch (Exception e) {
            logger.error("Error loading skill: {}", e.getMessage(), e);
            return ToolResult.failure("Failed to load skill '" + skillName + "': " + e.getMessage());
        }
    }
    
    /**
     * 获取可用技能名称列表。
     */
    public List<String> getAvailableSkillNames() {
        List<String> names = new ArrayList<>();
        for (SkillConfig config : availableSkills) {
            names.add(config.name);
        }
        return names;
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("skill") || params.path("skill").asText("").trim().isEmpty()) {
            return "Parameter 'skill' must be a non-empty string.";
        }
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 技能加载是只读的
    }
    
    /**
     * 技能配置。
     */
    public static class SkillConfig {
        public final String name;
        public final String description;
        public final String level;  // "user" 或 "project"
        public final String filePath;
        public final String body;
        
        public SkillConfig(String name, String description, String level, String filePath, String body) {
            this.name = name;
            this.description = description;
            this.level = level;
            this.filePath = filePath;
            this.body = body;
        }
    }
    
    /**
     * 技能管理接口。
     */
    public interface SkillManager {
        List<SkillConfig> listSkills();
        SkillConfig loadSkill(String name);
    }
}
