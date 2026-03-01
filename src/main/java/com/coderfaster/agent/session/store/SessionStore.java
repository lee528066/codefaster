package com.coderfaster.agent.session.store;

import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.session.SessionMessage;
import com.coderfaster.agent.session.SessionMetadata;
import com.coderfaster.agent.session.compaction.CompactionResult;
import com.coderfaster.agent.session.compaction.ContextStats;
import com.alibaba.dashscope.common.Message;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储接口
 * 定义会话持久化的核心操作
 */
public interface SessionStore {

    // ========== 会话生命周期 ==========

    /**
     * 创建新会话
     * 
     * @param projectPath 项目路径
     * @return 新会话 ID
     */
    String createSession(Path projectPath);

    /**
     * 追加消息到会话
     * 
     * @param sessionId 会话 ID
     * @param message 消息
     */
    void appendMessage(String sessionId, SessionMessage message);

    /**
     * 加载会话的所有消息
     * 
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<SessionMessage> loadSession(String sessionId);

    /**
     * 删除会话
     * 
     * @param sessionId 会话 ID
     * @return 是否成功删除
     */
    boolean deleteSession(String sessionId);

    /**
     * 检查会话是否存在
     * 
     * @param sessionId 会话 ID
     * @return 是否存在
     */
    boolean sessionExists(String sessionId);

    // ========== 会话查询 ==========

    /**
     * 列出项目的所有会话
     * 
     * @param projectPath 项目路径
     * @return 会话元数据列表（按最后活动时间倒序）
     */
    List<SessionMetadata> listSessions(Path projectPath);

    /**
     * 获取会话元数据
     * 
     * @param sessionId 会话 ID
     * @return 会话元数据（如果存在）
     */
    Optional<SessionMetadata> getSessionMetadata(String sessionId);

    /**
     * 根据 ID 前缀查找会话
     * 
     * @param projectPath 项目路径
     * @param idPrefix 会话 ID 前缀
     * @return 匹配的会话元数据列表
     */
    List<SessionMetadata> findSessionsByPrefix(Path projectPath, String idPrefix);

    // ========== LLM 消息转换 ==========

    /**
     * 获取用于 LLM 调用的消息列表
     * 将 SessionMessage 转换为 LLM SDK 的 Message 格式
     * 
     * @param sessionId 会话 ID
     * @return LLM 消息列表
     */
    List<Message> getLlmMessages(String sessionId);

    /**
     * 获取会话的工具定义
     * 
     * @param sessionId 会话 ID
     * @return 工具定义列表
     */
    List<ToolSchema> getSessionTools(String sessionId);

    /**
     * 设置会话的工具定义
     * 
     * @param sessionId 会话 ID
     * @param tools 工具定义列表
     */
    void setSessionTools(String sessionId, List<ToolSchema> tools);

    // ========== 会话维护 ==========

    /**
     * 清理过期会话
     * 
     * @param projectPath 项目路径
     * @param retentionDays 保留天数，-1 表示永不清理
     * @return 清理的会话数量
     */
    int cleanupExpiredSessions(Path projectPath, int retentionDays);

    /**
     * 更新会话元数据
     * 
     * @param sessionId 会话 ID
     * @param metadata 元数据
     */
    void updateMetadata(String sessionId, SessionMetadata metadata);

    // ========== 压缩相关 ==========

    /**
     * 微压缩：将工具结果落盘
     * 
     * @param sessionId 会话 ID
     * @param toolResultId 工具结果的消息 UUID
     * @param content 工具结果内容
     * @return 存储路径
     */
    String microcompact(String sessionId, String toolResultId, String content);

    /**
     * 加载已落盘的工具结果
     * 
     * @param storagePath 存储路径
     * @return 工具结果内容
     */
    Optional<String> loadOffloadedResult(String storagePath);

    /**
     * 执行自动压缩
     * 
     * @param sessionId 会话 ID
     * @param focusHint 可选的 focus hint
     * @return 压缩结果
     */
    CompactionResult autocompact(String sessionId, String focusHint);

    /**
     * 获取上下文统计信息
     * 
     * @param sessionId 会话 ID
     * @return 上下文统计
     */
    ContextStats getContextStats(String sessionId);

    // ========== 会话状态 ==========

    /**
     * 标记会话完成
     * 
     * @param sessionId 会话 ID
     * @param summary 完成摘要
     */
    void markCompleted(String sessionId, String summary);

    /**
     * 标记会话错误
     * 
     * @param sessionId 会话 ID
     * @param error 错误信息
     */
    void markError(String sessionId, String error);

    // ========== 导出 ==========

    /**
     * 导出会话为 Markdown 格式
     * 
     * @param sessionId 会话 ID
     * @return Markdown 内容
     */
    String exportToMarkdown(String sessionId);

    // ========== 资源管理 ==========

    /**
     * 关闭存储，释放资源
     */
    void close();
}
