package com.coderfaster.agent.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * LSP 服务器管理器
 * 负责启动、管理和停止 LSP 服务器进程
 */
public class LspServerManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LspServerManager.class);

    private final Map<String, LspServerHandle> serverHandles = new ConcurrentHashMap<>();
    private final Map<String, LspServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, String> languageToServer = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private volatile boolean closed = false;

    public LspServerManager() {
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "lsp-server-manager");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 添加服务器配置
     */
    public void addServerConfig(LspServerConfig config) {
        serverConfigs.put(config.getName(), config);
        for (String language : config.getLanguages()) {
            languageToServer.put(language.toLowerCase(), config.getName());
        }
        logger.info("Added LSP server config: {} for languages: {}", config.getName(), config.getLanguages());
    }

    /**
     * 启动指定名称的服务器
     */
    public CompletableFuture<LspServerHandle> startServer(String serverName) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Manager is closed"));
        }

        LspServerHandle existingHandle = serverHandles.get(serverName);
        if (existingHandle != null && existingHandle.getStatus() == LspServerStatus.READY) {
            return CompletableFuture.completedFuture(existingHandle);
        }

        LspServerConfig config = serverConfigs.get(serverName);
        if (config == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No config found for server: " + serverName));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return doStartServer(config);
            } catch (Exception e) {
                logger.error("Failed to start LSP server: {}", serverName, e);
                throw new CompletionException(e);
            }
        }, executorService);
    }

    /**
     * 根据语言启动对应的服务器
     */
    public CompletableFuture<LspServerHandle> startServerForLanguage(String language) {
        String serverName = languageToServer.get(language.toLowerCase());
        if (serverName == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No server configured for language: " + language));
        }
        return startServer(serverName);
    }

    /**
     * 启动所有已配置的服务器
     */
    public CompletableFuture<Void> startAllServers() {
        List<CompletableFuture<LspServerHandle>> futures = new ArrayList<>();
        for (String serverName : serverConfigs.keySet()) {
            futures.add(startServer(serverName));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * 获取服务器句柄
     */
    public Optional<LspServerHandle> getServerHandle(String serverName) {
        return Optional.ofNullable(serverHandles.get(serverName));
    }

    /**
     * 根据语言获取服务器句柄
     */
    public Optional<LspServerHandle> getServerForLanguage(String language) {
        String serverName = languageToServer.get(language.toLowerCase());
        if (serverName == null) {
            return Optional.empty();
        }
        return getServerHandle(serverName);
    }

    /**
     * 根据文件路径获取服务器句柄
     */
    public Optional<LspServerHandle> getServerForFile(String filePath) {
        String language = detectLanguage(filePath);
        if (language == null) {
            return Optional.empty();
        }
        return getServerForLanguage(language);
    }

    /**
     * 获取所有就绪的服务器句柄
     */
    public List<LspServerHandle> getReadyServers() {
        List<LspServerHandle> ready = new ArrayList<>();
        for (LspServerHandle handle : serverHandles.values()) {
            if (handle.getStatus() == LspServerStatus.READY) {
                ready.add(handle);
            }
        }
        return ready;
    }

    /**
     * 停止指定服务器
     */
    public CompletableFuture<Void> stopServer(String serverName) {
        LspServerHandle handle = serverHandles.remove(serverName);
        if (handle == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                handle.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down server: {}", serverName, e);
            }
        }, executorService);
    }

    /**
     * 停止所有服务器
     */
    public CompletableFuture<Void> stopAllServers() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String serverName : new ArrayList<>(serverHandles.keySet())) {
            futures.add(stopServer(serverName));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        try {
            stopAllServers().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Error stopping servers during close", e);
        }

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private LspServerHandle doStartServer(LspServerConfig config) throws Exception {
        logger.info("Starting LSP server: {}", config.getName());

        Process process = null;
        Socket socket = null;
        InputStream inputStream;
        OutputStream outputStream;

        switch (config.getTransport()) {
            case STDIO:
                process = startProcess(config);
                inputStream = process.getInputStream();
                outputStream = process.getOutputStream();
                break;
            case TCP:
                socket = new Socket(config.getHost(), config.getPort());
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                break;
            case SOCKET:
                throw new UnsupportedOperationException("Unix socket transport not yet supported");
            default:
                throw new IllegalArgumentException("Unknown transport type: " + config.getTransport());
        }

        LspLanguageClient languageClient = new LspLanguageClient();
        Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                languageClient, inputStream, outputStream);

        LanguageServer languageServer = launcher.getRemoteProxy();
        Future<?> listening = launcher.startListening();

        InitializeParams initParams = createInitializeParams(config);
        InitializeResult initResult = languageServer.initialize(initParams)
                .get(config.getStartupTimeoutMs(), TimeUnit.MILLISECONDS);

        languageServer.initialized(new InitializedParams());

        LspServerHandle handle = new LspServerHandle(
                config.getName(),
                config,
                languageServer,
                languageClient,
                process,
                socket,
                listening,
                initResult.getCapabilities()
        );

        serverHandles.put(config.getName(), handle);
        logger.info("LSP server {} started successfully with capabilities: {}",
                config.getName(), summarizeCapabilities(initResult.getCapabilities()));

        return handle;
    }

    private Process startProcess(LspServerConfig config) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(config.getCommand());
        command.addAll(config.getArgs());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);

        if (config.getWorkspaceRoot() != null) {
            pb.directory(new File(config.getWorkspaceRoot()));
        }

        Map<String, String> env = pb.environment();
        env.putAll(config.getEnv());

        logger.debug("Starting process: {}", String.join(" ", command));
        return pb.start();
    }

    private InitializeParams createInitializeParams(LspServerConfig config) {
        InitializeParams params = new InitializeParams();

        params.setProcessId((int) ProcessHandle.current().pid());

        if (config.getWorkspaceRoot() != null) {
            String rootUri = Paths.get(config.getWorkspaceRoot()).toUri().toString();
            params.setRootUri(rootUri);

            WorkspaceFolder folder = new WorkspaceFolder();
            folder.setUri(rootUri);
            folder.setName(Paths.get(config.getWorkspaceRoot()).getFileName().toString());
            params.setWorkspaceFolders(List.of(folder));
        }

        ClientCapabilities capabilities = new ClientCapabilities();

        TextDocumentClientCapabilities textDocument = new TextDocumentClientCapabilities();

        HoverCapabilities hover = new HoverCapabilities();
        hover.setContentFormat(List.of(MarkupKind.MARKDOWN, MarkupKind.PLAINTEXT));
        textDocument.setHover(hover);

        DefinitionCapabilities definition = new DefinitionCapabilities();
        definition.setLinkSupport(true);
        textDocument.setDefinition(definition);

        ReferencesCapabilities references = new ReferencesCapabilities();
        textDocument.setReferences(references);

        DocumentSymbolCapabilities documentSymbol = new DocumentSymbolCapabilities();
        documentSymbol.setHierarchicalDocumentSymbolSupport(true);
        textDocument.setDocumentSymbol(documentSymbol);

        ImplementationCapabilities implementation = new ImplementationCapabilities();
        implementation.setLinkSupport(true);
        textDocument.setImplementation(implementation);

        CallHierarchyCapabilities callHierarchy = new CallHierarchyCapabilities();
        textDocument.setCallHierarchy(callHierarchy);

        PublishDiagnosticsCapabilities publishDiagnostics = new PublishDiagnosticsCapabilities();
        publishDiagnostics.setRelatedInformation(true);
        textDocument.setPublishDiagnostics(publishDiagnostics);

        CodeActionCapabilities codeAction = new CodeActionCapabilities();
        codeAction.setCodeActionLiteralSupport(new CodeActionLiteralSupportCapabilities(
                new CodeActionKindCapabilities(List.of(
                        CodeActionKind.QuickFix,
                        CodeActionKind.Refactor,
                        CodeActionKind.RefactorExtract,
                        CodeActionKind.RefactorInline,
                        CodeActionKind.RefactorRewrite,
                        CodeActionKind.Source,
                        CodeActionKind.SourceOrganizeImports
                ))
        ));
        textDocument.setCodeAction(codeAction);

        capabilities.setTextDocument(textDocument);

        WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
        workspace.setWorkspaceFolders(true);
        SymbolCapabilities symbol = new SymbolCapabilities();
        workspace.setSymbol(symbol);
        capabilities.setWorkspace(workspace);

        GeneralClientCapabilities general = new GeneralClientCapabilities();
        capabilities.setGeneral(general);

        params.setCapabilities(capabilities);
        params.setClientInfo(new ClientInfo("coderfaster-agent", "1.0.0"));

        return params;
    }

    private String detectLanguage(String filePath) {
        if (filePath == null) {
            return null;
        }
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".java")) {
            return "java";
        } else if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        } else if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "javascript";
        } else if (lower.endsWith(".py")) {
            return "python";
        } else if (lower.endsWith(".go")) {
            return "go";
        } else if (lower.endsWith(".rs")) {
            return "rust";
        } else if (lower.endsWith(".c") || lower.endsWith(".h")) {
            return "c";
        } else if (lower.endsWith(".cpp") || lower.endsWith(".hpp") || lower.endsWith(".cc")) {
            return "cpp";
        } else if (lower.endsWith(".rb")) {
            return "ruby";
        } else if (lower.endsWith(".php")) {
            return "php";
        } else if (lower.endsWith(".cs")) {
            return "csharp";
        } else if (lower.endsWith(".kt") || lower.endsWith(".kts")) {
            return "kotlin";
        } else if (lower.endsWith(".swift")) {
            return "swift";
        }
        return null;
    }

    private String summarizeCapabilities(ServerCapabilities caps) {
        List<String> features = new ArrayList<>();
        if (caps.getHoverProvider() != null) features.add("hover");
        if (caps.getDefinitionProvider() != null) features.add("definition");
        if (caps.getReferencesProvider() != null) features.add("references");
        if (caps.getDocumentSymbolProvider() != null) features.add("documentSymbol");
        if (caps.getWorkspaceSymbolProvider() != null) features.add("workspaceSymbol");
        if (caps.getImplementationProvider() != null) features.add("implementation");
        if (caps.getCallHierarchyProvider() != null) features.add("callHierarchy");
        if (caps.getCodeActionProvider() != null) features.add("codeAction");
        return String.join(", ", features);
    }

    /**
     * 内部 LanguageClient 实现，用于接收服务器的通知
     */
    private static class LspLanguageClient implements LanguageClient {
        private final List<PublishDiagnosticsParams> diagnostics = new CopyOnWriteArrayList<>();

        @Override
        public void telemetryEvent(Object object) {
            logger.debug("Telemetry event: {}", object);
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            diagnostics.removeIf(d -> d.getUri().equals(params.getUri()));
            if (params.getDiagnostics() != null && !params.getDiagnostics().isEmpty()) {
                diagnostics.add(params);
            }
            logger.debug("Received diagnostics for {}: {} items",
                    params.getUri(), params.getDiagnostics() != null ? params.getDiagnostics().size() : 0);
        }

        @Override
        public void showMessage(MessageParams params) {
            logger.info("LSP message [{}]: {}", params.getType(), params.getMessage());
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams params) {
            logger.info("LSP message request [{}]: {}", params.getType(), params.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams params) {
            switch (params.getType()) {
                case Error:
                    logger.error("LSP log: {}", params.getMessage());
                    break;
                case Warning:
                    logger.warn("LSP log: {}", params.getMessage());
                    break;
                case Info:
                    logger.info("LSP log: {}", params.getMessage());
                    break;
                case Log:
                    logger.debug("LSP log: {}", params.getMessage());
                    break;
            }
        }

        public List<PublishDiagnosticsParams> getDiagnostics() {
            return new ArrayList<>(diagnostics);
        }

        public List<PublishDiagnosticsParams> getDiagnosticsForUri(String uri) {
            List<PublishDiagnosticsParams> result = new ArrayList<>();
            for (PublishDiagnosticsParams params : diagnostics) {
                if (params.getUri().equals(uri)) {
                    result.add(params);
                }
            }
            return result;
        }
    }

    /**
     * LSP 服务器句柄，包含服务器连接和状态信息
     */
    public static class LspServerHandle {
        private final String name;
        private final LspServerConfig config;
        private final LanguageServer server;
        private final LspLanguageClient client;
        private final Process process;
        private final Socket socket;
        private final Future<?> listening;
        private final ServerCapabilities capabilities;
        private volatile LspServerStatus status;

        LspServerHandle(String name, LspServerConfig config, LanguageServer server,
                        LspLanguageClient client, Process process, Socket socket,
                        Future<?> listening, ServerCapabilities capabilities) {
            this.name = name;
            this.config = config;
            this.server = server;
            this.client = client;
            this.process = process;
            this.socket = socket;
            this.listening = listening;
            this.capabilities = capabilities;
            this.status = LspServerStatus.READY;
        }

        public String getName() {
            return name;
        }

        public LspServerConfig getConfig() {
            return config;
        }

        public LanguageServer getServer() {
            return server;
        }

        public ServerCapabilities getCapabilities() {
            return capabilities;
        }

        public LspServerStatus getStatus() {
            return status;
        }

        public List<PublishDiagnosticsParams> getCachedDiagnostics() {
            return client.getDiagnostics();
        }

        public List<PublishDiagnosticsParams> getCachedDiagnosticsForUri(String uri) {
            return client.getDiagnosticsForUri(uri);
        }

        void shutdown() {
            status = LspServerStatus.STOPPING;
            try {
                server.shutdown().get(config.getShutdownTimeoutMs(), TimeUnit.MILLISECONDS);
                server.exit();
            } catch (Exception e) {
                logger.warn("Error during shutdown of {}", name, e);
            }

            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception e) {
                    logger.warn("Error closing socket for {}", name, e);
                }
            }

            if (process != null) {
                try {
                    if (process.isAlive()) {
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    logger.warn("Error destroying process for {}", name, e);
                }
            }

            if (listening != null) {
                listening.cancel(true);
            }

            status = LspServerStatus.STOPPED;
        }
    }

    /**
     * LSP 服务器状态
     */
    public enum LspServerStatus {
        NOT_STARTED,
        STARTING,
        READY,
        STOPPING,
        STOPPED,
        FAILED
    }
}
