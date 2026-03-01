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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 工具 - 启动专门的子代理来自主处理复杂的多步骤任务。
 * <p>
 * 基于 coderfaster-agent task 实现
 * <p>
 * 该工具动态加载可用的子代理，并将它们包含在描述中供模型选择。
 * <p>
 * 注意：这是一个简化实现。在完整实现中，将与 SubagentManager 集成以处理实际的子代理执行。
 */
public class TaskTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(TaskTool.class);

    /**
     * 可用的子代理类型
     */
    private List<SubagentConfig> availableSubagents = new ArrayList<>();

    /**
     * 子代理管理回调
     */
    private SubagentManager subagentManager;

    private static final String BASE_DESCRIPTION =
            "Launch a new agent to handle complex, multi-step tasks autonomously.\n\n" +
                    "When using the Task tool, you must specify a subagent_type parameter to select which agent type "
                    + "to use.\n\n" +
                    "When NOT to use the Agent tool:\n" +
                    "- If you want to read a specific file path, use the Read or Glob tool instead\n" +
                    "- If you are searching for a specific class definition like \"class Foo\", use the Glob tool "
                    + "instead\n" +
                    "- If you are searching for code within a specific file or set of 2-3 files, use the Read tool "
                    + "instead\n" +
                    "- Other tasks that are not related to the agent descriptions\n\n" +
                    "Usage notes:\n" +
                    "1. Launch multiple agents concurrently whenever possible, to maximize performance\n" +
                    "2. When the agent is done, it will return a single message back to you. The result is not "
                    + "visible to the user.\n" +
                    "3. Each agent invocation is stateless. Your prompt should contain a highly detailed task "
                    + "description.\n" +
                    "4. The agent's outputs should generally be trusted\n" +
                    "5. Clearly tell the agent whether you expect it to write code or just do research";

    @Override
    public ToolSchema getSchema() {
        // 构建子代理描述
        String subagentDescriptions = buildSubagentDescriptions();
        String fullDescription = BASE_DESCRIPTION + "\n\nAvailable agent types:\n" + subagentDescriptions;

        // 构建子代理枚举值
        List<String> subagentNames = new ArrayList<>();
        for (SubagentConfig config : availableSubagents) {
            subagentNames.add(config.name);
        }

        JsonSchemaBuilder builder = new JsonSchemaBuilder()
                .addProperty("description", "string",
                        "A short (3-5 word) description of the task")
                .addProperty("prompt", "string",
                        "The task for the agent to perform")
                .setRequired(Arrays.asList("description", "prompt", "subagent_type"));

        if (!subagentNames.isEmpty()) {
            builder.addEnumProperty("subagent_type", subagentNames,
                    "The type of specialized agent to use for this task");
        } else {
            builder.addProperty("subagent_type", "string",
                    "The type of specialized agent to use for this task");
        }

        JsonNode parameters = builder.build();

        return new ToolSchema(
                ToolNames.TASK,
                fullDescription,
                ToolKind.OTHER,
                parameters,
                "1.0.0"
        );
    }

    /**
     * 设置用于处理子代理操作的子代理管理器。
     */
    public void setSubagentManager(SubagentManager manager) {
        this.subagentManager = manager;
        refreshSubagents();
    }

    /**
     * 刷新可用子代理列表。
     */
    public void refreshSubagents() {
        if (subagentManager != null) {
            try {
                availableSubagents = subagentManager.listSubagents();
            } catch (Exception e) {
                logger.warn("Failed to load subagents: {}", e.getMessage());
                availableSubagents = getDefaultSubagents();
            }
        } else {
            availableSubagents = getDefaultSubagents();
        }
    }

    /**
     * 获取默认的子代理配置。
     */
    private List<SubagentConfig> getDefaultSubagents() {
        List<SubagentConfig> defaults = new ArrayList<>();

        defaults.add(new SubagentConfig(
                "coder",
                "A specialized coding agent that can write, modify, and refactor code",
                Arrays.asList(ToolNames.READ_FILE, ToolNames.WRITE_FILE, ToolNames.EDIT,
                        ToolNames.GREP, ToolNames.GLOB, ToolNames.SHELL)
        ));

        defaults.add(new SubagentConfig(
                "researcher",
                "A research agent that explores codebases and gathers information",
                Arrays.asList(ToolNames.READ_FILE, ToolNames.GREP, ToolNames.GLOB,
                        ToolNames.LS, ToolNames.WEB_FETCH)
        ));

        defaults.add(new SubagentConfig(
                "reviewer",
                "A code review agent that analyzes code quality and suggests improvements",
                Arrays.asList(ToolNames.READ_FILE, ToolNames.GREP, ToolNames.GLOB)
        ));

        return defaults;
    }

    /**
     * 为工具描述构建子代理描述。
     */
    private String buildSubagentDescriptions() {
        if (availableSubagents.isEmpty()) {
            return "No subagents are currently configured.";
        }

        StringBuilder sb = new StringBuilder();
        for (SubagentConfig config : availableSubagents) {
            sb.append("- **").append(config.name).append("**: ").append(config.description).append("\n");
        }
        return sb.toString();
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String description = params.path("description").asText("");
        String prompt = params.path("prompt").asText("");
        String subagentType = params.path("subagent_type").asText("");

        logger.debug("Task: description={}, subagent_type={}", description, subagentType);

        // 验证参数
        if (description.trim().isEmpty()) {
            return ToolResult.failure("Parameter 'description' must be a non-empty string.");
        }

        if (prompt.trim().isEmpty()) {
            return ToolResult.failure("Parameter 'prompt' must be a non-empty string.");
        }

        if (subagentType.trim().isEmpty()) {
            return ToolResult.failure("Parameter 'subagent_type' must be a non-empty string.");
        }

        // 查找子代理配置
        SubagentConfig subagentConfig = null;
        for (SubagentConfig config : availableSubagents) {
            if (config.name.equals(subagentType)) {
                subagentConfig = config;
                break;
            }
        }

        if (subagentConfig == null) {
            List<String> availableNames = new ArrayList<>();
            for (SubagentConfig config : availableSubagents) {
                availableNames.add(config.name);
            }
            return ToolResult.failure(
                    "Subagent '" + subagentType + "' not found. Available subagents: " +
                            String.join(", ", availableNames)
            );
        }

        // 执行子代理任务
        try {
            String result;
            if (subagentManager != null) {
                result = subagentManager.executeSubagent(subagentConfig, prompt, context);
            } else {
                // 降级处理：返回指示任务已排队的消息
                result = "Task delegated to " + subagentType + " subagent: " + description + "\n\n" +
                        "Prompt: " + prompt + "\n\n" +
                        "Note: This is a placeholder response. In a full implementation, the subagent would " +
                        "execute the task and return the actual results.";
            }

            return ToolResult.builder()
                    .success(true)
                    .content(result)
                    .displayData(buildTaskDisplay(subagentConfig, description, "completed"))
                    .build();

        } catch (Exception e) {
            logger.error("Error executing subagent task: {}", e.getMessage(), e);
            return ToolResult.builder()
                    .success(false)
                    .content("Failed to run subagent: " + e.getMessage())
                    .displayData(buildTaskDisplay(subagentConfig, description, "failed"))
                    .error(e.getMessage())
                    .build();
        }
    }

    /**
     * 构建任务执行的展示数据。
     */
    private Object buildTaskDisplay(SubagentConfig config, String description, String status) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("type", "task_execution");
        display.put("subagentName", config.name);
        display.put("taskDescription", description);
        display.put("status", status);
        return display;
    }

    /**
     * 获取可用子代理名称列表。
     */
    public List<String> getAvailableSubagentNames() {
        List<String> names = new ArrayList<>();
        for (SubagentConfig config : availableSubagents) {
            names.add(config.name);
        }
        return names;
    }

    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("description") || params.path("description").asText("").trim().isEmpty()) {
            return "Parameter 'description' must be a non-empty string.";
        }
        if (!params.has("prompt") || params.path("prompt").asText("").trim().isEmpty()) {
            return "Parameter 'prompt' must be a non-empty string.";
        }
        if (!params.has("subagent_type") || params.path("subagent_type").asText("").trim().isEmpty()) {
            return "Parameter 'subagent_type' must be a non-empty string.";
        }
        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 任务委派不需要确认
    }

    /**
     * 子代理配置。
     */
    public static class SubagentConfig {
        public final String name;
        public final String description;
        public final List<String> tools;

        public SubagentConfig(String name, String description, List<String> tools) {
            this.name = name;
            this.description = description;
            this.tools = tools;
        }
    }

    /**
     * 子代理管理接口。
     */
    public interface SubagentManager {
        /**
         * 列出所有可用的子代理。
         *
         * @return 可用子代理配置列表
         */
        List<SubagentConfig> listSubagents();

        /**
         * 使用给定的配置和提示执行子代理。
         *
         * @param config 要执行的子代理配置
         * @param prompt 发送给子代理的任务提示
         * @param context 包含环境和状态信息的执行上下文
         * @return 子代理的执行结果
         */
        String executeSubagent(SubagentConfig config, String prompt, ExecutionContext context);
    }
}
