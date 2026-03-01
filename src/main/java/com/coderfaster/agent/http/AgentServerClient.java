package com.coderfaster.agent.http;

/**
 * Agent Server 客户端接口
 * 抽象与 LLM 服务的通信层，支持多种实现：
 * - DirectLlmClient: 直接调用百炼大模型（默认推荐）
 * - MultiModalLlmClient: 调用百炼多模态大模型（支持图像、视频等）
 */
public interface AgentServerClient extends AutoCloseable {

    /**
     * 发送聊天请求
     *
     * @param request 聊天请求
     * @return Agent 响应
     * @throws AgentHttpException 通信异常
     */
    AgentResponse chat(ChatRequest request) throws AgentHttpException;

    /**
     * 发送 Tool 执行结果
     *
     * @param request Tool 结果请求
     * @return Agent 响应
     * @throws AgentHttpException 通信异常
     */
    AgentResponse submitToolResult(ToolResultRequest request) throws AgentHttpException;

    /**
     * 健康检查
     *
     * @return true 如果服务可用
     */
    boolean healthCheck();

    /**
     * 关闭客户端，释放资源
     */
    @Override
    void close();
}
