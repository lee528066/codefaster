package com.coderfaster.agent.tui;

import com.coderfaster.agent.core.AgentEvent;
import dev.tamboui.toolkit.app.ToolkitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 将 AgentEvent 从后台线程桥接到 TamboUI 渲染线程。
 * 所有状态修改都通过 runOnRenderThread() 保证线程安全。
 */
public class AgentEventBridge {

    private static final Logger log = LoggerFactory.getLogger(AgentEventBridge.class);

    private final AppController controller;
    private final ToolkitRunner runner;

    public AgentEventBridge(AppController controller, ToolkitRunner runner) {
        this.controller = controller;
        this.runner = runner;
    }

    public void handleEvent(AgentEvent event) {
        runner.runOnRenderThread(() -> {
            try {
                switch (event.getType()) {
                    case STARTED:
                        controller.onAgentStarted(event);
                        break;
                    case ITERATION_START:
                        controller.onIterationStart(event);
                        break;
                    case ITERATION_END:
                        break;
                    case MESSAGE:
                        controller.onAgentMessage(event);
                        break;
                    case PROGRESS:
                        controller.onProgress(event);
                        break;
                    case TOOL_CALL_START:
                        controller.onToolCallStart(event);
                        break;
                    case TOOL_CALL_END:
                        controller.onToolCallEnd(event);
                        break;
                    case COMPLETED:
                        controller.onAgentCompleted(event);
                        break;
                    case ERROR:
                        controller.onAgentError(event);
                        break;
                    case CANCELLED:
                        controller.onAgentCancelled();
                        break;
                }
            } catch (Exception e) {
                log.error("Error handling agent event: {}", event.getType(), e);
            }
        });
    }
}
