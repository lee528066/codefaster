package com.coderfaster.agent.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 会话配置
 * 管理会话存储、清理和压缩的相关配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionConfig {

    /**
     * CodeMate 配置目录名
     */
    public static final String CODERFASTER_DIR = ".coderfaster";

    /**
     * 会话存储子目录
     */
    public static final String SESSIONS_DIR = "sessions";

    /**
     * 项目目录
     */
    public static final String PROJECTS_DIR = "projects";

    /**
     * 工具结果缓存目录
     */
    public static final String CACHE_DIR = "cache";

    /**
     * 工具结果子目录
     */
    public static final String TOOL_RESULTS_DIR = "tool-results";

    // ========== 清理配置 ==========

    /**
     * 会话保留天数，-1 表示永不清理
     */
    @Builder.Default
    private int cleanupPeriodDays = 30;

    /**
     * 启动时是否执行清理
     */
    @Builder.Default
    private boolean cleanupOnStartup = true;

    // ========== 微压缩配置 ==========

    /**
     * 保留在上下文中的最近工具结果数（Hot Tail）
     */
    @Builder.Default
    private int microcompactHotTailSize = 5;

    /**
     * 触发落盘的单个结果大小（字符数）
     */
    @Builder.Default
    private int microcompactMaxResultSize = 10000;

    // ========== 自动压缩配置 ==========

    /**
     * 触发自动压缩的 token 使用率百分比
     */
    @Builder.Default
    private int autocompactThresholdPercent = 80;

    /**
     * 预留给输出的 token 空间
     */
    @Builder.Default
    private int autocompactOutputHeadroom = 4000;

    /**
     * 预留给压缩流程的 token 空间
     */
    @Builder.Default
    private int autocompactCompactionHeadroom = 8000;

    /**
     * 压缩后重新读取的最近文件数
     */
    @Builder.Default
    private int autocompactRehydrateFileCount = 5;

    /**
     * 模型的最大上下文窗口大小（tokens）
     */
    @Builder.Default
    private int maxContextTokens = 128000;

    // ========== 路径方法 ==========

    /**
     * 获取 CodeMate 主目录
     */
    public static Path getCodemateHome() {
        String home = System.getProperty("user.home");
        return Paths.get(home, CODERFASTER_DIR);
    }

    /**
     * 获取项目会话存储目录
     */
    public static Path getProjectSessionsDir(Path projectPath) {
        String encodedPath = encodeProjectPath(projectPath);
        return getCodemateHome()
                .resolve(PROJECTS_DIR)
                .resolve(encodedPath)
                .resolve(SESSIONS_DIR);
    }

    /**
     * 获取项目工具结果缓存目录
     */
    public static Path getToolResultsCacheDir(Path projectPath) {
        String encodedPath = encodeProjectPath(projectPath);
        return getCodemateHome()
                .resolve(PROJECTS_DIR)
                .resolve(encodedPath)
                .resolve(CACHE_DIR)
                .resolve(TOOL_RESULTS_DIR);
    }

    /**
     * 获取会话文件路径
     */
    public static Path getSessionFilePath(Path projectPath, String sessionId) {
        return getProjectSessionsDir(projectPath).resolve(sessionId + ".jsonl");
    }

    /**
     * 编码项目路径为目录名
     * 将路径中的 / 替换为 -，并去掉开头的 -
     */
    public static String encodeProjectPath(Path projectPath) {
        String absolute = projectPath.toAbsolutePath().normalize().toString();
        return absolute.replace("/", "-").replace("\\", "-").replaceFirst("^-+", "");
    }

    /**
     * 解码目录名为项目路径
     */
    public static Path decodeProjectPath(String encoded) {
        String path = "/" + encoded.replace("-", "/");
        return Paths.get(path);
    }

    /**
     * 创建默认配置
     */
    public static SessionConfig defaults() {
        return SessionConfig.builder().build();
    }
}
