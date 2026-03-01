package com.coderfaster.agent.http;

/**
 * Agent HTTP 通信异常
 */
public class AgentHttpException extends Exception {
    
    private final int statusCode;
    private final String responseBody;
    
    public AgentHttpException(String message) {
        super(message);
        this.statusCode = -1;
        this.responseBody = null;
    }
    
    public AgentHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.responseBody = null;
    }
    
    public AgentHttpException(String message, int statusCode, String responseBody) {
        super(message + " (HTTP " + statusCode + "): " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
    
    public int getStatusCode() {
        return statusCode;
    }
    
    public String getResponseBody() {
        return responseBody;
    }
    
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }
    
    public boolean isServerError() {
        return statusCode >= 500;
    }
    
    public boolean isNetworkError() {
        return statusCode == -1;
    }
}
