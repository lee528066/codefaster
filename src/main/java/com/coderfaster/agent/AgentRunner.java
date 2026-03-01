package com.coderfaster.agent;

import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.core.AgentEvent;
import com.coderfaster.agent.core.AgentLoopController;
import com.coderfaster.agent.core.AgentResult;
import com.coderfaster.agent.http.AgentServerClient;
import com.coderfaster.agent.mock.DirectLlmClient;
import com.coderfaster.agent.mock.MultiModalLlmClient;
import com.coderfaster.agent.session.SessionConfig;
import com.coderfaster.agent.session.SessionMetadata;
import com.coderfaster.agent.session.compaction.CompactionResult;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.coderfaster.agent.session.store.SessionStore;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.DefaultToolFactory;
import com.coderfaster.agent.tool.ToolRegistry;
import com.coderfaster.agent.utils.ModelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agent Runner
 * CodeMate Agent Client 的统一入口类
 *
 * 使用示例：
 * <pre>{@code
 * AgentRunner agent = AgentRunner.builder()
 *     .uid("235419")
 *     .workingDirectory(Path.of("/path/to/project"))
 *     .build();
 *
 * AgentResult result = agent.run("帮我分析这个项目的代码结构");
 * System.out.println(result.getContent());
 *
 * agent.close();
 * }</pre>
 */
public class AgentRunner implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final AgentConfig config;
    private final AgentServerClient serverClient;
    private final ToolRegistry toolRegistry;
    private final AgentLoopController loopController;
    private final SessionConfig sessionConfig;

    private AgentRunner(AgentConfig config, ToolRegistry toolRegistry, AgentServerClient serverClient,
                       SessionConfig sessionConfig) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.serverClient = serverClient;
        this.sessionConfig = sessionConfig;
        this.loopController = new AgentLoopController(config, serverClient, toolRegistry);

        if (sessionConfig.isCleanupOnStartup()) {
            cleanupExpiredSessions();
        }

        log.info("AgentRunner initialized with {} tools, session persistence enabled", toolRegistry.size());
    }

    /**
     * 清理过期会话（启动时调用）
     */
    private void cleanupExpiredSessions() {
        try {
            SessionStore store = getSessionStore();
            if (store != null) {
                int cleaned = store.cleanupExpiredSessions(
                        config.getWorkingDirectory(),
                        sessionConfig.getCleanupPeriodDays());
                if (cleaned > 0) {
                    log.info("Cleaned up {} expired sessions", cleaned);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup expired sessions: {}", e.getMessage());
        }
    }

    /**
     * 执行 Agent 任务
     *
     * @param userMessage 用户消息
     * @return 执行结果
     */
    public AgentResult run(String userMessage) {
        return loopController.run(userMessage);
    }

    /**
     * 执行 Agent 任务（恢复会话）
     *
     * @param userMessage 用户消息
     * @param sessionId 会话 ID
     * @return 执行结果
     */
    public AgentResult run(String userMessage, String sessionId) {
        return loopController.run(userMessage, sessionId);
    }

    /**
     * 设置确认处理器
     * 用于处理危险操作的用户确认
     *
     * @param handler 确认处理器
     */
    public void setConfirmationHandler(AgentLoopController.ConfirmationHandler handler) {
        loopController.setConfirmationHandler(handler);
    }

    /**
     * 设置事件处理器
     * 用于监听 Agent 执行过程中的事件
     *
     * @param handler 事件处理器
     */
    public void setEventHandler(Consumer<AgentEvent> handler) {
        loopController.setEventHandler(handler);
    }

    /**
     * 注册自定义 Tool
     *
     * @param tool 自定义 Tool
     */
    public void registerTool(BaseTool tool) {
        toolRegistry.register(tool);
    }

    /**
     * 禁用 Tool
     *
     * @param toolName Tool 名称
     */
    public void disableTool(String toolName) {
        toolRegistry.disable(toolName);
    }

    /**
     * 启用 Tool
     *
     * @param toolName Tool 名称
     */
    public void enableTool(String toolName) {
        toolRegistry.enable(toolName);
    }

    /**
     * 获取配置
     */
    public AgentConfig getConfig() {
        return config;
    }

    /**
     * 获取 Tool 注册中心
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 健康检查
     */
    public boolean healthCheck() {
        return serverClient.healthCheck();
    }

    /**
     * 关闭 Agent
     */
    @Override
    public void close() {
        loopController.shutdown();
        serverClient.close();
        log.info("AgentRunner closed");
    }

    // ========== 会话管理方法 ==========

    /**
     * 获取 SessionStore
     */
    public SessionStore getSessionStore() {
        if (serverClient instanceof DirectLlmClient) {
            return ((DirectLlmClient) serverClient).getSessionStore();
        } else if (serverClient instanceof MultiModalLlmClient) {
            return ((MultiModalLlmClient) serverClient).getSessionStore();
        }
        return null;
    }

    /**
     * 列出当前项目的所有会话
     */
    public List<SessionMetadata> listSessions() {
        SessionStore store = getSessionStore();
        if (store != null) {
            return store.listSessions(config.getWorkingDirectory());
        }
        return List.of();
    }

    /**
     * 获取会话上下文统计
     */
    public ContextStats getContextStats(String sessionId) {
        if (serverClient instanceof DirectLlmClient) {
            return ((DirectLlmClient) serverClient).getContextStats(sessionId);
        } else if (serverClient instanceof MultiModalLlmClient) {
            return ((MultiModalLlmClient) serverClient).getContextStats(sessionId);
        }
        return ContextStats.empty(sessionConfig.getMaxContextTokens());
    }

    /**
     * 手动执行压缩
     */
    public CompactionResult compact(String sessionId, String focusHint) {
        if (serverClient instanceof DirectLlmClient) {
            return ((DirectLlmClient) serverClient).compact(sessionId, focusHint);
        } else if (serverClient instanceof MultiModalLlmClient) {
            return ((MultiModalLlmClient) serverClient).compact(sessionId, focusHint);
        }
        return CompactionResult.failure("Session compaction not supported for this client type");
    }

    /**
     * 删除会话
     */
    public boolean deleteSession(String sessionId) {
        SessionStore store = getSessionStore();
        if (store != null) {
            return store.deleteSession(sessionId);
        }
        return false;
    }

    /**
     * 导出会话为 Markdown
     */
    public String exportSession(String sessionId) {
        SessionStore store = getSessionStore();
        if (store != null) {
            return store.exportToMarkdown(sessionId);
        }
        return "";
    }

    /**
     * 根据 ID 前缀查找会话
     */
    public List<SessionMetadata> findSessionsByPrefix(String idPrefix) {
        SessionStore store = getSessionStore();
        if (store != null) {
            return store.findSessionsByPrefix(config.getWorkingDirectory(), idPrefix);
        }
        return List.of();
    }

    /**
     * 获取会话配置
     */
    public SessionConfig getSessionConfig() {
        return sessionConfig;
    }

    /**
     * 创建 Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * AgentRunner Builder
     */
    public static class Builder {
        private String uid;
        private String clientType = "java-sdk";
        private String clientVersion = "1.0.0";
        private Path workingDirectory;
        private int maxIterations = 50;
        private int connectTimeoutSeconds = 30;
        private int readTimeoutSeconds = 120;
        private int writeTimeoutSeconds = 30;
        private boolean autoConfirm = false;
        private boolean debug = false;
        private String modelName = "qwen3.5-plus";
        private String mockSystemPrompt;
        private ToolRegistry customToolRegistry;
        private AgentLoopController.ConfirmationHandler confirmationHandler;
        private Consumer<AgentEvent> eventHandler;
        private SessionConfig sessionConfig;

        /**
         * 设置用户 ID / 工号
         */
        public Builder uid(String uid) {
            this.uid = uid;
            return this;
        }

        /**
         * 设置客户端类型
         */
        public Builder clientType(String clientType) {
            this.clientType = clientType;
            return this;
        }

        /**
         * 设置客户端版本
         */
        public Builder clientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
            return this;
        }

        /**
         * 设置工作目录
         */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /**
         * 设置工作目录
         */
        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = Path.of(workingDirectory);
            return this;
        }

        /**
         * 设置最大循环次数
         */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /**
         * 设置连接超时（秒）
         */
        public Builder connectTimeoutSeconds(int seconds) {
            this.connectTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置读取超时（秒）
         */
        public Builder readTimeoutSeconds(int seconds) {
            this.readTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置写入超时（秒）
         */
        public Builder writeTimeoutSeconds(int seconds) {
            this.writeTimeoutSeconds = seconds;
            return this;
        }

        /**
         * 设置是否自动确认危险操作
         */
        public Builder autoConfirm(boolean autoConfirm) {
            this.autoConfirm = autoConfirm;
            return this;
        }

        /**
         * 设置调试模式
         */
        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        /**
         * 使用自定义 Tool 注册中心
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.customToolRegistry = toolRegistry;
            return this;
        }

        /**
         * 设置确认处理器
         */
        public Builder confirmationHandler(AgentLoopController.ConfirmationHandler handler) {
            this.confirmationHandler = handler;
            return this;
        }

        /**
         * 设置事件处理器
         */
        public Builder eventHandler(Consumer<AgentEvent> handler) {
            this.eventHandler = handler;
            return this;
        }

        /**
         * 设置模型名称
         * 默认使用 qwen3.5-plus
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置会话配置
         */
        public Builder sessionConfig(SessionConfig sessionConfig) {
            this.sessionConfig = sessionConfig;
            return this;
        }

        /**
         * 构建 AgentRunner
         */
        public AgentRunner build() {
            // 构建配置（直连模式为默认）
            AgentConfig config = AgentConfig.builder()
                    .uid(uid)
                    .clientType(clientType)
                    .clientVersion(clientVersion)
                    .workingDirectory(workingDirectory != null
                            ? workingDirectory
                            : Path.of(System.getProperty("user.dir")))
                    .maxIterations(maxIterations)
                    .connectTimeoutSeconds(connectTimeoutSeconds)
                    .readTimeoutSeconds(readTimeoutSeconds)
                    .writeTimeoutSeconds(writeTimeoutSeconds)
                    .autoConfirm(autoConfirm)
                    .debug(debug)
                    .modelName(modelName)
                    .apiKey("sk-f5e5430b825343f8af4ebd5c305f38a6")
                    .mockSystemPrompt(mockSystemPrompt)
                    .build();

            // 验证配置
            config.validate();

            // 创建 Tool 注册中心（传入 config 以配置 WebSearchTool 等需要 API Key 的工具）
            ToolRegistry toolRegistry = customToolRegistry != null
                    ? customToolRegistry
                    : DefaultToolFactory.createDefaultRegistry(config);

            // 如果使用自定义 ToolRegistry，也尝试配置 WebSearch
            if (customToolRegistry != null) {
                DefaultToolFactory.configureWebSearch(customToolRegistry, config);
            }

            // 会话配置
            SessionConfig sessConfig = sessionConfig != null ? sessionConfig : SessionConfig.defaults();

            // 创建 Server Client（统一使用直连模式，传入项目路径和会话配置）
            Path projectPath = config.getWorkingDirectory();
            AgentServerClient serverClient;
            if (ModelUtils.isMultiModalMode(modelName)) {
                serverClient = new MultiModalLlmClient(config, projectPath, sessConfig);
                log.info("Using MultiModalLlmClient with model: {}, project: {}", modelName, projectPath);
            } else {
                serverClient = new DirectLlmClient(config, projectPath, sessConfig);
                log.info("Using DirectLlmClient with model: {}, project: {}", modelName, projectPath);
            }

            // 创建 AgentRunner
            AgentRunner runner = new AgentRunner(config, toolRegistry, serverClient, sessConfig);

            // 设置处理器
            if (confirmationHandler != null) {
                runner.setConfirmationHandler(confirmationHandler);
            }
            if (eventHandler != null) {
                runner.setEventHandler(eventHandler);
            }

            return runner;
        }
    }
}
