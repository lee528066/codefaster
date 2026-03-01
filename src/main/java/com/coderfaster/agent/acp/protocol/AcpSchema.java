package com.coderfaster.agent.acp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import java.util.List;
import java.util.Map;

public class AcpSchema {
    
    @Data
    public static class InitializeRequest {
        private String protocolVersion;
        private ClientCapabilities clientCapabilities;
    }
    
    @Data
    public static class ClientCapabilities {
        private FsCapabilities fs;
    }
    
    @Data
    public static class FsCapabilities {
        private Boolean readTextFile;
        private Boolean writeTextFile;
    }
    
    @Data
    public static class InitializeResponse {
        private String protocolVersion;
        private AgentInfo agentInfo;
        private List<AuthMethod> authMethods;
        private Modes modes;
        private AgentCapabilities agentCapabilities;
    }
    
    @Data
    public static class AgentInfo {
        private String name;
        private String title;
        private String version;
    }
    
    @Data
    public static class AuthMethod {
        private String id;
        private String name;
        private String description;
    }
    
    @Data
    public static class Modes {
        private String currentModeId;
        private List<ModeInfo> availableModes;
    }
    
    @Data
    public static class ModeInfo {
        private String id;
        private String name;
        private String description;
    }
    
    @Data
    public static class AgentCapabilities {
        private Boolean loadSession;
        private PromptCapabilities promptCapabilities;
    }
    
    @Data
    public static class PromptCapabilities {
        private Boolean image;
        private Boolean audio;
        private Boolean embeddedContext;
    }
    
    @Data
    public static class NewSessionRequest {
        private String cwd;
        private List<McpServer> mcpServers;
    }
    
    @Data
    public static class NewSessionResponse {
        private String sessionId;
        private Models models;
    }
    
    @Data
    public static class Models {
        private String currentModelId;
        private List<ModelInfo> availableModels;
    }
    
    @Data
    public static class ModelInfo {
        private String modelId;
        private String name;
        private String description;
        private ModelMeta _meta;
    }
    
    @Data
    public static class ModelMeta {
        private Integer contextLimit;
    }
    
    @Data
    public static class McpServer {
        private String name;
        private String command;
        private List<String> args;
        private List<EnvVar> env;
    }
    
    @Data
    public static class EnvVar {
        private String name;
        private String value;
    }
    
    @Data
    public static class PromptRequest {
        private String sessionId;
        private String prompt;
        private List<PromptPart> parts;
    }
    
    @Data
    public static class PromptPart {
        private String type;
        private String text;
    }
    
    @Data
    public static class PromptResponse {
        private String sessionId;
    }
    
    @Data
    public static class CancelNotification {
        private String sessionId;
    }
    
    @Data
    public static class SetModelRequest {
        private String sessionId;
        private String modelId;
    }
    
    @Data
    public static class SetModelResponse {
        private String sessionId;
        private String modelId;
    }
    
    @Data
    public static class AuthenticateRequest {
        private String methodId;
    }
}
