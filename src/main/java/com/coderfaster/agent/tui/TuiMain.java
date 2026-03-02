package com.coderfaster.agent.tui;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.config.local.ConfigInitializer;
import com.coderfaster.agent.config.local.LocalConfig;
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
 */
public class TuiMain {

    private static final Logger log = LoggerFactory.getLogger(TuiMain.class);

    public static void main(String[] args) {
        TuiAppConfig config = parseArgs(args);

        if (config.showHelp) {
            printHelp();
            return;
        }

        // ACP 模式：启动 ACP 服务器
        if (config.acpMode) {
            startAcpMode(config);
            return;
        }

        try {
            startTuiMode(config);
        } catch (Exception e) {
            log.error("Failed to start TUI", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 启动 ACP 模式
     */
    private static void startAcpMode(TuiAppConfig config) {
        try {
            // 检查并初始化本地配置
            System.out.println("正在检查配置...");
            LocalConfig localConfig = ConfigInitializer.initialize(true);
            System.out.println("✓ 配置已加载：" + LocalConfig.getConfigPath());
            System.out.println("  账号类型：" + localConfig.getAuthType());
            System.out.println("  模型：" + localConfig.getModelName());
            System.out.println();
            System.out.println("Starting ACP mode...");

            // 使用本地配置创建 AgentConfig
            AgentConfig agentConfig = AgentConfig.builder()
                    .apiKey(localConfig.getApiKey())
                    .modelName(localConfig.getModelName())
                    .baseUrl(localConfig.getEffectiveBaseUrl())
                    .authType(localConfig.getAuthType())
                    .workingDirectory(config.workingDirectory)
                    .autoConfirm(true) // ACP 模式默认自动确认
                    .debug(config.debug)
                    .maxIterations(config.maxIterations)
                    .build();

            SessionConfig sessionConfig = SessionConfig.builder()
                    .cleanupPeriodDays(config.cleanupDays)
                    .cleanupOnStartup(true)
                    .build();

            AgentRunner agentRunner = AgentRunner.builder(agentConfig)
                    .sessionConfig(sessionConfig)
                    .build();

            com.coderfaster.agent.acp.AcpMain.startWithAgentRunner(agentRunner);

        } catch (Exception e) {
            log.error("Failed to start ACP mode", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * 启动 TUI 模式
     */
    private static void startTuiMode(TuiAppConfig config) throws Exception {
        // 检查并初始化本地配置
        System.out.println("正在检查配置...");
        LocalConfig localConfig = ConfigInitializer.initialize(true);
        System.out.println("✓ 配置已加载：" + LocalConfig.getConfigPath());
        System.out.println("  账号类型：" + localConfig.getAuthType());
        System.out.println("  模型：" + localConfig.getModelName());
        System.out.println();

        // 使用本地配置创建 AgentConfig
        AgentConfig agentConfig = AgentConfig.builder()
                .apiKey(localConfig.getApiKey())
                .modelName(localConfig.getModelName())
                .baseUrl(localConfig.getEffectiveBaseUrl())
                .authType(localConfig.getAuthType())
                .workingDirectory(config.workingDirectory)
                .autoConfirm(config.autoConfirm)
                .debug(config.debug)
                .maxIterations(config.maxIterations)
                .build();

        SessionConfig sessionConfig = SessionConfig.builder()
                .cleanupPeriodDays(config.cleanupDays)
                .cleanupOnStartup(true)
                .build();

        AgentRunner agentRunner = AgentRunner.builder(agentConfig)
                .sessionConfig(sessionConfig)
                .build();

        AppController controller = new AppController(agentRunner);

        String resumedSessionId = handleSessionResume(agentRunner, controller, config);

        controller.addSystemMessage("Welcome to CodeFaster Agent TUI!");
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
                    controller.close();
                    runner.quit();
                } catch (Exception e) {
                    log.error("Error during quit", e);
                }
            });

            runner.run(mainView::render);
        }
    }

    private static TuiAppConfig parseArgs(String[] args) {
        TuiAppConfig config = new TuiAppConfig();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--acp":
                    config.acpMode = true;
                    break;
                case "--server-url":
                    log.warn("--server-url is deprecated");
                    i++;
                    break;
                case "--uid":
                    log.warn("--uid is deprecated");
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
        controller.loadSessionHistory(targetSessionId);
        log.info("Resuming session: {}", targetSessionId);
        return targetSessionId;
    }

    private static void printHelp() {
        System.out.println("CodeFaster Agent TUI");
        System.out.println();
        System.out.println("Usage: coderfaster [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --acp                    Start in ACP mode");
        System.out.println("  --working-dir <path>     Working directory");
        System.out.println("  --model <name>           Model name");
        System.out.println("  --auto-confirm           Auto-confirm operations");
        System.out.println("  --debug                  Debug mode");
        System.out.println("  --max-iterations <n>     Max iterations");
        System.out.println("  --resume [id]            Resume session");
        System.out.println("  --continue, -c           Resume latest session");
        System.out.println("  --cleanup-days <n>       Session retention days");
        System.out.println("  --help, -h               Show help");
    }

    private static class TuiAppConfig {
        Path workingDirectory;
        String modelName = "qwen3.5-plus";
        boolean autoConfirm = false;
        boolean debug = false;
        int maxIterations = 50;
        boolean showHelp = false;
        boolean acpMode = false;
        boolean resumeSession = false;
        String resumeSessionId;
        int cleanupDays = 30;
    }
}
