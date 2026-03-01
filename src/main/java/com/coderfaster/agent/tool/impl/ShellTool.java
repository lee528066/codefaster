package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.PathResolver;
import com.coderfaster.agent.tool.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shell 工具 - 在持久化的 Shell 会话中执行命令，支持可选的超时设置。
 * <p>
 * 基于 coderfaster-agent shell 实现
 * <p>
 * 重要提示：此工具用于终端操作，如 git、npm、docker 等。
 * 不要将其用于文件操作（读取、写入、编辑、搜索、查找文件）-
 * 请使用专门的工具来完成这些操作。
 */
public class ShellTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ShellTool.class);

    /**
     * 默认前台执行超时时间（毫秒）（2 分钟）
     */
    private static final long DEFAULT_FOREGROUND_TIMEOUT_MS = 120000;

    /**
     * 最大允许超时时间（10 分钟）
     */
    private static final long MAX_TIMEOUT_MS = 600000;

    /**
     * 最大输出捕获大小
     */
    private static final int MAX_OUTPUT_SIZE = 100000;

    /**
     * 出于安全原因被禁止的命令
     */
    private static final Set<String> BLOCKED_COMMANDS = new HashSet<>(Arrays.asList(
            "rm -rf /", "rm -rf /*", "dd if=", "mkfs", ":(){ :|:& };:", "fork bomb"
    ));

    /**
     * 危险的命令模式
     */
    private static final List<String> DANGEROUS_PATTERNS = Arrays.asList(
            "rm\\s+-rf\\s+/(?!tmp)",
            "chmod\\s+-R\\s+777\\s+/",
            ">\\s*/dev/sd",
            "mkfs\\.",
            "dd\\s+if="
    );

    /**
     * 用于捕获进程输出的共享线程池
     */
    private static final ExecutorService OUTPUT_CAPTURE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(1024),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private static final String DESCRIPTION = buildDescription();

    private static String buildDescription() {
        String osSpecific;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

        if (isWindows) {
            osSpecific = "cmd.exe /c <command>";
        } else {
            osSpecific = "bash -c <command>";
        }

        return "Executes a given shell command (as `" + osSpecific + "`) in a persistent shell session " +
                "with optional timeout, ensuring proper handling and security measures.\n\n" +
                "IMPORTANT: This tool is for terminal operations like git, npm, docker, etc. " +
                "DO NOT use it for file operations (reading, writing, editing, searching, finding files) - " +
                "use the specialized tools for this instead.\n\n" +
                "**Usage notes**:\n" +
                "- The command argument is required.\n" +
                "- You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). " +
                "If not specified, commands will timeout after 120000ms (2 minutes).\n" +
                "- It is very helpful if you write a clear, concise description of what this command does in 5-10 "
                + "words.\n\n" +
                "- Avoid using run_shell_command with the `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, or "
                + "`echo` commands, " +
                "unless explicitly instructed or when these commands are truly necessary for the task. Instead, "
                + "always prefer using the dedicated tools:\n" +
                "  - File search: Use glob (NOT find or ls)\n" +
                "  - Content search: Use grep_search (NOT grep or rg)\n" +
                "  - Read files: Use read_file (NOT cat/head/tail)\n" +
                "  - Edit files: Use edit (NOT sed/awk)\n" +
                "  - Write files: Use write_file (NOT echo >/cat <)";
    }

    @Override
    public ToolSchema getSchema() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
        String commandDescription = isWindows
                ? "Exact command to execute as `cmd.exe /c <command>`"
                : "Exact bash command to execute as `bash -c <command>`";
        commandDescription += " Command substitution ($() and ``) is not allowed for security reasons.";

        JsonNode parameters = new JsonSchemaBuilder()
                .addProperty("command", "string", commandDescription)
                .addProperty("is_background", "boolean",
                        "Optional: Whether to run the command in background. If not specified, defaults to false " +
                                "(foreground execution). Explicitly set to true for long-running processes like "
                                + "development " +
                                "servers, watchers, or daemons that should continue running without blocking further "
                                + "commands.")
                .addProperty("timeout", "number",
                        "Optional timeout in milliseconds (max 600000)")
                .addProperty("description", "string",
                        "Brief description of the command for the user. Be specific and concise. " +
                                "Ideally a single sentence. Can be up to 3 sentences for clarity. No line breaks.")
                .addProperty("directory", "string",
                        "(OPTIONAL) The absolute path of the directory to run the command in. " +
                                "If not provided, the project root directory is used. " +
                                "Must be a directory within the workspace and must already exist.")
                .setRequired(Arrays.asList("command"))
                .build();

        return new ToolSchema(
                ToolNames.SHELL,
                DESCRIPTION,
                ToolKind.EXECUTE,
                parameters,
                "1.0.0"
        );
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String command = params.path("command").asText("");
        boolean isBackground = params.has("is_background") && params.path("is_background").asBoolean(false);
        Long timeout = params.has("timeout") && !params.path("timeout").isNull()
                ? params.path("timeout").asLong() : null;
        String description = params.has("description") ? params.path("description").asText("") : null;
        String directory = params.has("directory") && !params.path("directory").isNull()
                ? params.path("directory").asText("") : null;

        logger.debug("Shell: command={}, isBackground={}, timeout={}, directory={}",
                command, isBackground, timeout, directory);

        // 验证命令
        if (command == null || command.trim().isEmpty()) {
            return ToolResult.failure("Command cannot be empty.");
        }

        // 安全检查
        String securityError = checkCommandSecurity(command);
        if (securityError != null) {
            return ToolResult.failure(securityError);
        }

        // 验证超时时间
        if (timeout != null) {
            if (timeout <= 0) {
                return ToolResult.failure("Timeout must be a positive number.");
            }
            if (timeout > MAX_TIMEOUT_MS) {
                return ToolResult.failure("Timeout cannot exceed " + MAX_TIMEOUT_MS + "ms (10 minutes).");
            }
        }

        // 确定工作目录
        Path workDir;
        if (directory != null && !directory.isEmpty()) {
            // 解析路径（支持相对路径）
            Path dirPath = PathResolver.resolve(directory, context);
            if (!Files.exists(dirPath)) {
                return ToolResult.failure("Directory does not exist: " + dirPath);
            }
            if (!Files.isDirectory(dirPath)) {
                return ToolResult.failure("Path is not a directory: " + dirPath);
            }
            workDir = dirPath;
        } else {
            workDir = PathResolver.getWorkingDirectory(context);
        }

        // 检查是否取消
        if (context != null && context.isCancelled()) {
            return ToolResult.failure("Command was cancelled by user before it could start.");
        }

        // 执行命令
        long effectiveTimeout = isBackground ? 0 : (timeout != null ? timeout : DEFAULT_FOREGROUND_TIMEOUT_MS);

        try {
            return executeCommand(command, workDir, isBackground, effectiveTimeout, context);
        } catch (Exception e) {
            logger.error("Error executing command: {}", command, e);
            return ToolResult.failure("Error executing command: " + e.getMessage());
        }
    }

    /**
     * 检查命令的安全性问题。
     */
    private String checkCommandSecurity(String command) {
        // 检查被禁止的命令
        String lowerCommand = command.toLowerCase().trim();
        for (String blocked : BLOCKED_COMMANDS) {
            if (lowerCommand.contains(blocked.toLowerCase())) {
                return "Command is not allowed for security reasons: contains blocked pattern.";
            }
        }

        // 检查危险模式
        for (String pattern : DANGEROUS_PATTERNS) {
            if (command.matches(".*" + pattern + ".*")) {
                return "Command contains potentially dangerous pattern and requires manual execution.";
            }
        }

        // 检查命令替换
        if (command.contains("$(") || command.matches(".*`[^`]+`.*")) {
            // 允许简单情况，但对嵌套替换发出警告
            if (command.contains("$($(") || command.contains("`") && command.indexOf("`") != command.lastIndexOf("`")) {
                // This is allowed in coderfaster-agent, just a security note
                logger.debug("Command contains command substitution: {}", command);
            }
        }

        return null;
    }

    /**
     * 执行 Shell 命令。
     */
    private ToolResult executeCommand(String command, Path workDir, boolean isBackground,
                                      long timeoutMs, ExecutionContext context) throws IOException,
            InterruptedException {

        boolean isWindows = isWindowsSystem();
        Process process = startProcess(command, workDir, isWindows);
        long pid = getProcessId(process);

        if (isBackground) {
            return handleBackgroundCommand(pid, isWindows);
        }

        return executeForegroundCommand(process, command, workDir, timeoutMs, context, pid);
    }

    /**
     * 检查当前系统是否为 Windows。
     *
     * @return 如果是 Windows 返回 true，否则返回 false
     */
    private boolean isWindowsSystem() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 使用给定的命令启动新进程。
     *
     * @param command 要执行的命令
     * @param workDir 工作目录
     * @param isWindows 系统是否为 Windows
     * @return 已启动的进程
     * @throws IOException 如果进程创建失败
     */
    private Process startProcess(String command, Path workDir, boolean isWindows) throws IOException {
        ProcessBuilder processBuilder = createProcessBuilder(command, isWindows);
        processBuilder.directory(workDir.toFile());
        processBuilder.redirectErrorStream(true);

        Map<String, String> env = processBuilder.environment();
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");

        return processBuilder.start();
    }

    /**
     * 为给定的命令创建 ProcessBuilder。
     *
     * @param command 要执行的命令
     * @param isWindows 系统是否为 Windows
     * @return 配置好的 ProcessBuilder
     */
    private ProcessBuilder createProcessBuilder(String command, boolean isWindows) {
        if (isWindows) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            return new ProcessBuilder("bash", "-c", command);
        }
    }

    /**
     * 处理后台命令执行。
     *
     * @param pid 进程 ID
     * @param isWindows 系统是否为 Windows
     * @return 工具结果
     */
    private ToolResult handleBackgroundCommand(long pid, boolean isWindows) {
        String pidMsg = pid > 0 ? " PID: " + pid : "";
        String killHint = isWindows
                ? " (Use taskkill /F /T /PID to stop)"
                : " (Use kill to stop)";

        return ToolResult.success("Background command started." + pidMsg + killHint);
    }

    /**
     * 执行前台命令并等待完成。
     *
     * @param process 要执行的进程
     * @param command 命令字符串
     * @param workDir 工作目录
     * @param timeoutMs 超时时间（毫秒）
     * @param context 执行上下文
     * @param pid 进程 ID
     * @return 工具结果
     * @throws InterruptedException 如果被中断
     */
    private ToolResult executeForegroundCommand(Process process, String command, Path workDir,
                                                long timeoutMs, ExecutionContext context, long pid)
            throws InterruptedException {

        StringBuilder outputBuilder = new StringBuilder();
        Future<String> outputFuture = captureProcessOutput(process, outputBuilder, OUTPUT_CAPTURE_EXECUTOR);
        ProcessExecutionResult execResult = waitForProcessCompletion(process, timeoutMs, outputFuture);

        String output = outputBuilder.toString();

        if (execResult.timedOut) {
            return buildTimeoutResult(timeoutMs, output);
        }

        if (context != null && context.isCancelled()) {
            return buildCancelledResult(output);
        }

        return buildCommandResult(command, workDir, output, execResult.exitCode, pid);
    }

    /**
     * 异步捕获进程输出。
     *
     * @param process 进程
     * @param outputBuilder 输出构建器
     * @param executor 执行器服务
     * @return 包含输出的 Future
     */
    private Future<String> captureProcessOutput(Process process, StringBuilder outputBuilder,
                                                ExecutorService executor) {
        return executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int totalChars = 0;
                while ((line = reader.readLine()) != null) {
                    if (totalChars < MAX_OUTPUT_SIZE) {
                        if (outputBuilder.length() > 0) {
                            outputBuilder.append("\n");
                        }
                        outputBuilder.append(line);
                        totalChars += line.length() + 1;
                    }
                }
            }
            return outputBuilder.toString();
        });
    }

    /**
     * 等待进程完成，支持超时。
     *
     * @param process 进程
     * @param timeoutMs 超时时间（毫秒）
     * @param outputFuture 输出捕获的 Future
     * @return 进程执行结果
     * @throws InterruptedException 如果被中断
     */
    private ProcessExecutionResult waitForProcessCompletion(Process process, long timeoutMs,
                                                            Future<String> outputFuture)
            throws InterruptedException {

        boolean timedOut = false;
        int exitCode = -1;

        try {
            if (timeoutMs > 0) {
                boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finished) {
                    timedOut = true;
                    process.destroyForcibly();
                }
            } else {
                process.waitFor();
            }

            if (!timedOut) {
                exitCode = process.exitValue();
            }

            try {
                outputFuture.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                outputFuture.cancel(true);
            } catch (ExecutionException e) {
                logger.warn("Error capturing output: {}", e.getMessage());
            }

        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        return new ProcessExecutionResult(timedOut, exitCode);
    }

    /**
     * 构建超时结果。
     *
     * @param timeoutMs 超时值
     * @param output 捕获的输出
     * @return 工具结果
     */
    private ToolResult buildTimeoutResult(long timeoutMs, String output) {
        String content = "Command timed out after " + timeoutMs + "ms before it could complete.";
        if (!output.trim().isEmpty()) {
            content += " Below is the output before it timed out:\n" + output;
        } else {
            content += " There was no output before it timed out.";
        }
        return ToolResult.failure(content);
    }

    /**
     * 构建取消结果。
     *
     * @param output 捕获的输出
     * @return 工具结果
     */
    private ToolResult buildCancelledResult(String output) {
        String content = "Command was cancelled by user before it could complete.";
        if (!output.trim().isEmpty()) {
            content += " Below is the output before it was cancelled:\n" + output;
        }
        return ToolResult.success(content);
    }

    /**
     * 构建命令执行结果。
     *
     * @param command 命令字符串
     * @param workDir 工作目录
     * @param output 命令输出
     * @param exitCode 退出码
     * @param pid 进程 ID
     * @return 工具结果
     */
    private ToolResult buildCommandResult(String command, Path workDir, String output,
                                         int exitCode, long pid) {
        StringBuilder llmContent = new StringBuilder();
        llmContent.append("Command: ").append(command).append("\n");
        llmContent.append("Directory: ").append(workDir.toAbsolutePath()).append("\n");
        llmContent.append("Output: ").append(output.isEmpty() ? "(empty)" : output).append("\n");
        llmContent.append("Exit Code: ").append(exitCode).append("\n");

        if (pid > 0) {
            llmContent.append("Process PID: ").append(pid).append("\n");
        }

        String displayResult = output.isEmpty()
                ? (exitCode == 0 ? "Command completed successfully." : "Command exited with code: " + exitCode)
                : output;

        return ToolResult.builder()
                .success(exitCode == 0)
                .content(llmContent.toString())
                .displayData(displayResult)
                .error(exitCode != 0 ? "Command exited with code: " + exitCode : null)
                .build();
    }

    /**
     * 进程执行结果持有者。
     */
    private static class ProcessExecutionResult {
        final boolean timedOut;
        final int exitCode;

        ProcessExecutionResult(boolean timedOut, int exitCode) {
            this.timedOut = timedOut;
            this.exitCode = exitCode;
        }
    }

    /**
     * 获取进程 ID（尽力而为）。
     */
    private long getProcessId(Process process) {
        try {
            // Java 9+ 版本
            return process.pid();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public String validateParams(JsonNode params) {
        String command = params.path("command").asText("");
        if (command.trim().isEmpty()) {
            return "The 'command' parameter cannot be empty.";
        }

        if (params.has("timeout") && !params.path("timeout").isNull()) {
            long timeout = params.path("timeout").asLong();
            if (timeout <= 0) {
                return "Timeout must be a positive number.";
            }
            if (timeout > MAX_TIMEOUT_MS) {
                return "Timeout cannot exceed " + MAX_TIMEOUT_MS + "ms (10 minutes).";
            }
        }

        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        String command = params.path("command").asText("").toLowerCase();

        // 通常需要确认的命令
        if (command.contains("rm ") || command.contains("delete") ||
                command.contains("sudo") || command.contains("chmod") ||
                command.contains("chown") || command.contains("mv ") ||
                command.contains("git push") || command.contains("git reset") ||
                command.contains("npm publish") || command.contains("yarn publish")) {
            return true;
        }

        return true; // 默认 Shell 命令需要确认
    }
}
