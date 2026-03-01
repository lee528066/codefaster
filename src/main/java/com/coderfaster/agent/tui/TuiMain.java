package com.coderfaster.agent.tui;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.session.SessionConfig;
import com.coderfaster.agent.session.SessionMetadata;
import com.coderfaster.agent.tui.view.MainView;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.tui.TuiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * TUI 主入口
 * 使用 TamboUI Toolkit DSL 构建声明式终端 UI
 *
 * 使用方法:
 *   coderfaster [options]
 *
 * 选项:
 *   --working-dir path    工作目录
 *   --model name          模型名称（默认：qwen3.5-plus）
 *   --auto-confirm        自动确认危险操作
 *   --debug               开启调试模式
 *   --max-iterations n    最大迭代次数（默认：50）
 *   --resume [id-prefix]  恢复会话（不带参数则恢复最近会话）
 *   --continue            恢复最近会话（--resume 的简写）
 *   --cleanup-days n      会话保留天数（默认：30，-1 表示永不清理）
 *   --help                显示帮助
 */
public class TuiMain {

    private static final Logger log = LoggerFactory.getLogger(TuiMain.class);

    public static void main(String[] args) {
        TuiAppConfig config = parseArgs(args);

        if (config.showHelp) {
            printHelp();
            return;
        }

        try {
            SessionConfig sessionConfig = SessionConfig.builder()
                    .cleanupPeriodDays(config.cleanupDays)
                    .cleanupOnStartup(true)
                    .build();

            AgentRunner agentRunner = AgentRunner.builder()
                    .workingDirectory(config.workingDirectory)
                    .modelName(config.modelName)
                    .autoConfirm(config.autoConfirm)
                    .debug(config.debug)
                    .maxIterations(config.maxIterations)
                    .sessionConfig(sessionConfig)
                    .build();

            AppController controller = new AppController(agentRunner);

            String resumedSessionId = handleSessionResume(agentRunner, controller, config);

            controller.addSystemMessage("Welcome to CodeMate Agent TUI!");
            if (resumedSessionId != null) {
                controller.addSystemMessage("Resumed session: " + resumedSessionId.substring(0, 8));
            }
            controller.addSystemMessage("Type your message and press Enter to send, or type / for commands.");

            MainView mainView = new MainView(controller);

            TuiConfig tuiConfig = TuiConfig.builder()
                    .tickRate(Duration.ofMillis(100))
                    .mouseCapture(true)
                    .build();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down TUI...");
                try {
                    controller.close();
                } catch (Exception e) {
                    log.debug("Error during shutdown (ignored)", e);
                }
            }));

            try (ToolkitRunner runner = ToolkitRunner.create(tuiConfig)) {
                AgentEventBridge bridge = new AgentEventBridge(controller, runner);
                agentRunner.setEventHandler(bridge::handleEvent);
                controller.setQuitCallback(() -> {
                    try {
                        // 先关闭 controller 资源（包括线程池）
                        controller.close();
                        // 然后退出渲染循环，让 try-with-resources 自动关闭 runner 和资源
                        runner.quit();
                    } catch (Exception e) {
                        log.error("Error during quit", e);
                    }
                });

                runner.run(mainView::render);
            }

        } catch (Exception e) {
            log.error("Failed to start TUI", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static TuiAppConfig parseArgs(String[] args) {
        TuiAppConfig config = new TuiAppConfig();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--server-url":
                    log.warn("--server-url is deprecated, direct connection mode is used by default");
                    i++;
                    break;
                case "--uid":
                    log.warn("--uid is deprecated, direct connection mode is used by default");
                    i++;
                    break;
                case "--working-dir":
                    if (i + 1 < args.length) config.workingDirectory = Path.of(args[++i]);
                    break;
                case "--model":
                    if (i + 1 < args.length) config.modelName = args[++i];
                    break;
                case "--auto-confirm":
                    config.autoConfirm = true;
                    break;
                case "--debug":
                    config.debug = true;
                    break;
                case "--max-iterations":
                    if (i + 1 < args.length) config.maxIterations = Integer.parseInt(args[++i]);
                    break;
                case "--resume":
                    config.resumeSession = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        config.resumeSessionId = args[++i];
                    }
                    break;
                case "--continue":
                case "-c":
                    config.resumeSession = true;
                    break;
                case "--cleanup-days":
                    if (i + 1 < args.length) config.cleanupDays = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                case "-h":
                    config.showHelp = true;
                    break;
                default:
                    System.err.println("Unknown option: " + arg);
                    break;
            }
        }

        if (config.workingDirectory == null) {
            config.workingDirectory = Path.of(System.getProperty("user.dir"));
        }

        return config;
    }

    /**
     * 处理会话恢复逻辑
     */
    private static String handleSessionResume(AgentRunner agentRunner, AppController controller, TuiAppConfig config) {
        if (!config.resumeSession) {
            return null;
        }

        List<SessionMetadata> sessions = agentRunner.listSessions();
        if (sessions.isEmpty()) {
            log.info("No sessions to resume, starting fresh");
            return null;
        }

        String targetSessionId = null;

        if (config.resumeSessionId != null && !config.resumeSessionId.isEmpty()) {
            List<SessionMetadata> matches = agentRunner.findSessionsByPrefix(config.resumeSessionId);
            if (matches.isEmpty()) {
                log.warn("No session found matching prefix: {}", config.resumeSessionId);
                return null;
            }
            if (matches.size() > 1) {
                log.warn("Multiple sessions match prefix '{}', using most recent", config.resumeSessionId);
            }
            targetSessionId = matches.get(0).getSessionId();
        } else {
            targetSessionId = sessions.get(0).getSessionId();
        }

        controller.setSessionId(targetSessionId);
        // 加载历史消息到 UI
        controller.loadSessionHistory(targetSessionId);
        log.info("Resuming session: {}", targetSessionId);
        return targetSessionId;
    }

    private static void printHelp() {
        System.out.println("CodeFaster Agent TUI (powered by TamboUI)");
        System.out.println();
        System.out.println("Usage: coderfaster [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --working-dir <path>     Working directory (default: current directory)");
        System.out.println("  --model <name>           Model name (default: qwen3.5-plus)");
        System.out.println("  --auto-confirm           Auto-confirm dangerous operations");
        System.out.println("  --debug                  Enable debug mode");
        System.out.println("  --max-iterations <n>     Maximum iterations (default: 50)");
        System.out.println("  --resume [id-prefix]     Resume a session (latest if no prefix given)");
        System.out.println("  --continue, -c           Resume the most recent session");
        System.out.println("  --cleanup-days <n>       Session retention days (default: 30, -1 for never)");
        System.out.println("  --help, -h               Show this help message");
        System.out.println();
        System.out.println("Keyboard Shortcuts:");
        System.out.println("  Enter           Send message / Execute command");
        System.out.println("  Ctrl+C          Cancel current task");
        System.out.println("  Ctrl+Q / q      Quit application");
        System.out.println("  Ctrl+U / Ctrl+D Scroll up/down half page");
        System.out.println("  Ctrl+T / Ctrl+B Scroll to top/bottom");
        System.out.println("  Tab             Auto-complete slash command");
        System.out.println("  Esc             Cancel input / Close dialog");
        System.out.println();
        System.out.println("Slash Commands:");
        System.out.println("  /help           Show help");
        System.out.println("  /clear          Clear chat history");
        System.out.println("  /quit           Quit application");
        System.out.println("  /cancel         Cancel current task");
        System.out.println();
        System.out.println("Session Commands:");
        System.out.println("  /sessions       List all sessions");
        System.out.println("  /resume [id]    Resume a session");
        System.out.println("  /new            Start a new session");
        System.out.println("  /delete <id>    Delete a session");
        System.out.println("  /compact [hint] Compact current session");
        System.out.println("  /context        Show context statistics");
        System.out.println("  /export [id]    Export session to Markdown");
    }

    private static class TuiAppConfig {
        Path workingDirectory;
        String modelName = "qwen3.5-plus";
        boolean autoConfirm = false;
        boolean debug = false;
        int maxIterations = 50;
        boolean showHelp = false;
        boolean resumeSession = false;
        String resumeSessionId;
        int cleanupDays = 30;
    }
}
