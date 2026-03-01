package com.coderfaster.agent.core;

import com.coderfaster.agent.config.AgentConfig;
import com.coderfaster.agent.http.AgentHttpException;
import com.coderfaster.agent.http.AgentResponse;
import com.coderfaster.agent.http.AgentServerClient;
import com.coderfaster.agent.http.ChatRequest;
import com.coderfaster.agent.http.ToolResultRequest;
import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.TokenUsage;
import com.coderfaster.agent.model.ToolCall;
import com.coderfaster.agent.model.ToolCallResult;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Agent Loop Controller
 * 负责驱动 ReAct 循环的核心组件
 * <p>
 * ReAct 循环：
 * 1. Reason：调用 Server 获取 LLM 响应
 * 2. Act：本地执行 Tool
 * 3. Observe：收集结果，更新上下文
 * 4. 重复直到完成或达到最大循环次数
 */
public class AgentLoopController {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopController.class);

    private final AgentConfig config;
    private final AgentServerClient serverClient;
    private final ToolRegistry toolRegistry;

    /**
     * Tool 执行前的确认回调
     * 返回 true 允许执行，返回 false 拒绝执行
     */
    private ConfirmationHandler confirmationHandler;

    /**
     * 消息接收回调
     */
    private Consumer<AgentEvent> eventHandler;

    /**
     * 工具并发执行的线程池
     */
    private final ExecutorService toolExecutor;

    public AgentLoopController(AgentConfig config, AgentServerClient serverClient, ToolRegistry toolRegistry) {
        this.config = config;
        this.serverClient = serverClient;
        this.toolRegistry = toolRegistry;

        // 初始化线程池
        this.toolExecutor = new ThreadPoolExecutor(
                config.getMaxConcurrentTools(),
                config.getMaxConcurrentTools(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(config.getMaxQueueSize()),
                new ThreadFactory() {
                    private int counter = 0;
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "tool-executor-" + counter++);
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                // 当队列满时，在调用线程中执行（CallerRunsPolicy）
                // 这样可以自然地进行背压控制，避免任务丢失
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 设置确认处理器
     */
    public void setConfirmationHandler(ConfirmationHandler handler) {
        this.confirmationHandler = handler;
    }

    /**
     * 设置事件处理器
     */
    public void setEventHandler(Consumer<AgentEvent> handler) {
        this.eventHandler = handler;
    }

    /**
     * 执行 Agent 任务
     *
     * @param userMessage 用户消息
     * @return 最终结果
     */
    public AgentResult run(String userMessage) {
        return run(userMessage, null);
    }

    /**
     * 执行 Agent 任务（恢复会话）
     *
     * @param userMessage 用户消息
     * @param sessionId 会话 ID（用于恢复会话）
     * @return 最终结果
     */
    public AgentResult run(String userMessage, String sessionId) {
        // 输入验证
        if (userMessage == null) {
            return AgentResult.error("User message cannot be null");
        }
        if (userMessage.trim().isEmpty()) {
            return AgentResult.error("User message cannot be empty or contain only whitespace");
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        TokenUsage totalTokenUsage = TokenUsage.empty();

        // 初始化会话上下文
        ExecutionContext context = initializeSessionContext(sessionId, cancelled);
        String currentSessionId = sessionId;

        emitEvent(AgentEvent.started(userMessage));

        try {
            // 处理初始聊天请求
            AgentResponse response = handleInitialChatRequest(userMessage, sessionId, totalTokenUsage);
            if (response.isError()) {
                return AgentResult.error(response.getErrorMessage());
            }

            // 累加初始请求的token usage
            if (response.getTokenUsage() != null) {
                totalTokenUsage = totalTokenUsage.add(response.getTokenUsage());
            }

            // 更新会话ID
            if (currentSessionId == null && response.getSessionId() != null) {
                currentSessionId = response.getSessionId();
                context.setSessionId(currentSessionId);
            }

            // 执行ReAct循环
            return executeReactLoop(context, response, currentSessionId, cancelled, totalTokenUsage);
        } catch (Exception e) {
            log.error("Unexpected error in ReAct loop", e);
            emitEvent(AgentEvent.error(e.getMessage()));
            return AgentResult.error("Unexpected error: " + e.getMessage());
        }
    }

    /**
     * 初始化会话上下文
     */
    private ExecutionContext initializeSessionContext(String sessionId, AtomicBoolean cancelled) {
        return ExecutionContext.builder()
                .workingDirectory(config.getWorkingDirectory())
                .sessionId(sessionId)
                .cancelled(cancelled)
                .progressCallback(this::emitProgress)
                .build();
    }

    /**
     * 处理初始聊天请求
     */
    private AgentResponse handleInitialChatRequest(String userMessage, String sessionId, TokenUsage totalTokenUsage) {
        ChatRequest chatRequest = ChatRequest.builder()
                .sessionId(sessionId)
                .message(userMessage)
                .tools(toolRegistry.getAllSchemas())
                .uid(config.getUid())
                .clientType(config.getClientType())
                .clientVersion(config.getClientVersion())
                .build();

        try {
            return serverClient.chat(chatRequest);
        } catch (AgentHttpException e) {
            log.error("Failed to communicate with server", e);
            // 返回错误响应而不是抛出异常，以便统一处理
            return AgentResponse.error(sessionId, "SERVER_COMMUNICATION_ERROR", "Server communication error: " + e.getMessage());
        }
    }

    /**
     * 执行ReAct循环
     */
    private AgentResult executeReactLoop(ExecutionContext context, AgentResponse initialResponse,
                                      String currentSessionId, AtomicBoolean cancelled, TokenUsage totalTokenUsage) {
        AgentResponse response = initialResponse;
        int iteration = 0;
        List<String> messages = new ArrayList<>();

        while (iteration < config.getMaxIterations() && !cancelled.get()) {
            iteration++;
            TokenUsage iterationTokenUsage = TokenUsage.empty();

            log.info("ReAct iteration {} started", iteration);
            emitEvent(AgentEvent.iterationStart(iteration));

            if (response.isError()) {
                log.error("Server returned error: {} - {}",
                        response.getErrorCode(), response.getErrorMessage());
                return AgentResult.error(response.getErrorMessage());
            }

            if (response.getMessage() != null && !response.getMessage().isEmpty()) {
                messages.add(response.getMessage());
                emitEvent(AgentEvent.message(response.getMessage()));
            }

            if (response.isFinalResponse() || !response.hasToolCalls()) {
                log.info("ReAct loop completed after {} iterations", iteration);
                emitEvent(AgentEvent.completed(String.join("\n", messages)));
                return AgentResult.success(currentSessionId, String.join("\n", messages));
            }

            List<ToolCallResult> toolResults = executeToolCalls(response.getToolCalls(), context);

            ToolResultRequest toolResultRequest = ToolResultRequest.builder()
                    .sessionId(currentSessionId)
                    .results(toolResults)
                    .build();

            try {
                response = serverClient.submitToolResult(toolResultRequest);
            } catch (AgentHttpException e) {
                log.error("Failed to submit tool results", e);
                return AgentResult.error("Failed to submit tool results: " + e.getMessage());
            }

            if (response.getTokenUsage() != null) {
                iterationTokenUsage = iterationTokenUsage.add(response.getTokenUsage());
                totalTokenUsage = totalTokenUsage.add(response.getTokenUsage());
            }

            emitEvent(AgentEvent.iterationEnd(iteration));
        }

        // 处理循环结束的原因
        if (iteration >= config.getMaxIterations()) {
            return handleMaxIterationsReached(iteration, totalTokenUsage);
        } else {
            return AgentResult.cancelled();
        }
    }

    /**
     * 处理达到最大迭代次数的情况
     */
    private AgentResult handleMaxIterationsReached(int iteration, TokenUsage totalTokenUsage) {
        log.warn("ReAct loop reached maximum iterations: {}", config.getMaxIterations());
        return AgentResult.error("Maximum iterations reached (" + config.getMaxIterations() + ")");
    }

    /**
     * 执行 Tool 调用列表（支持并发执行）
     */
    private List<ToolCallResult> executeToolCalls(List<ToolCall> toolCalls, ExecutionContext context) {
        if (CollectionUtils.isEmpty(toolCalls)) {
            return Lists.newArrayList();
        }

        // 如果禁用并发或只有一个工具，使用串行执行
        if (!config.isEnableParallelToolExecution() || toolCalls.size() == 1) {
            return executeToolCallsSequentially(toolCalls, context);
        }

        // 分类：可并发执行的工具 vs 必须串行执行的工具
        List<ToolCall> parallelTools = Lists.newArrayList();
        List<ToolCall> serialTools = Lists.newArrayList();

        for (ToolCall toolCall : toolCalls) {
            if (canExecuteInParallel(toolCall.getName())) {
                parallelTools.add(toolCall);
            } else {
                serialTools.add(toolCall);
            }
        }

        List<ToolCallResult> results = Lists.newArrayList();

        // 1. 并发执行可并行的工具
        if (!CollectionUtils.isEmpty(parallelTools)) {
            log.info("Executing {} tools in parallel", parallelTools.size());
            results.addAll(executeToolCallsInParallel(parallelTools, context));
        }

        // 2. 串行执行必须串行的工具
        if (!CollectionUtils.isEmpty(serialTools)) {
            log.info("Executing {} tools sequentially", serialTools.size());
            results.addAll(executeToolCallsSequentially(serialTools, context));
        }

        return results;
    }

    /**
     * 并发执行工具调用
     */
    private List<ToolCallResult> executeToolCallsInParallel(List<ToolCall> toolCalls, ExecutionContext context) {
        List<CompletableFuture<ToolCallResult>> futures = toolCalls.stream()
                .map(toolCall -> CompletableFuture.supplyAsync(
                        () -> executeSingleToolCall(toolCall, context),
                        toolExecutor
                ))
                .collect(Collectors.toList());

        // 等待所有任务完成
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            allOf.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Tool execution interrupted", e);
        } catch (ExecutionException e) {
            log.error("Tool execution failed", e);
        }

        // 收集结果（保持原始顺序）
        return futures.stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception e) {
                        log.error("Failed to get tool result", e);
                        return ToolCallResult.builder()
                                .success(false)
                                .error("Failed to get tool result: " + e.getMessage())
                                .build();
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 串行执行工具调用
     */
    private List<ToolCallResult> executeToolCallsSequentially(List<ToolCall> toolCalls, ExecutionContext context) {
        List<ToolCallResult> results = Lists.newArrayList();

        for (ToolCall toolCall : toolCalls) {
            ToolCallResult result = executeSingleToolCall(toolCall, context);
            results.add(result);
        }

        return results;
    }

    /**
     * 执行单个工具调用
     */
    private ToolCallResult executeSingleToolCall(ToolCall toolCall, ExecutionContext context) {
        log.info("Executing tool: {} (callId: {})", toolCall.getName(), toolCall.getCallId());
        emitEvent(AgentEvent.toolCallStart(toolCall.getName(), toolCall.getCallId(), toolCall.getArguments()));

        // 检查是否需要用户确认
        if (needsConfirmation(toolCall.getName(), toolCall.getArguments())) {
            if (!confirmExecution(toolCall)) {
                log.info("Tool execution rejected by user: {}", toolCall.getName());
                ToolCallResult rejectedResult = ToolCallResult.builder()
                        .callId(toolCall.getCallId())
                        .success(false)
                        .error("Execution rejected by user")
                        .build();
                emitEvent(AgentEvent.toolCallEnd(toolCall.getName(), toolCall.getCallId(), false, "Rejected by user"));
                return rejectedResult;
            }
        }

        // 执行工具
        ToolResult result = toolRegistry.execute(
                toolCall.getName(),
                toolCall.getArguments(),
                context
        );

        ToolCallResult callResult = ToolCallResult.from(toolCall.getCallId(), result);

        log.info("Tool {} completed, success: {}", toolCall.getName(), result.isSuccess());
        emitEvent(AgentEvent.toolCallEnd(toolCall.getName(), toolCall.getCallId(), result.isSuccess(),
                result.isSuccess() ? result.getContent() : result.getError()));
        return callResult;
    }

    /**
     * 判断工具是否可以并发执行
     */
    private boolean canExecuteInParallel(String toolName) {
        return toolRegistry.getTool(toolName)
                .map(BaseTool::canExecuteInParallel)
                .orElse(false);
    }

    /**
     * 检查是否需要用户确认
     */
    private boolean needsConfirmation(String toolName, JsonNode params) {
        if (config.isAutoConfirm()) {
            return false;
        }
        return toolRegistry.requiresConfirmation(toolName, params);
    }

    /**
     * 请求用户确认
     */
    private boolean confirmExecution(ToolCall toolCall) {
        if (confirmationHandler == null) {
            // 没有确认处理器，默认拒绝危险操作
            log.warn("No confirmation handler set, rejecting dangerous operation: {}", toolCall.getName());
            return false;
        }
        return confirmationHandler.confirm(toolCall.getName(), toolCall.getArguments());
    }

    /**
     * 发送事件
     */
    private void emitEvent(AgentEvent event) {
        if (eventHandler != null) {
            try {
                eventHandler.accept(event);
            } catch (Exception e) {
                log.warn("Event handler error", e);
            }
        }
    }

    /**
     * 发送进度消息
     */
    private void emitProgress(String message) {
        emitEvent(AgentEvent.progress(message));
    }

    /**
     * 关闭资源
     */
    public void shutdown() {
        if (toolExecutor != null && !toolExecutor.isShutdown()) {
            log.info("Shutting down tool executor");
            toolExecutor.shutdown();
            try {
                if (!toolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    toolExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                toolExecutor.shutdownNow();
            }
        }
    }

    /**
     * 确认处理器接口
     */
    @FunctionalInterface
    public interface ConfirmationHandler {
        /**
         * 请求用户确认
         *
         * @param toolName 工具名称
         * @param params 工具参数
         * @return true 允许执行，false 拒绝执行
         */
        boolean confirm(String toolName, JsonNode params);
    }
}
