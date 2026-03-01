package com.coderfaster.agent.model;

/**
 * IDE 相关上下文接口
 * 用于在 IDE 插件环境下提供 IDE 特有的功能
 *
 * 这是一个抽象接口，具体实现由各 IDE 插件提供
 */
public interface IdeContext {

    /**
     * 获取当前打开的文件路径
     */
    String getCurrentFilePath();

    /**
     * 获取当前光标位置（行号，从 1 开始）
     */
    int getCurrentLine();

    /**
     * 获取当前光标位置（列号，从 1 开始）
     */
    int getCurrentColumn();

    /**
     * 获取当前选中的文本
     */
    String getSelectedText();

    /**
     * 在编辑器中打开文件
     */
    void openFile(String filePath);

    /**
     * 跳转到指定位置
     */
    void navigateTo(String filePath, int line, int column);

    /**
     * 显示通知消息
     */
    void showNotification(String message, NotificationType type);

    /**
     * 通知类型
     */
    enum NotificationType {
        /** 信息提示 */
        INFO,
        /** 警告提示 */
        WARNING,
        /** 错误提示 */
        ERROR
    }
}
