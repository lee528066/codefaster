package com.coderfaster.agent.tool;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tool 注册中心
 * 管理所有 Tool 的定义和执行
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 已注册的 Tool 集合（保持插入顺序）
     */
    private final Map<String, BaseTool> tools = new LinkedHashMap<>();

    /**
     * 禁用的 Tool 名称集合
     */
    private final Set<String> disabledTools = ConcurrentHashMap.newKeySet();

    /**
     * 注册 Tool
     *
     * @param tool 要注册的 Tool
     * @throws IllegalArgumentException 如果 Tool 名称已存在
     */
    public void register(BaseTool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool cannot be null");
        }
        String name = tool.getName();
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        tools.put(name, tool);
        log.info("Registered tool: {} (kind: {})", name, tool.getSchema().getKind());
    }

    /**
     * 批量注册 Tool
     */
    public void registerAll(BaseTool... tools) {
        if (tools == null) {
            return;
        }
        for (BaseTool tool : tools) {
            register(tool);
        }
    }

    /**
     * 批量注册 Tool
     */
    public void registerAll(Collection<BaseTool> tools) {
        if (tools == null) {
            return;
        }
        for (BaseTool tool : tools) {
            register(tool);
        }
    }

    /**
     * 注销 Tool
     */
    public void unregister(String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        BaseTool removed = tools.remove(name);
        if (removed != null) {
            log.info("Unregistered tool: {}", name);
        }
    }

    /**
     * 禁用 Tool
     */
    public void disable(String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        disabledTools.add(name);
        log.info("Disabled tool: {}", name);
    }

    /**
     * 启用 Tool
     */
    public void enable(String name) {
        if (StringUtils.isBlank(name)) {
            return;
        }
        disabledTools.remove(name);
        log.info("Enabled tool: {}", name);
    }

    /**
     * 获取 Tool
     */
    public Optional<BaseTool> getTool(String name) {
        if (StringUtils.isBlank(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有已启用的 Tool Schema（用于上报给 Server）
     */
    public List<ToolSchema> getAllSchemas() {
        return tools.entrySet().stream()
                .filter(e -> !disabledTools.contains(e.getKey()))
                .map(e -> e.getValue().getSchema())
                .collect(Collectors.toList());
    }

    /**
     * 获取所有已注册的 Tool 名称
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * 检查 Tool 是否已注册
     */
    public boolean hasTool(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        return tools.containsKey(name);
    }

    /**
     * 检查 Tool 是否已启用
     */
    public boolean isEnabled(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        return tools.containsKey(name) && !disabledTools.contains(name);
    }

    /**
     * 执行 Tool
     *
     * @param toolName 工具名称
     * @param params 工具参数
     * @param context 执行上下文
     * @return 执行结果
     */
    public ToolResult execute(String toolName, JsonNode params, ExecutionContext context) {
        if (StringUtils.isBlank(toolName)) {
            return ToolResult.error("Tool name cannot be blank");
        }
        BaseTool tool = tools.get(toolName);

        if (tool == null) {
            return ToolResult.error("Unknown tool: " + toolName);
        }

        if (disabledTools.contains(toolName)) {
            return ToolResult.error("Tool is disabled: " + toolName);
        }

        String validationError = tool.validateParams(params);
        if (validationError != null) {
            return ToolResult.error("Invalid parameters: " + validationError);
        }

        long startTime = System.currentTimeMillis();
        try {
            log.debug("Executing tool: {} with params: {}", toolName, params);
            ToolResult result = tool.execute(params, context);
            long durationMs = System.currentTimeMillis() - startTime;

            result.setDurationMs(durationMs);
            log.debug("Tool {} execution completed, success: {}, duration: {} ms",
                    toolName, result.isSuccess(), durationMs);
            return result;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Tool execution failed: " + toolName, e);
            ToolResult errorResult = ToolResult.error("Tool execution failed: " + e.getMessage());
            errorResult.setDurationMs(durationMs);
            return errorResult;
        }
    }

    /**
     * 检查是否需要用户确认
     */
    public boolean requiresConfirmation(String toolName, JsonNode params) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }
        BaseTool tool = tools.get(toolName);
        return tool != null && tool.requiresConfirmation(params);
    }

    /**
     * 获取 Tool 数量
     */
    public int size() {
        return tools.size();
    }

    /**
     * 清空所有 Tool
     */
    public void clear() {
        tools.clear();
        disabledTools.clear();
        log.info("All tools cleared");
    }
}
