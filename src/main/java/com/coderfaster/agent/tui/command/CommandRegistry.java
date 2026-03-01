package com.coderfaster.agent.tui.command;

import com.coderfaster.agent.tui.UIActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 斜杠命令注册中心
 * 管理所有可用的斜杠命令
 */
public class CommandRegistry {

    private final List<SlashCommand> commands = new ArrayList<>();
    private final UIActions actions;

    public CommandRegistry(UIActions actions) {
        this.actions = actions;
        registerBuiltinCommands();
    }

    /**
     * 注册内置命令
     */
    private void registerBuiltinCommands() {
        // /help - 显示帮助
        register(SlashCommand.builder()
                .name("help")
                .aliases(new String[]{"h", "?"})
                .description("Show help information")
                .usage("/help")
                .executor(args -> actions.showHelp())
                .build());

        // /clear - 清空历史
        register(SlashCommand.builder()
                .name("clear")
                .aliases(new String[]{"cls", "c"})
                .description("Clear chat history")
                .usage("/clear")
                .executor(args -> actions.clearHistory())
                .build());

        // /quit - 退出
        register(SlashCommand.builder()
                .name("quit")
                .aliases(new String[]{"exit", "q"})
                .description("Quit the application")
                .usage("/quit")
                .executor(args -> actions.quit())
                .build());

        // /cancel - 取消当前任务
        register(SlashCommand.builder()
                .name("cancel")
                .aliases(new String[]{"stop"})
                .description("Cancel current task")
                .usage("/cancel")
                .executor(args -> actions.cancelTask())
                .build());

        // /refresh - 刷新界面
        register(SlashCommand.builder()
                .name("refresh")
                .aliases(new String[]{"r"})
                .description("Refresh the screen")
                .usage("/refresh")
                .executor(args -> actions.refresh())
                .build());

        // ========== 会话管理命令 ==========

        // /sessions - 列出会话
        register(SlashCommand.builder()
                .name("sessions")
                .aliases(new String[]{"ls"})
                .description("List all sessions for current project")
                .usage("/sessions")
                .executor(args -> actions.listSessions())
                .build());

        // /resume - 恢复会话
        register(SlashCommand.builder()
                .name("resume")
                .aliases(new String[]{})
                .description("Resume a previous session")
                .usage("/resume [session-id-prefix]")
                .requiresInput(true)
                .inputHint("Enter session ID prefix")
                .executor(args -> actions.resumeSession(args.trim()))
                .build());

        // /new - 创建新会话
        register(SlashCommand.builder()
                .name("new")
                .aliases(new String[]{"n"})
                .description("Start a new session")
                .usage("/new")
                .executor(args -> actions.newSession())
                .build());

        // /delete - 删除会话
        register(SlashCommand.builder()
                .name("delete")
                .aliases(new String[]{"del"})
                .description("Delete a session")
                .usage("/delete <session-id-prefix>")
                .requiresInput(true)
                .inputHint("Enter session ID prefix to delete")
                .executor(args -> actions.deleteSession(args.trim()))
                .build());

        // /compact - 手动压缩
        register(SlashCommand.builder()
                .name("compact")
                .aliases(new String[]{})
                .description("Manually compact current session")
                .usage("/compact [focus-hint]")
                .executor(args -> actions.compactSession(args.isEmpty() ? null : args.trim()))
                .build());

        // /context - 显示上下文统计
        register(SlashCommand.builder()
                .name("context")
                .aliases(new String[]{"ctx"})
                .description("Show context usage statistics")
                .usage("/context")
                .executor(args -> actions.showContextStats())
                .build());

        // /export - 导出会话
        register(SlashCommand.builder()
                .name("export")
                .aliases(new String[]{})
                .description("Export session to Markdown")
                .usage("/export [session-id-prefix]")
                .requiresInput(true)
                .inputHint("Enter session ID prefix (or leave empty for current)")
                .executor(args -> actions.exportSession(args.trim()))
                .build());
    }

    /**
     * 注册命令
     */
    public void register(SlashCommand command) {
        commands.add(command);
    }

    /**
     * 获取所有命令
     */
    public List<SlashCommand> getAllCommands() {
        return new ArrayList<>(commands);
    }

    /**
     * 查找匹配的命令
     */
    public Optional<SlashCommand> findCommand(String name) {
        return commands.stream()
                .filter(cmd -> cmd.matches(name))
                .findFirst();
    }

    /**
     * 获取命令建议（用于自动补全）
     */
    public List<SlashCommand> getSuggestions(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(commands);
        }
        
        String lowerPrefix = prefix.toLowerCase();
        List<SlashCommand> suggestions = new ArrayList<>();
        
        for (SlashCommand cmd : commands) {
            if (cmd.getName().toLowerCase().startsWith(lowerPrefix)) {
                suggestions.add(cmd);
                continue;
            }
            if (cmd.getAliases() != null) {
                for (String alias : cmd.getAliases()) {
                    if (alias.toLowerCase().startsWith(lowerPrefix)) {
                        suggestions.add(cmd);
                        break;
                    }
                }
            }
        }
        
        return suggestions;
    }

    /**
     * 检查输入是否为斜杠命令
     */
    public boolean isCommand(String input) {
        return input != null && input.startsWith("/");
    }

    /**
     * 解析并执行命令
     * @return true 如果成功执行了命令
     */
    public boolean parseAndExecute(String input) {
        if (!isCommand(input)) {
            return false;
        }

        String commandLine = input.substring(1).trim();
        if (commandLine.isEmpty()) {
            return false;
        }

        String[] parts = commandLine.split("\\s+", 2);
        String commandName = parts[0];
        String args = parts.length > 1 ? parts[1] : "";

        Optional<SlashCommand> command = findCommand(commandName);
        if (command.isPresent()) {
            command.get().execute(args);
            return true;
        }

        return false;
    }

    /**
     * 获取帮助文本
     */
    public String getHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Available Commands:\n\n");

        for (SlashCommand cmd : commands) {
            sb.append("  /").append(cmd.getName());
            if (cmd.getAliases() != null && cmd.getAliases().length > 0) {
                sb.append(" (");
                sb.append(String.join(", ", cmd.getAliases()));
                sb.append(")");
            }
            sb.append("\n");
            sb.append("    ").append(cmd.getDescription()).append("\n");
        }

        return sb.toString();
    }
}
