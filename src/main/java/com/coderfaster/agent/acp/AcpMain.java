package com.coderfaster.agent.acp;

import com.coderfaster.agent.AgentRunner;
import com.coderfaster.agent.acp.agent.AcpAgent;
import com.coderfaster.agent.acp.connection.AcpConnection;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACP 模式主入口类
 * 支持两种启动方式：
 * 1. 独立启动：直接运行 main 方法
 * 2. 嵌入启动：通过 TuiMain 的 --acp 参数启动
 */
public class AcpMain {

    private static final Logger log = LoggerFactory.getLogger(AcpMain.class);

    public static void main(String[] args) {
        log.info("CodeFaster ACP Mode starting...");

        try {
            // 独立启动模式：使用默认的 AcpAgent（内部创建 AgentRunner）
            startStandalone();
        } catch (Exception e) {
            log.error("ACP failed to start", e);
            System.exit(1);
        }
    }

    /**
     * 独立启动模式
     */
    private static void startStandalone() throws Exception {
        com.coderfaster.agent.acp.agent.CodeFasterAcpAgent agent = new com.coderfaster.agent.acp.agent.CodeFasterAcpAgent();

        AcpConnection connection = new AcpConnection((method, params) -> {
            return handleMethod(agent, method, params);
        });

        connection.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down ACP...");
            connection.stop();
            agent.close();
        }));

        Thread.currentThread().join();
    }

    /**
     * 使用 AgentRunner 启动 ACP 模式
     * 由 TuiMain 调用，传入已配置好的 AgentRunner
     *
     * @param agentRunner 已配置的 AgentRunner 实例
     */
    public static void startWithAgentRunner(AgentRunner agentRunner) throws Exception {
        log.info("CodeFaster ACP Mode starting with AgentRunner...");

        AcpAgent agent = new AcpAgent(agentRunner);
        AcpAgentHandler handler = new AcpAgentHandler(agent);

        AcpConnection connection = new AcpConnection(handler::handleMethod);

        connection.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down ACP...");
            connection.stop();
            handler.close();
        }));

        log.info("ACP started successfully");

        Thread.currentThread().join();
    }

    private static JsonNode handleMethod(com.coderfaster.agent.acp.agent.CodeFasterAcpAgent agent, String method, JsonNode params) throws Exception {
        try {
            switch (method) {
                case com.coderfaster.agent.acp.protocol.AcpMethods.INITIALIZE:
                    return agent.initialize(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.SESSION_NEW:
                    return agent.newSession(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.SESSION_LOAD:
                    return agent.loadSession(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.SESSION_LIST:
                    return agent.listSessions(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.SESSION_PROMPT:
                    return agent.prompt(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.SESSION_CANCEL:
                    return agent.cancel(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.SESSION_SET_MODEL:
                    return agent.setModel(params);
                case com.coderfaster.agent.acp.protocol.AcpMethods.AUTHENTICATE:
                    return agent.authenticate(params);
                default:
                    throw AcpException.methodNotFound("Unknown method: " + method);
            }
        } catch (AcpException e) {
            throw e;
        } catch (Exception e) {
            throw AcpException.internalError(e.getMessage());
        }
    }
}
