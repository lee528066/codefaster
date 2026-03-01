package com.coderfaster.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tool 执行上下文
 * 提供执行 Tool 时需要的环境信息和回调
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContext {
    
    /**
     * 工作目录（项目根目录）
     */
    private Path workingDirectory;
    
    /**
     * 会话 ID
     */
    private String sessionId;
    
    /**
     * 取消信号（用于中断长时间运行的操作）
     */
    @Builder.Default
    private AtomicBoolean cancelled = new AtomicBoolean(false);
    
    /**
     * 进度回调（用于报告执行进度）
     */
    private Consumer<String> progressCallback;
    
    /**
     * IDE 相关上下文（仅在 IDE 插件环境下可用）
     */
    private IdeContext ideContext;
    
    /**
     * 额外的上下文数据
     */
    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();
    
    /**
     * 检查是否已取消
     */
    public boolean isCancelled() {
        return cancelled.get();
    }
    
    /**
     * 取消执行
     */
    public void cancel() {
        cancelled.set(true);
    }
    
    /**
     * 报告进度
     */
    public void reportProgress(String message) {
        if (progressCallback != null) {
            progressCallback.accept(message);
        }
    }
    
    /**
     * 解析相对路径为绝对路径
     */
    public Path resolvePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return workingDirectory;
        }
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            return path;
        }
        return workingDirectory.resolve(path).normalize();
    }
}
