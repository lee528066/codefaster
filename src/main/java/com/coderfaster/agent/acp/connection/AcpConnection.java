package com.coderfaster.agent.acp.connection;

import com.coderfaster.agent.acp.protocol.AcpError;
import com.coderfaster.agent.acp.protocol.AcpMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

public class AcpConnection {
    
    private static final Logger log = LoggerFactory.getLogger(AcpConnection.class);
    
    private final ObjectMapper objectMapper;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final MessageHandler handler;
    private final AtomicInteger nextRequestId;
    private volatile boolean running;
    private Thread receiveThread;
    
    @FunctionalInterface
    public interface MessageHandler {
        JsonNode handle(String method, JsonNode params) throws Exception;
    }
    
    public AcpConnection(MessageHandler handler) {
        this.handler = handler;
        this.objectMapper = new ObjectMapper();
        this.reader = new BufferedReader(new InputStreamReader(System.in));
        this.writer = new PrintWriter(System.out, true);
        this.nextRequestId = new AtomicInteger(0);
        this.running = true;
    }
    
    public void start() {
        receiveThread = new Thread(this::receiveLoop, "ACP-Receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();
        log.info("ACP connection started");
    }
    
    public void stop() {
        running = false;
        if (receiveThread != null) {
            receiveThread.interrupt();
        }
        log.info("ACP connection stopped");
    }
    
    private void receiveLoop() {
        while (running) {
            try {
                String line = reader.readLine();
                if (line == null) {
                    log.warn("EOF received, closing connection");
                    break;
                }
                
                line = line.trim();
                if (line.isEmpty()) continue;
                
                log.debug("Received: {}", line);
                AcpMessage message = objectMapper.readValue(line, AcpMessage.class);
                processMessage(message);
                
            } catch (IOException e) {
                if (running) log.error("Read error", e);
                break;
            } catch (Exception e) {
                log.error("Message processing error", e);
            }
        }
    }
    
    private void processMessage(AcpMessage message) throws Exception {
        if (message.isRequest()) {
            try {
                JsonNode result = handler.handle(message.getMethod(), message.getParams());
                sendResponse(message.getId(), result);
            } catch (Exception e) {
                log.error("Handler error for method: {}", message.getMethod(), e);
                AcpError error = AcpError.internalError(e.getMessage());
                sendErrorResponse(message.getId(), error);
            }
        } else if (message.isNotification()) {
            handler.handle(message.getMethod(), message.getParams());
        }
    }
    
    public void sendResponse(Object id, JsonNode result) throws IOException {
        AcpMessage message = AcpMessage.response(id, result);
        sendMessage(message);
    }
    
    public void sendErrorResponse(Object id, AcpError error) throws IOException {
        AcpMessage message = AcpMessage.errorResponse(id, error);
        sendMessage(message);
    }
    
    private synchronized void sendMessage(AcpMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message);
        writer.println(json);
        writer.flush();
        log.debug("Sent: {}", json);
    }
}
