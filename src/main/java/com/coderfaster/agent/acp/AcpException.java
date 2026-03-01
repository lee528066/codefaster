package com.coderfaster.agent.acp;

import com.coderfaster.agent.acp.protocol.AcpError;
import lombok.Getter;

@Getter
public class AcpException extends Exception {
    private final AcpError error;
    
    public AcpException(AcpError error) {
        super(error.getMessage());
        this.error = error;
    }
    
    public static AcpException methodNotFound(String details) {
        return new AcpException(AcpError.methodNotFound(details));
    }
    
    public static AcpException invalidParams(String details) {
        return new AcpException(AcpError.invalidParams(details));
    }
    
    public static AcpException internalError(String details) {
        return new AcpException(AcpError.internalError(details));
    }
    
    public static AcpException authRequired(String details) {
        return new AcpException(AcpError.authRequired(details));
    }
}
