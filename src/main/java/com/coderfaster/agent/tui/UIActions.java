package com.coderfaster.agent.tui;

/**
 * TUI 操作接口
 * 借鉴 qwen-code 的 UIActionsContext 设计
 * 定义所有可用的 UI 操作
 */
public interface UIActions {

    /**
     * 提交用户输入
     */
    void submitInput(String input);

    /**
     * 取消当前任务
     */
    void cancelTask();

    /**
     * 确认危险操作
     */
    void confirmAction(boolean confirmed);

    /**
     * 显示帮助
     */
    void showHelp();

    /**
     * 隐藏帮助
     */
    void hideHelp();

    /**
     * 清空历史
     */
    void clearHistory();

    /**
     * 退出应用
     */
    void quit();

    /**
     * 向上滚动历史
     */
    void scrollUp();

    /**
     * 向下滚动历史
     */
    void scrollDown();

    /**
     * 滚动到顶部
     */
    void scrollToTop();

    /**
     * 滚动到底部
     */
    void scrollToBottom();

    /**
     * 切换焦点
     */
    void toggleFocus();

    /**
     * 刷新界面
     */
    void refresh();

    // ========== 会话管理操作 ==========

    /**
     * 列出会话
     */
    void listSessions();

    /**
     * 恢复会话
     */
    void resumeSession(String sessionIdOrPrefix);

    /**
     * 创建新会话
     */
    void newSession();

    /**
     * 删除会话
     */
    void deleteSession(String sessionIdOrPrefix);

    /**
     * 手动压缩会话
     */
    void compactSession(String focusHint);

    /**
     * 显示上下文统计
     */
    void showContextStats();

    /**
     * 导出会话
     */
    void exportSession(String sessionIdOrPrefix);
}
