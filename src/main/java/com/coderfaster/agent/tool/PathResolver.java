package com.coderfaster.agent.tool;

import com.coderfaster.agent.model.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径解析工具类
 * 统一处理 Tool 中的路径解析逻辑，支持相对路径自动转换为绝对路径
 */
public final class PathResolver {

    private static final Logger logger = LoggerFactory.getLogger(PathResolver.class);

    private PathResolver() {
    }

    /**
     * 解析路径，将相对路径转换为基于工作目录的绝对路径
     *
     * @param pathStr 路径字符串（可以是相对路径或绝对路径）
     * @param context 执行上下文（包含工作目录）
     * @return 解析后的绝对路径
     */
    public static Path resolve(String pathStr, ExecutionContext context) {
        if (pathStr == null || pathStr.isEmpty()) {
            return null;
        }

        Path path = Paths.get(pathStr);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        if (context != null && context.getWorkingDirectory() != null) {
            Path resolved = context.getWorkingDirectory().resolve(pathStr).normalize();
            logger.debug("Resolved relative path '{}' to absolute: {}", pathStr, resolved);
            return resolved;
        }

        Path resolved = Paths.get(System.getProperty("user.dir")).resolve(pathStr).normalize();
        logger.debug("Resolved relative path '{}' using user.dir: {}", pathStr, resolved);
        return resolved;
    }

    /**
     * 解析路径并返回绝对路径字符串
     *
     * @param pathStr 路径字符串
     * @param context 执行上下文
     * @return 绝对路径字符串
     */
    public static String resolveToString(String pathStr, ExecutionContext context) {
        Path resolved = resolve(pathStr, context);
        return resolved != null ? resolved.toAbsolutePath().toString() : null;
    }

    /**
     * 获取工作目录
     *
     * @param context 执行上下文
     * @return 工作目录路径
     */
    public static Path getWorkingDirectory(ExecutionContext context) {
        if (context != null && context.getWorkingDirectory() != null) {
            return context.getWorkingDirectory();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    /**
     * 检查路径是否在工作目录内
     *
     * @param path    待检查的路径
     * @param context 执行上下文
     * @return 是否在工作目录内
     */
    public static boolean isWithinWorkspace(Path path, ExecutionContext context) {
        if (context == null || context.getWorkingDirectory() == null) {
            return true;
        }

        try {
            Path normalizedPath = path.normalize().toAbsolutePath();
            Path normalizedWorkDir = context.getWorkingDirectory().normalize().toAbsolutePath();
            return normalizedPath.startsWith(normalizedWorkDir);
        } catch (Exception e) {
            logger.warn("Could not validate workspace boundary: {}", e.getMessage());
            return true;
        }
    }
}
