package com.coderfaster.agent.tool;

import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.lsp.LspService;
import com.coderfaster.agent.tool.impl.EditTool;
import com.coderfaster.agent.tool.impl.ExitPlanModeTool;
import com.coderfaster.agent.tool.impl.GlobTool;
import com.coderfaster.agent.tool.impl.GrepTool;
import com.coderfaster.agent.tool.impl.ListDirectoryTool;
import com.coderfaster.agent.tool.impl.LspTool;
import com.coderfaster.agent.tool.impl.MemoryTool;
import com.coderfaster.agent.tool.impl.ReadFileTool;
import com.coderfaster.agent.tool.impl.ShellTool;
import com.coderfaster.agent.tool.impl.SkillTool;
import com.coderfaster.agent.tool.impl.TaskTool;
import com.coderfaster.agent.tool.impl.TodoWriteTool;
import com.coderfaster.agent.tool.impl.WebFetchTool;
import com.coderfaster.agent.tool.impl.WebSearchTool;
import com.coderfaster.agent.tool.impl.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认 Tool 工厂
 * 用于创建和注册默认的 Tool 集合
 *
 * 基于 coderfaster-agent 工具集实现
 */
public class DefaultToolFactory {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolFactory.class);

    /**
     * 创建包含所有默认 Tool 的注册中心
     */
    public static ToolRegistry createDefaultRegistry() {
        ToolRegistry registry = new ToolRegistry();
        registerDefaultTools(registry);
        return registry;
    }

    /**
     * 创建包含所有默认 Tool 的注册中心，并配置 AgentConfig
     *
     * @param config Agent 配置（用于配置 WebSearch 等需要 API Key 的工具）
     */
    public static ToolRegistry createDefaultRegistry(AgentConfig config) {
        ToolRegistry registry = new ToolRegistry();
        registerDefaultTools(registry, null, config);
        return registry;
    }

    /**
     * 创建包含所有默认 Tool 的注册中心，并配置 LSP 服务
     *
     * @param lspService 已初始化的 LSP 服务（可为 null，则 LSP 功能不可用）
     */
    public static ToolRegistry createDefaultRegistry(LspService lspService) {
        ToolRegistry registry = new ToolRegistry();
        registerDefaultTools(registry, lspService);
        return registry;
    }

    /**
     * 创建包含所有默认 Tool 的注册中心，并配置 LSP 服务和 AgentConfig
     *
     * @param lspService 已初始化的 LSP 服务（可为 null，则 LSP 功能不可用）
     * @param config Agent 配置（用于配置 WebSearch 等需要 API Key 的工具）
     */
    public static ToolRegistry createDefaultRegistry(LspService lspService, AgentConfig config) {
        ToolRegistry registry = new ToolRegistry();
        registerDefaultTools(registry, lspService, config);
        return registry;
    }

    /**
     * 向注册中心注册所有默认 Tool
     */
    public static void registerDefaultTools(ToolRegistry registry) {
        registerDefaultTools(registry, null, null);
    }

    /**
     * 向注册中心注册所有默认 Tool，并配置 LSP 服务
     *
     * @param registry 工具注册中心
     * @param lspService 已初始化的 LSP 服务（可为 null，则 LSP 功能不可用）
     */
    public static void registerDefaultTools(ToolRegistry registry, LspService lspService) {
        registerDefaultTools(registry, lspService, null);
    }

    /**
     * 向注册中心注册所有默认 Tool，并配置 LSP 服务和 AgentConfig
     *
     * @param registry 工具注册中心
     * @param lspService 已初始化的 LSP 服务（可为 null，则 LSP 功能不可用）
     * @param config Agent 配置（用于配置 WebSearch 等需要 API Key 的工具，可为 null）
     */
    public static void registerDefaultTools(ToolRegistry registry, LspService lspService, AgentConfig config) {
        // 文件操作 Tools
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditTool());
        registry.register(new ListDirectoryTool());

        // 搜索 Tools
        registry.register(new GrepTool());
        registry.register(new GlobTool());

        // 命令执行 Tool
        registry.register(new ShellTool());

        // 任务管理 Tools
        registry.register(new TodoWriteTool());
        registry.register(new MemoryTool());

        // Web Tools
        registry.register(new WebFetchTool());

        // WebSearchTool - 使用 AgentConfig 中的 apiKey 配置
        WebSearchTool webSearchTool;
        if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            webSearchTool = new WebSearchTool(config.getApiKey());
            log.info("WebSearchTool configured with API Key from AgentConfig");
        } else {
            webSearchTool = new WebSearchTool();
            log.info("WebSearchTool registered without API Key (web search may be unavailable)");
        }
        registry.register(webSearchTool);

        // 子代理和技能 Tools
        registry.register(new TaskTool());
        registry.register(new SkillTool());

        // 模式切换 Tool
        registry.register(new ExitPlanModeTool());

        // LSP Tool (代码智能)
        LspTool lspTool = new LspTool();
        if (lspService != null) {
            lspTool.setLspClient(lspService.getLspClient());
            log.info("LSP Tool configured with LspService");
        } else {
            log.info("LSP Tool registered without LspService (LSP features will be unavailable)");
        }
        registry.register(lspTool);

        log.info("Registered {} default tools", registry.size());
    }

    /**
     * 为已注册的 LspTool 配置 LSP 服务
     *
     * @param registry 工具注册中心
     * @param lspService 已初始化的 LSP 服务
     */
    public static void configureLspService(ToolRegistry registry, LspService lspService) {
        registry.getTool(ToolNames.LSP).ifPresent(tool -> {
            if (tool instanceof LspTool) {
                if (lspService != null) {
                    ((LspTool) tool).setLspClient(lspService.getLspClient());
                    log.info("LSP Tool configured with LspService");
                }
            }
        });
    }

    /**
     * 为已注册的 WebSearchTool 配置 API Key
     *
     * @param registry 工具注册中心
     * @param config Agent 配置
     */
    public static void configureWebSearch(ToolRegistry registry, AgentConfig config) {
        if (config == null || config.getApiKey() == null || config.getApiKey().isEmpty()) {
            return;
        }
        registry.getTool(ToolNames.WEB_SEARCH).ifPresent(tool -> {
            if (tool instanceof WebSearchTool) {
                ((WebSearchTool) tool).setApiKey(config.getApiKey());
                log.info("WebSearchTool configured with API Key from AgentConfig");
            }
        });
    }

    /**
     * 创建只读 Tool 注册中心（不包含编辑和执行操作）
     */
    public static ToolRegistry createReadOnlyRegistry() {
        return createReadOnlyRegistry(null, null);
    }

    /**
     * 创建只读 Tool 注册中心（不包含编辑和执行操作），并配置 LSP 服务
     *
     * @param lspService 已初始化的 LSP 服务（可为 null）
     */
    public static ToolRegistry createReadOnlyRegistry(LspService lspService) {
        return createReadOnlyRegistry(lspService, null);
    }

    /**
     * 创建只读 Tool 注册中心（不包含编辑和执行操作），并配置 LSP 服务和 AgentConfig
     *
     * @param lspService 已初始化的 LSP 服务（可为 null）
     * @param config Agent 配置（用于配置 WebSearch 等需要 API Key 的工具，可为 null）
     */
    public static ToolRegistry createReadOnlyRegistry(LspService lspService, AgentConfig config) {
        ToolRegistry registry = new ToolRegistry();

        // 只读文件操作
        registry.register(new ReadFileTool());
        registry.register(new ListDirectoryTool());

        // 搜索
        registry.register(new GrepTool());
        registry.register(new GlobTool());

        // Web 获取和搜索
        registry.register(new WebFetchTool());

        // WebSearchTool - 使用 AgentConfig 中的 apiKey 配置
        WebSearchTool webSearchTool;
        if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            webSearchTool = new WebSearchTool(config.getApiKey());
        } else {
            webSearchTool = new WebSearchTool();
        }
        registry.register(webSearchTool);

        // LSP (只读)
        LspTool lspTool = new LspTool();
        if (lspService != null) {
            lspTool.setLspClient(lspService.getLspClient());
        }
        registry.register(lspTool);

        log.info("Registered {} read-only tools", registry.size());
        return registry;
    }


    /**
     * 创建 Agent 模式 Tool 注册中心（完整功能），并配置 LSP 服务
     *
     * @param lspService 已初始化的 LSP 服务（可为 null）
     */
    public static ToolRegistry createAgentRegistry(LspService lspService) {
        return createAgentRegistry(lspService, null);
    }

    /**
     * 创建 Agent 模式 Tool 注册中心（完整功能），并配置 LSP 服务和 AgentConfig
     *
     * @param lspService 已初始化的 LSP 服务（可为 null）
     * @param config Agent 配置（用于配置 WebSearch 等需要 API Key 的工具，可为 null）
     */
    public static ToolRegistry createAgentRegistry(LspService lspService, AgentConfig config) {
        ToolRegistry registry = new ToolRegistry();

        // 注册所有默认工具
        registerDefaultTools(registry, lspService, config);

        log.info("Registered {} agent tools", registry.size());
        return registry;
    }

    /**
     * 创建 Plan 模式 Tool 注册中心（只读 + 退出计划模式）
     */
    public static ToolRegistry createPlanModeRegistry() {
        return createPlanModeRegistry(null, null);
    }

    /**
     * 创建 Plan 模式 Tool 注册中心（只读 + 退出计划模式），并配置 LSP 服务
     *
     * @param lspService 已初始化的 LSP 服务（可为 null）
     */
    public static ToolRegistry createPlanModeRegistry(LspService lspService) {
        return createPlanModeRegistry(lspService, null);
    }

    /**
     * 创建 Plan 模式 Tool 注册中心（只读 + 退出计划模式），并配置 LSP 服务和 AgentConfig
     *
     * @param lspService 已初始化的 LSP 服务（可为 null）
     * @param config Agent 配置（用于配置 WebSearch 等需要 API Key 的工具，可为 null）
     */
    public static ToolRegistry createPlanModeRegistry(LspService lspService, AgentConfig config) {
        ToolRegistry registry = createReadOnlyRegistry(lspService, config);

        // Plan 模式特有的退出工具
        registry.register(new ExitPlanModeTool());

        log.info("Registered {} plan mode tools", registry.size());
        return registry;
    }
}
