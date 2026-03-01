package com.coderfaster.agent.tool;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tool 基类
 * 所有 Tool 都需要继承此类并实现相应方法
 *
 * 每个 Tool 需要提供：
 * 1. Schema（给 LLM 理解的描述信息）
 * 2. execute 方法（实际执行逻辑）
 */
public abstract class BaseTool {

    /**
     * 获取 Tool Schema
     * 用于上报给 Server，再转发给 LLM
     *
     * @return Tool 的元信息描述
     */
    public abstract ToolSchema getSchema();

    /**
     * 执行 Tool
     *
     * @param params 工具参数（JSON 格式）
     * @param context 执行上下文
     * @return 执行结果
     */
    public abstract ToolResult execute(JsonNode params, ExecutionContext context);

    /**
     * 是否需要用户确认（默认不需要）
     * 危险操作（如编辑文件、执行 shell 命令）应该返回 true
     *
     * @param params 工具参数
     * @return 是否需要确认
     */
    public boolean requiresConfirmation(JsonNode params) {
        return false;
    }

    /**
     * 验证参数是否有效
     * 子类可以重写此方法添加参数验证逻辑
     *
     * @param params 工具参数
     * @return 验证失败的错误信息，null 表示验证通过
     */
    public String validateParams(JsonNode params) {
        return null;
    }

    /**
     * 获取工具名称（便捷方法）
     */
    public String getName() {
        ToolSchema schema = getSchema();
        if (schema == null) {
            return null;
        }
        return schema.getName();
    }

    /**
     * 是否可以并发执行（默认只读工具可以并发）
     * 子类可以重写此方法来自定义并发行为
     *
     * @return true 表示可以并发执行，false 表示必须串行执行
     */
    public boolean canExecuteInParallel() {
        // 默认情况下，不需要确认的工具（通常是只读工具）可以并发执行
        return !requiresConfirmation(null);
    }
}
