package com.coderfaster.agent.acp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AcpError {
    private int code;
    private String message;
    private JsonNode data;
    
    public static AcpError parseError(String details) {
        return builder().code(AcpErrorCode.PARSE_ERROR).message("Parse error").build();
    }
    
    public static AcpError invalidRequest(String details) {
        return builder().code(AcpErrorCode.INVALID_REQUEST).message("Invalid request").build();
    }
    
    public static AcpError methodNotFound(String details) {
        return builder().code(AcpErrorCode.METHOD_NOT_FOUND).message("Method not found").build();
    }
    
    public static AcpError invalidParams(String details) {
        return builder().code(AcpErrorCode.INVALID_PARAMS).message("Invalid params").build();
    }
    
    public static AcpError internalError(String details) {
        return builder().code(AcpErrorCode.INTERNAL_ERROR).message("Internal error").build();
    }
    
    public static AcpError authRequired(String details) {
        return builder().code(AcpErrorCode.AUTH_REQUIRED).message("Authentication required").build();
    }
}
