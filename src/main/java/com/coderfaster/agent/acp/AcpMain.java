package com.coderfaster.agent.acp;

import com.coderfaster.agent.acp.agent.CodeFasterAcpAgent;
import com.coderfaster.agent.acp.connection.AcpConnection;
import com.coderfaster.agent.acp.protocol.AcpMethods;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AcpMain {
    
    private static final Logger log = LoggerFactory.getLogger(AcpMain.class);
    
    public static void main(String[] args) {
        log.info("CodeFaster ACP Mode starting...");
        
        try {
            CodeFasterAcpAgent agent = new CodeFasterAcpAgent();
            
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
            
        } catch (Exception e) {
            log.error("ACP failed to start", e);
            System.exit(1);
        }
    }
    
    private static JsonNode handleMethod(CodeFasterAcpAgent agent, String method, JsonNode params) throws Exception {
        try {
            switch (method) {
                case AcpMethods.INITIALIZE: return agent.initialize(params);
                case AcpMethods.SESSION_NEW: return agent.newSession(params);
                case AcpMethods.SESSION_LOAD: return agent.loadSession(params);
                case AcpMethods.SESSION_LIST: return agent.listSessions(params);
                case AcpMethods.SESSION_PROMPT: return agent.prompt(params);
                case AcpMethods.SESSION_CANCEL: return agent.cancel(params);
                case AcpMethods.SESSION_SET_MODEL: return agent.setModel(params);
                case AcpMethods.AUTHENTICATE: return agent.authenticate(params);
                default: throw AcpException.methodNotFound("Unknown method: " + method);
            }
        } catch (AcpException e) {
            throw e;
        } catch (Exception e) {
            throw AcpException.internalError(e.getMessage());
        }
    }
}
