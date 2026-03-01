package com.coderfaster.agent.config;

import com.coderfaster.agent.config.local.LocalConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Agent 配置类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConfig {

    /**
     * 用户 ID / 工号
     */
    private String uid;

    /**
     * 客户端类型（如：idea, cli, web）
     */
    @Builder.Default
    private String clientType = "java-sdk";

    /**
     * 客户端版本
     */
    @Builder.Default
    private String clientVersion = "1.0.0";

    /**
     * 工作目录
     */
    @Builder.Default
    private Path workingDirectory = Paths.get(System.getProperty("user.dir"));

    /**
     * 最大循环次数（防止无限循环）
     */
    @Builder.Default
    private int maxIterations = 50;

    /**
     * 连接超时（秒）
     */
    @Builder.Default
    private int connectTimeoutSeconds = 30;

    /**
     * 读取超时（秒）
     */
    @Builder.Default
    private int readTimeoutSeconds = 120;

    /**
     * 写入超时（秒）
     */
    @Builder.Default
    private int writeTimeoutSeconds = 30;

    /**
     * 是否自动确认危险操作（默认需要用户确认）
     */
    @Builder.Default
    private boolean autoConfirm = false;

    /**
     * 是否启用调试模式
     */
    @Builder.Default
    private boolean debug = false;

    /**
     * 使用的模型名称
     * 默认使用 qwen3.5-plus
     */
    @Builder.Default
    private String modelName = "qwen3.5-plus";

    /**
     * API 密钥
     * 如果未设置，则从本地配置文件或环境变量获取
     */
    private String apiKey;

    /**
     * Base URL（用于 Code Plan 等特殊配置）
     */
    private String baseUrl;

    /**
     * 认证类型：NORMAL 或 CODE_PLAN
     */
    @Builder.Default
    private String authType = "NORMAL";

    /**
     * Mock 模式下的系统提示词
     */
    private String mockSystemPrompt;

    /**
     * 工具并发执行的最大线程数
     * 默认为 CPU 核心数的 2 倍
     */
    @Builder.Default
    private int maxConcurrentTools = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * 是否启用工具并发执行
     */
    @Builder.Default
    private boolean enableParallelToolExecution = true;

    /**
     * 工具并发执行的队列大小
     */
    @Builder.Default
    private int maxQueueSize = 2048;

    /**
     * 验证配置
     */
    public void validate() {
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory is required");
        }
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (maxConcurrentTools <= 0) {
            throw new IllegalArgumentException("maxConcurrentTools must be positive");
        }
    }

    /**
     * 从本地配置加载 AgentConfig
     */
    public static AgentConfig fromLocalConfig() {
        try {
            LocalConfig localConfig = LocalConfig.load();
            return AgentConfig.builder()
                    .apiKey(localConfig.getApiKey())
                    .modelName(localConfig.getModelName())
                    .baseUrl(localConfig.getEffectiveBaseUrl())
                    .authType(localConfig.getAuthType())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load local config", e);
        }
    }

    /**
     * 从本地配置加载或创建 AgentConfig（带交互式引导）
     */
    public static AgentConfig fromLocalConfigWithInit() {
        try {
            LocalConfig localConfig = com.coderfaster.agent.config.local.ConfigInitializer.initialize();
            return AgentConfig.builder()
                    .apiKey(localConfig.getApiKey())
                    .modelName(localConfig.getModelName())
                    .baseUrl(localConfig.getEffectiveBaseUrl())
                    .authType(localConfig.getAuthType())
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize config", e);
        }
    }
}
