package com.coderfaster.agent.tool;

/**
 * 工具名称常量，用于避免循环依赖。
 * 这些常量在多个文件中使用，应与实际的工具类名保持同步。
 * 
 * 基于 coderfaster-agent tool-names 实现
 */
public final class ToolNames {
    
    private ToolNames() {
        // 防止实例化
    }
    
    // 工具内部名称（在 API 中使用）
    public static final String EDIT = "edit";
    public static final String WRITE_FILE = "write_file";
    public static final String READ_FILE = "read_file";
    public static final String GREP = "grep_search";
    public static final String GLOB = "glob";
    public static final String SHELL = "run_shell_command";
    public static final String TODO_WRITE = "todo_write";
    public static final String MEMORY = "save_memory";
    public static final String TASK = "task";
    public static final String SKILL = "skill";
    public static final String EXIT_PLAN_MODE = "exit_plan_mode";
    public static final String WEB_FETCH = "web_fetch";
    public static final String WEB_SEARCH = "web_search";
    public static final String LS = "list_directory";
    public static final String LSP = "lsp";
    
    /**
     * 工具显示名称常量。
     * 这些是在 UI 中显示的用户友好名称。
     */
    public static final class DisplayNames {
        private DisplayNames() {
            // 防止实例化
        }
        
        public static final String EDIT = "Edit";
        public static final String WRITE_FILE = "WriteFile";
        public static final String READ_FILE = "ReadFile";
        public static final String GREP = "Grep";
        public static final String GLOB = "Glob";
        public static final String SHELL = "Shell";
        public static final String TODO_WRITE = "TodoWrite";
        public static final String MEMORY = "SaveMemory";
        public static final String TASK = "Task";
        public static final String SKILL = "Skill";
        public static final String EXIT_PLAN_MODE = "ExitPlanMode";
        public static final String WEB_FETCH = "WebFetch";
        public static final String WEB_SEARCH = "WebSearch";
        public static final String LS = "ListFiles";
        public static final String LSP = "Lsp";
    }
    
    /**
     * 从旧工具名称到新工具名称的迁移映射。
     * 这些遗留工具名称在早期版本中使用，需要支持以保持与现有用户配置的向后兼容性。
     */
    public static final class Migration {
        private Migration() {
            // 防止实例化
        }
        
        // 遗留名称映射
        public static final String SEARCH_FILE_CONTENT = GREP;  // grep 工具的遗留名称
        public static final String REPLACE = EDIT;              // edit 工具的遗留名称
        
        /**
         * 获取可能是遗留名称的当前工具名称。
         * 
         * @param name 工具名称（可能是遗留名称）
         * @return 当前工具名称
         */
        public static String migrate(String name) {
            if (name == null) {
                return null;
            }
            switch (name) {
                case "search_file_content":
                    return GREP;
                case "replace":
                    return EDIT;
                default:
                    return name;
            }
        }
    }
}
