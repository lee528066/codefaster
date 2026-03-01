package com.coderfaster.agent.lsp;

import com.coderfaster.agent.tool.impl.LspTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * LSP 服务类
 * 提供 LSP 功能的高层封装，简化初始化和使用
 */
public class LspService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LspService.class);

    private final LspServerManager serverManager;
    private final LspClientImpl lspClient;
    private final String workspaceRoot;
    private volatile boolean initialized = false;

    private LspService(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        this.serverManager = new LspServerManager();
        this.lspClient = new LspClientImpl(serverManager, workspaceRoot);
    }

    /**
     * 创建 LSP 服务构建器
     */
    public static Builder builder(String workspaceRoot) {
        return new Builder(workspaceRoot);
    }

    /**
     * 初始化并启动所有 LSP 服务器
     */
    public CompletableFuture<Void> initialize() {
        return serverManager.startAllServers()
                .thenRun(() -> {
                    initialized = true;
                    logger.info("LSP service initialized with {} servers", serverManager.getReadyServers().size());
                });
    }

    /**
     * 同步初始化并启动所有 LSP 服务器
     */
    public void initializeSync(long timeoutMs) throws Exception {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        initialize().get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查服务是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取 LspClient 实例，用于注入到 LspTool
     */
    public LspTool.LspClient getLspClient() {
        return lspClient;
    }

    /**
     * 获取 LspServerManager 实例
     */
    public LspServerManager getServerManager() {
        return serverManager;
    }

    /**
     * 获取工作空间根目录
     */
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    /**
     * 创建一个已配置好 LspClient 的 LspTool 实例
     */
    public LspTool createLspTool() {
        LspTool tool = new LspTool();
        tool.setLspClient(lspClient);
        return tool;
    }

    @Override
    public void close() {
        try {
            lspClient.closeAllDocuments();
        } catch (Exception e) {
            logger.warn("Error closing documents", e);
        }
        serverManager.close();
        initialized = false;
        logger.info("LSP service closed");
    }

    /**
     * LSP 服务构建器
     */
    public static class Builder {
        private final String workspaceRoot;
        private final List<LspServerConfig> configs = new ArrayList<>();

        private Builder(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        /**
         * 添加 LSP 服务器配置
         */
        public Builder withConfig(LspServerConfig config) {
            this.configs.add(config);
            return this;
        }

        /**
         * 构建 LSP 服务
         */
        public LspService build() {
            LspService service = new LspService(workspaceRoot);
            for (LspServerConfig config : configs) {
                service.serverManager.addServerConfig(config);
            }
            return service;
        }
    }
}
