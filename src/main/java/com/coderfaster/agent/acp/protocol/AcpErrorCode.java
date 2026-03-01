package com.coderfaster.agent.acp.protocol;

public class AcpErrorCode {
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int AUTH_REQUIRED = -32001;
    public static final int SESSION_NOT_FOUND = -32002;
    
    private AcpErrorCode() {}
}
