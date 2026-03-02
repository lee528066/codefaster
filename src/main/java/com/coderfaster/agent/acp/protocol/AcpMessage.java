package com.coderfaster.agent.acp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
public class AcpMessage {
    @Builder.Default
    private String jsonrpc = "2.0";
    private Object id;
    private String method;
    private JsonNode params;
    private JsonNode result;
    private AcpError error;
    
    public boolean isRequest() { return method != null && id != null; }
    public boolean isNotification() { return method != null && id == null; }
    public boolean isResponse() { return id != null && (result != null || error != null); }
    
    public static AcpMessage request(Object id, String method, JsonNode params) {
        return builder().jsonrpc("2.0").id(id).method(method).params(params).build();
    }
    
    public static AcpMessage response(Object id, JsonNode result) {
        return builder().jsonrpc("2.0").id(id).result(result).build();
    }
    
    public static AcpMessage errorResponse(Object id, AcpError error) {
        return builder().jsonrpc("2.0").id(id).error(error).build();
    }
    
    public static AcpMessage notification(String method, JsonNode params) {
        return builder().jsonrpc("2.0").method(method).params(params).build();
    }
}
