/**
 * LSP (Language Server Protocol) 支持包
 * 
 * <p>该包提供了与语言服务器协议（LSP）服务器交互的功能，支持代码智能特性如：
 * <ul>
 *   <li>跳转到定义 (Go to Definition)</li>
 *   <li>查找引用 (Find References)</li>
 *   <li>悬停信息 (Hover)</li>
 *   <li>文档符号 (Document Symbols)</li>
 *   <li>工作区符号搜索 (Workspace Symbol Search)</li>
 *   <li>查找实现 (Go to Implementation)</li>
 *   <li>调用层次结构 (Call Hierarchy)</li>
 *   <li>诊断信息 (Diagnostics)</li>
 *   <li>代码操作 (Code Actions)</li>
 * </ul>
 * 
 * <h2>主要类：</h2>
 * <ul>
 *   <li>{@link com.coderfaster.agent.lsp.LspService} - 高层服务接口，简化初始化和使用</li>
 *   <li>{@link com.coderfaster.agent.lsp.LspServerManager} - LSP 服务器进程管理器</li>
 *   <li>{@link com.coderfaster.agent.lsp.LspServerConfig} - LSP 服务器配置</li>
 *   <li>{@link com.coderfaster.agent.lsp.LspClientImpl} - LSP 客户端实现</li>
 * </ul>
 * 
 * <h2>使用示例：</h2>
 * <pre>{@code
 * // 方式一：使用 LspService（推荐）
 * try (LspService lspService = LspService.builder("/path/to/workspace")
 *         .withTypeScript()
 *         .withPython()
 *         .build()) {
 *     
 *     // 初始化（启动 LSP 服务器）
 *     lspService.initializeSync(60000);
 *     
 *     // 获取 LspTool 并使用
 *     LspTool lspTool = lspService.createLspTool();
 *     // ... 使用 lspTool
 * }
 * 
 * // 方式二：手动管理
 * LspServerManager manager = new LspServerManager();
 * manager.addServerConfig(LspServerConfig.typescript("/path/to/workspace"));
 * manager.startAllServers().get();
 * 
 * LspClientImpl client = new LspClientImpl(manager, "/path/to/workspace");
 * LspTool lspTool = new LspTool();
 * lspTool.setLspClient(client);
 * 
 * // ... 使用 lspTool
 * 
 * manager.close();
 * }</pre>
 * 
 * <h2>支持的语言服务器：</h2>
 * <ul>
 *   <li>Java - Eclipse JDT Language Server (jdtls)</li>
 *   <li>TypeScript/JavaScript - TypeScript Language Server</li>
 *   <li>Python - Python Language Server (pylsp)</li>
 *   <li>Go - gopls</li>
 *   <li>Rust - rust-analyzer</li>
 * </ul>
 * 
 * <p>也可以通过 {@link com.coderfaster.agent.lsp.LspServerConfig.Builder} 
 * 配置自定义的 LSP 服务器。
 * 
 * @see com.coderfaster.agent.tool.impl.LspTool
 */
package com.coderfaster.agent.lsp;
