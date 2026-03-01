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

import java.util.*;

/**
 * ExitPlanMode 工具 - 在计划模式下用于展示计划并请求用户批准以继续。
 * 
 * 基于 coderfaster-agent exitPlanMode 实现
 * 
 * 当你处于计划模式并已完成计划展示且准备好编写代码时使用此工具。
 * 这将提示用户退出计划模式。
 * 
 * 重要提示：仅在任务需要规划需要编写代码的任务实现步骤时使用此工具。
 * 对于收集信息、搜索文件、读取文件或通常尝试理解代码库的研究任务 - 不要使用此工具。
 */
public class ExitPlanModeTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(ExitPlanModeTool.class);
    
    /** 处理计划批准的回调 */
    private PlanApprovalHandler approvalHandler;
    
    /** 跟踪上次计划是否已批准 */
    private volatile boolean lastPlanApproved = false;
    
    private static final String DESCRIPTION = 
        "Use this tool when you are in plan mode and have finished presenting your plan and are ready to code. " +
        "This will prompt the user to exit plan mode.\n\n" +
        "IMPORTANT: Only use this tool when the task requires planning the implementation steps of a task " +
        "that requires writing code. For research tasks where you're gathering information, searching files, " +
        "reading files or in general trying to understand the codebase - do NOT use this tool.\n\n" +
        "Examples:\n" +
        "1. Initial task: \"Search for and understand the implementation of vim mode in the codebase\" - " +
        "Do NOT use the exit plan mode tool because you are not planning the implementation steps of a task.\n" +
        "2. Initial task: \"Help me implement yank mode for vim\" - Use the exit plan mode tool after you have " +
        "finished planning the implementation steps of the task.";
    
    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("plan", "string",
                "The plan you came up with, that you want to run by the user for approval. " +
                "Supports markdown. The plan should be pretty concise.")
            .setRequired(Arrays.asList("plan"))
            .build();
        
        return new ToolSchema(
            ToolNames.EXIT_PLAN_MODE,
            DESCRIPTION,
            ToolKind.THINK,
            parameters,
            "1.0.0"
        );
    }
    
    /**
     * 设置计划批准处理器。
     */
    public void setApprovalHandler(PlanApprovalHandler handler) {
        this.approvalHandler = handler;
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String plan = params.path("plan").asText("");
        
        logger.debug("ExitPlanMode: plan.length={}", plan.length());
        
        // 验证计划
        if (plan.trim().isEmpty()) {
            return ToolResult.failure("Parameter 'plan' must be a non-empty string.");
        }
        
        // 如果有批准处理器，使用它
        if (approvalHandler != null) {
            try {
                boolean approved = approvalHandler.requestApproval(plan);
                lastPlanApproved = approved;
                
                if (!approved) {
                    return ToolResult.builder()
                        .success(true)
                        .content("Plan execution was not approved. Remaining in plan mode.")
                        .displayData(buildPlanDisplay(plan, "rejected"))
                        .build();
                }
                
                return ToolResult.builder()
                    .success(true)
                    .content("User has approved your plan. You can now start coding. " +
                        "Start with updating your todo list if applicable.")
                    .displayData(buildPlanDisplay(plan, "approved"))
                    .build();
                    
            } catch (Exception e) {
                logger.error("Error during plan approval: {}", e.getMessage(), e);
                return ToolResult.failure("Failed to present plan: " + e.getMessage());
            }
        }
        
        // 默认行为：假设已批准（用于非交互式使用）
        lastPlanApproved = true;
        
        return ToolResult.builder()
            .success(true)
            .content("User has approved your plan. You can now start coding. " +
                "Start with updating your todo list if applicable.")
            .displayData(buildPlanDisplay(plan, "approved"))
            .build();
    }
    
    /**
     * 为计划构建显示数据。
     */
    private Object buildPlanDisplay(String plan, String status) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("type", "plan_summary");
        display.put("status", status);
        display.put("plan", plan);
        display.put("message", status.equals("approved") ? "User approved the plan." : "Plan was rejected.");
        return display;
    }
    
    /**
     * 检查上次计划是否已批准。
     */
    public boolean wasLastPlanApproved() {
        return lastPlanApproved;
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("plan") || params.path("plan").asText("").trim().isEmpty()) {
            return "Parameter 'plan' must be a non-empty string.";
        }
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return true; // 计划批准需要用户确认
    }
    
    /**
     * 处理计划批准的接口。
     */
    public interface PlanApprovalHandler {
        /**
         * 请求用户批准计划。
         * 
         * @param plan 要批准的计划
         * @return 如果批准返回 true，如果拒绝返回 false
         */
        boolean requestApproval(String plan);
    }
    
    /**
     * 计划批准结果的枚举。
     */
    public enum ApprovalOutcome {
        /** 用户批准并希望继续一次 */
        PROCEED_ONCE,
        /** 用户批准并希望自动批准未来的计划 */
        PROCEED_ALWAYS,
        /** 用户取消/拒绝了计划 */
        CANCEL
    }
}
