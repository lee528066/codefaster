package com.coderfaster.agent.tui.command;

import lombok.Builder;
import lombok.Data;

import java.util.function.Consumer;

/**
 * 斜杠命令定义
 * 类似 qwen-code 的斜杠命令系统
 */
@Data
@Builder
public class SlashCommand {
    
    /**
     * 命令名称（不含斜杠）
     */
    private String name;
    
    /**
     * 命令别名
     */
    private String[] aliases;
    
    /**
     * 命令描述
     */
    private String description;
    
    /**
     * 命令用法
     */
    private String usage;
    
    /**
     * 是否需要参数
     */
    @Builder.Default
    private boolean requiresArgs = false;
    
    /**
     * 是否需要用户输入参数（选中命令后不立即执行，而是等待用户输入）
     */
    @Builder.Default
    private boolean requiresInput = false;
    
    /**
     * 输入提示（当 requiresInput 为 true 时显示）
     */
    private String inputHint;
    
    /**
     * 命令执行器
     */
    private Consumer<String> executor;
    
    /**
     * 检查命令是否匹配
     */
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }
        String cmd = input.toLowerCase().trim();
        if (cmd.equals(name.toLowerCase())) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (cmd.equals(alias.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 检查输入是否以此命令开头
     */
    public boolean startsWith(String input) {
        if (input == null) {
            return false;
        }
        String cmd = input.toLowerCase().trim();
        if (cmd.startsWith(name.toLowerCase())) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (cmd.startsWith(alias.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 执行命令
     */
    public void execute(String args) {
        if (executor != null) {
            executor.accept(args);
        }
    }
}
