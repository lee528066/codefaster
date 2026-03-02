package com.coderfaster.agent.acp;

import com.coderfaster.agent.acp.agent.AcpAgent;
import com.coderfaster.agent.acp.protocol.AcpMethods;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ACP Agent Handler - 方法路由处理器
 * 负责将 ACP 协议方法路由到 AcpAgent 实现
 */
public class AcpAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(AcpAgentHandler.class);

    private final AcpAgent agent;

    public AcpAgentHandler(AcpAgent agent) {
        this.agent = agent;
        log.info("AcpAgentHandler initialized");
    }

    /**
     * 处理 ACP 方法调用
     */
    public JsonNode handleMethod(String method, JsonNode params) throws Exception {
        try {
            log.debug("Handling ACP method: {}", method);

            switch (method) {
                case AcpMethods.INITIALIZE:
                    return agent.initialize(params);
                case AcpMethods.SESSION_NEW:
                    return agent.newSession(params);
                case AcpMethods.SESSION_LOAD:
                    return agent.loadSession(params);
                case AcpMethods.SESSION_LIST:
                    return agent.listSessions(params);
                case AcpMethods.SESSION_PROMPT:
                    return agent.prompt(params);
                case AcpMethods.SESSION_CANCEL:
                    return agent.cancel(params);
                case AcpMethods.SESSION_SET_MODEL:
                    return agent.setModel(params);
                case AcpMethods.AUTHENTICATE:
                    return agent.authenticate(params);
                default:
                    throw AcpException.methodNotFound("Unknown method: " + method);
            }
        } catch (AcpException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error handling method: {}", method, e);
            throw AcpException.internalError(e.getMessage());
        }
    }

    /**
     * 关闭资源
     */
    public void close() {
        agent.close();
        log.info("AcpAgentHandler closed");
    }
}
