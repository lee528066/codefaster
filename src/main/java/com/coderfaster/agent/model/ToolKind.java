package com.coderfaster.agent.model;

/**
 * Tool 类型枚举
 * 用于分类不同类型的工具，便于权限控制和 UI 展示
 * 
 * 基于 coderfaster-agent ToolKind 定义
 */
public enum ToolKind {
    /**
     * 只读操作：read_file, list_directory
     */
    READ,
    
    /**
     * 编辑操作：edit, write_file
     */
    EDIT,
    
    /**
     * 执行操作：shell, run_test
     */
    EXECUTE,
    
    /**
     * 搜索操作：grep, glob, semantic_search
     */
    SEARCH,
    
    /**
     * 思考/记忆操作：todo_write, save_memory
     */
    THINK,
    
    /**
     * 网络获取操作：web_fetch, web_search
     */
    FETCH,
    
    /**
     * IDE 特有：lsp, refactor, go_to_definition
     */
    IDE,
    
    /**
     * 其他操作：task, skill, exit_plan_mode
     */
    OTHER
}
