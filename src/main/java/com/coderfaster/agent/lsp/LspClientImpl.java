package com.coderfaster.agent.lsp;

import com.coderfaster.agent.tool.impl.LspTool;
import com.coderfaster.agent.tool.impl.LspTool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * LSP 客户端实现类
 * 实现 LspTool.LspClient 接口，使用 lsp4j 与 LSP 服务器通信
 */
public class LspClientImpl implements LspTool.LspClient {

    private static final Logger logger = LoggerFactory.getLogger(LspClientImpl.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    private final LspServerManager serverManager;
    private final String workspaceRoot;
    private final Set<String> openedDocuments = Collections.synchronizedSet(new HashSet<>());

    public LspClientImpl(LspServerManager serverManager, String workspaceRoot) {
        this.serverManager = serverManager;
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public boolean isEnabled() {
        return !serverManager.getReadyServers().isEmpty();
    }

    @Override
    public List<LspLocation> definitions(String filePath, int line, int character, int limit) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                logger.debug("No LSP server available for file: {}", filePath);
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            ensureDocumentOpen(handle, uri, filePath);

            DefinitionParams params = new DefinitionParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line - 1, character - 1)
            );

            Either<List<? extends Location>, List<? extends LocationLink>> result =
                    handle.getServer().getTextDocumentService().definition(params)
                            .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspLocation> locations = new ArrayList<>();
            if (result != null) {
                if (result.isLeft() && result.getLeft() != null) {
                    for (Location loc : result.getLeft()) {
                        locations.add(convertLocation(loc));
                        if (locations.size() >= limit) break;
                    }
                } else if (result.isRight() && result.getRight() != null) {
                    for (LocationLink link : result.getRight()) {
                        locations.add(convertLocationLink(link));
                        if (locations.size() >= limit) break;
                    }
                }
            }
            return locations;
        } catch (Exception e) {
            logger.error("Error getting definitions", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspLocation> references(String filePath, int line, int character, boolean includeDeclaration, int limit) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            ensureDocumentOpen(handle, uri, filePath);

            ReferenceParams params = new ReferenceParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line - 1, character - 1),
                    new ReferenceContext(includeDeclaration)
            );

            List<? extends Location> result = handle.getServer().getTextDocumentService().references(params)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspLocation> locations = new ArrayList<>();
            if (result != null) {
                for (Location loc : result) {
                    locations.add(convertLocation(loc));
                    if (locations.size() >= limit) break;
                }
            }
            return locations;
        } catch (Exception e) {
            logger.error("Error getting references", e);
            return Collections.emptyList();
        }
    }

    @Override
    public String hover(String filePath, int line, int character) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                return null;
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            ensureDocumentOpen(handle, uri, filePath);

            HoverParams params = new HoverParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line - 1, character - 1)
            );

            Hover result = handle.getServer().getTextDocumentService().hover(params)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (result == null || result.getContents() == null) {
                return null;
            }

            return extractHoverContent(result.getContents());
        } catch (Exception e) {
            logger.error("Error getting hover info", e);
            return null;
        }
    }

    @Override
    public List<LspSymbol> documentSymbols(String filePath, int limit) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            ensureDocumentOpen(handle, uri, filePath);

            DocumentSymbolParams params = new DocumentSymbolParams(new TextDocumentIdentifier(uri));

            List<Either<SymbolInformation, DocumentSymbol>> result =
                    handle.getServer().getTextDocumentService().documentSymbol(params)
                            .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspSymbol> symbols = new ArrayList<>();
            if (result != null) {
                for (Either<SymbolInformation, DocumentSymbol> item : result) {
                    if (item.isLeft()) {
                        symbols.add(convertSymbolInformation(item.getLeft()));
                    } else if (item.isRight()) {
                        collectDocumentSymbols(item.getRight(), uri, null, symbols, limit);
                    }
                    if (symbols.size() >= limit) break;
                }
            }
            return symbols;
        } catch (Exception e) {
            logger.error("Error getting document symbols", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspSymbol> workspaceSymbols(String query, int limit) {
        try {
            List<LspSymbol> symbols = new ArrayList<>();

            for (LspServerManager.LspServerHandle handle : serverManager.getReadyServers()) {
                if (handle.getCapabilities().getWorkspaceSymbolProvider() == null) {
                    continue;
                }

                WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);

                Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result =
                        handle.getServer().getWorkspaceService().symbol(params)
                                .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (result != null) {
                    if (result.isLeft() && result.getLeft() != null) {
                        for (SymbolInformation info : result.getLeft()) {
                            symbols.add(convertSymbolInformation(info));
                            if (symbols.size() >= limit) return symbols;
                        }
                    } else if (result.isRight() && result.getRight() != null) {
                        for (WorkspaceSymbol ws : result.getRight()) {
                            symbols.add(convertWorkspaceSymbol(ws));
                            if (symbols.size() >= limit) return symbols;
                        }
                    }
                }
            }
            return symbols;
        } catch (Exception e) {
            logger.error("Error getting workspace symbols", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspLocation> implementations(String filePath, int line, int character, int limit) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            if (handle.getCapabilities().getImplementationProvider() == null) {
                return Collections.emptyList();
            }

            ensureDocumentOpen(handle, uri, filePath);

            ImplementationParams params = new ImplementationParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line - 1, character - 1)
            );

            Either<List<? extends Location>, List<? extends LocationLink>> result =
                    handle.getServer().getTextDocumentService().implementation(params)
                            .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspLocation> locations = new ArrayList<>();
            if (result != null) {
                if (result.isLeft() && result.getLeft() != null) {
                    for (Location loc : result.getLeft()) {
                        locations.add(convertLocation(loc));
                        if (locations.size() >= limit) break;
                    }
                } else if (result.isRight() && result.getRight() != null) {
                    for (LocationLink link : result.getRight()) {
                        locations.add(convertLocationLink(link));
                        if (locations.size() >= limit) break;
                    }
                }
            }
            return locations;
        } catch (Exception e) {
            logger.error("Error getting implementations", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspCallHierarchyItem> prepareCallHierarchy(String filePath, int line, int character, int limit) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            if (handle.getCapabilities().getCallHierarchyProvider() == null) {
                return Collections.emptyList();
            }

            ensureDocumentOpen(handle, uri, filePath);

            CallHierarchyPrepareParams params = new CallHierarchyPrepareParams(
                    new TextDocumentIdentifier(uri),
                    new Position(line - 1, character - 1)
            );

            List<CallHierarchyItem> result = handle.getServer().getTextDocumentService()
                    .prepareCallHierarchy(params)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspCallHierarchyItem> items = new ArrayList<>();
            if (result != null) {
                for (CallHierarchyItem item : result) {
                    items.add(convertCallHierarchyItem(item));
                    if (items.size() >= limit) break;
                }
            }
            return items;
        } catch (Exception e) {
            logger.error("Error preparing call hierarchy", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspCallHierarchyItem> incomingCalls(JsonNode itemNode, int limit) {
        try {
            CallHierarchyItem item = parseCallHierarchyItem(itemNode);
            if (item == null) {
                return Collections.emptyList();
            }

            String uri = item.getUri();
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(fromUri(uri));
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams(item);

            List<CallHierarchyIncomingCall> result = handle.getServer().getTextDocumentService()
                    .callHierarchyIncomingCalls(params)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspCallHierarchyItem> items = new ArrayList<>();
            if (result != null) {
                for (CallHierarchyIncomingCall call : result) {
                    items.add(convertCallHierarchyItem(call.getFrom()));
                    if (items.size() >= limit) break;
                }
            }
            return items;
        } catch (Exception e) {
            logger.error("Error getting incoming calls", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspCallHierarchyItem> outgoingCalls(JsonNode itemNode, int limit) {
        try {
            CallHierarchyItem item = parseCallHierarchyItem(itemNode);
            if (item == null) {
                return Collections.emptyList();
            }

            String uri = item.getUri();
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(fromUri(uri));
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams(item);

            List<CallHierarchyOutgoingCall> result = handle.getServer().getTextDocumentService()
                    .callHierarchyOutgoingCalls(params)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspCallHierarchyItem> items = new ArrayList<>();
            if (result != null) {
                for (CallHierarchyOutgoingCall call : result) {
                    items.add(convertCallHierarchyItem(call.getTo()));
                    if (items.size() >= limit) break;
                }
            }
            return items;
        } catch (Exception e) {
            logger.error("Error getting outgoing calls", e);
            return Collections.emptyList();
        }
    }

    @Override
    public List<LspDiagnostic> diagnostics(String filePath) {
        try {
            String uri = toUri(filePath);
            List<LspDiagnostic> diagnostics = new ArrayList<>();

            for (LspServerManager.LspServerHandle handle : serverManager.getReadyServers()) {
                for (PublishDiagnosticsParams params : handle.getCachedDiagnosticsForUri(uri)) {
                    if (params.getDiagnostics() != null) {
                        for (Diagnostic diag : params.getDiagnostics()) {
                            diagnostics.add(convertDiagnostic(diag));
                        }
                    }
                }
            }

            return diagnostics;
        } catch (Exception e) {
            logger.error("Error getting diagnostics", e);
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, List<LspDiagnostic>> workspaceDiagnostics(int limit) {
        try {
            Map<String, List<LspDiagnostic>> result = new LinkedHashMap<>();
            int count = 0;

            for (LspServerManager.LspServerHandle handle : serverManager.getReadyServers()) {
                for (PublishDiagnosticsParams params : handle.getCachedDiagnostics()) {
                    if (count >= limit) break;

                    String filePath = fromUri(params.getUri());
                    if (params.getDiagnostics() != null && !params.getDiagnostics().isEmpty()) {
                        List<LspDiagnostic> diagnostics = params.getDiagnostics().stream()
                                .map(this::convertDiagnostic)
                                .collect(Collectors.toList());
                        result.put(filePath, diagnostics);
                        count++;
                    }
                }
            }

            return result;
        } catch (Exception e) {
            logger.error("Error getting workspace diagnostics", e);
            return Collections.emptyMap();
        }
    }

    @Override
    public List<LspCodeAction> codeActions(String filePath, int line, int character, int endLine, int endCharacter, int limit) {
        try {
            String uri = toUri(filePath);
            Optional<LspServerManager.LspServerHandle> handleOpt = serverManager.getServerForFile(filePath);
            if (handleOpt.isEmpty()) {
                return Collections.emptyList();
            }

            LspServerManager.LspServerHandle handle = handleOpt.get();
            if (handle.getCapabilities().getCodeActionProvider() == null) {
                return Collections.emptyList();
            }

            ensureDocumentOpen(handle, uri, filePath);

            Range range = new Range(
                    new Position(line - 1, character - 1),
                    new Position(endLine - 1, endCharacter - 1)
            );

            List<Diagnostic> diagnosticsInRange = new ArrayList<>();
            for (PublishDiagnosticsParams params : handle.getCachedDiagnosticsForUri(uri)) {
                if (params.getDiagnostics() != null) {
                    for (Diagnostic diag : params.getDiagnostics()) {
                        if (rangesOverlap(diag.getRange(), range)) {
                            diagnosticsInRange.add(diag);
                        }
                    }
                }
            }

            CodeActionContext context = new CodeActionContext(diagnosticsInRange);
            CodeActionParams params = new CodeActionParams(
                    new TextDocumentIdentifier(uri),
                    range,
                    context
            );

            List<Either<Command, CodeAction>> result = handle.getServer().getTextDocumentService()
                    .codeAction(params)
                    .get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<LspCodeAction> actions = new ArrayList<>();
            if (result != null) {
                for (Either<Command, CodeAction> item : result) {
                    if (item.isRight()) {
                        actions.add(convertCodeAction(item.getRight()));
                    } else if (item.isLeft()) {
                        actions.add(convertCommand(item.getLeft()));
                    }
                    if (actions.size() >= limit) break;
                }
            }
            return actions;
        } catch (Exception e) {
            logger.error("Error getting code actions", e);
            return Collections.emptyList();
        }
    }

    private void ensureDocumentOpen(LspServerManager.LspServerHandle handle, String uri, String filePath) {
        if (openedDocuments.contains(uri)) {
            return;
        }

        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                path = Paths.get(workspaceRoot, filePath);
            }

            if (Files.exists(path)) {
                String content = Files.readString(path);
                String languageId = detectLanguageId(filePath);

                DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(
                        new TextDocumentItem(uri, languageId, 1, content)
                );
                handle.getServer().getTextDocumentService().didOpen(params);
                openedDocuments.add(uri);
                logger.debug("Opened document: {}", uri);
            }
        } catch (Exception e) {
            logger.warn("Failed to open document: {}", uri, e);
        }
    }

    private String toUri(String filePath) {
        Path path = Paths.get(filePath);
        if (!path.isAbsolute()) {
            path = Paths.get(workspaceRoot, filePath);
        }
        return path.toUri().toString();
    }

    private String fromUri(String uri) {
        try {
            return Paths.get(new URI(uri)).toString();
        } catch (Exception e) {
            return uri.replaceFirst("file://", "");
        }
    }

    private String detectLanguageId(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".ts")) return "typescript";
        if (lower.endsWith(".tsx")) return "typescriptreact";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".jsx")) return "javascriptreact";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".go")) return "go";
        if (lower.endsWith(".rs")) return "rust";
        if (lower.endsWith(".c")) return "c";
        if (lower.endsWith(".cpp") || lower.endsWith(".cc")) return "cpp";
        if (lower.endsWith(".h") || lower.endsWith(".hpp")) return "cpp";
        if (lower.endsWith(".rb")) return "ruby";
        if (lower.endsWith(".php")) return "php";
        if (lower.endsWith(".cs")) return "csharp";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlin";
        if (lower.endsWith(".swift")) return "swift";
        return "plaintext";
    }

    private LspLocation convertLocation(Location loc) {
        LspLocation result = new LspLocation();
        result.uri = fromUri(loc.getUri());
        result.startLine = loc.getRange().getStart().getLine() + 1;
        result.startChar = loc.getRange().getStart().getCharacter() + 1;
        result.endLine = loc.getRange().getEnd().getLine() + 1;
        result.endChar = loc.getRange().getEnd().getCharacter() + 1;
        return result;
    }

    private LspLocation convertLocationLink(LocationLink link) {
        LspLocation result = new LspLocation();
        result.uri = fromUri(link.getTargetUri());
        result.startLine = link.getTargetRange().getStart().getLine() + 1;
        result.startChar = link.getTargetRange().getStart().getCharacter() + 1;
        result.endLine = link.getTargetRange().getEnd().getLine() + 1;
        result.endChar = link.getTargetRange().getEnd().getCharacter() + 1;
        return result;
    }

    private LspSymbol convertSymbolInformation(SymbolInformation info) {
        LspSymbol result = new LspSymbol();
        result.name = info.getName();
        result.kind = info.getKind().name().toLowerCase();
        result.uri = fromUri(info.getLocation().getUri());
        result.line = info.getLocation().getRange().getStart().getLine() + 1;
        result.containerName = info.getContainerName();
        return result;
    }

    private LspSymbol convertWorkspaceSymbol(WorkspaceSymbol ws) {
        LspSymbol result = new LspSymbol();
        result.name = ws.getName();
        result.kind = ws.getKind().name().toLowerCase();
        if (ws.getLocation().isLeft()) {
            Location loc = ws.getLocation().getLeft();
            result.uri = fromUri(loc.getUri());
            result.line = loc.getRange().getStart().getLine() + 1;
        } else if (ws.getLocation().isRight()) {
            WorkspaceSymbolLocation loc = ws.getLocation().getRight();
            result.uri = fromUri(loc.getUri());
            result.line = 1;
        }
        result.containerName = ws.getContainerName();
        return result;
    }

    private void collectDocumentSymbols(DocumentSymbol symbol, String uri, String containerName,
                                         List<LspSymbol> symbols, int limit) {
        if (symbols.size() >= limit) return;

        LspSymbol result = new LspSymbol();
        result.name = symbol.getName();
        result.kind = symbol.getKind().name().toLowerCase();
        result.uri = fromUri(uri);
        result.line = symbol.getRange().getStart().getLine() + 1;
        result.containerName = containerName;
        symbols.add(result);

        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                collectDocumentSymbols(child, uri, symbol.getName(), symbols, limit);
            }
        }
    }

    private LspCallHierarchyItem convertCallHierarchyItem(CallHierarchyItem item) {
        LspCallHierarchyItem result = new LspCallHierarchyItem();
        result.name = item.getName();
        result.kind = item.getKind().name().toLowerCase();
        result.uri = fromUri(item.getUri());
        result.line = item.getRange().getStart().getLine() + 1;
        result.detail = item.getDetail();
        return result;
    }

    private CallHierarchyItem parseCallHierarchyItem(JsonNode node) {
        try {
            if (node == null || node.isMissingNode()) {
                return null;
            }

            CallHierarchyItem item = new CallHierarchyItem();
            item.setName(node.path("name").asText());
            item.setKind(SymbolKind.valueOf(node.path("kind").asText().toUpperCase()));
            item.setUri(toUri(node.path("uri").asText()));

            int line = node.path("line").asInt(1) - 1;
            Range range = new Range(new Position(line, 0), new Position(line, 0));
            item.setRange(range);
            item.setSelectionRange(range);

            if (node.has("detail")) {
                item.setDetail(node.path("detail").asText());
            }

            return item;
        } catch (Exception e) {
            logger.error("Error parsing call hierarchy item", e);
            return null;
        }
    }

    private LspDiagnostic convertDiagnostic(Diagnostic diag) {
        LspDiagnostic result = new LspDiagnostic();
        result.line = diag.getRange().getStart().getLine() + 1;
        result.character = diag.getRange().getStart().getCharacter() + 1;
        result.message = diag.getMessage();
        result.severity = diag.getSeverity() != null ? diag.getSeverity().name().toLowerCase() : "error";
        result.code = diag.getCode() != null ? diag.getCode().toString() : null;
        result.source = diag.getSource();
        return result;
    }

    private LspCodeAction convertCodeAction(CodeAction action) {
        LspCodeAction result = new LspCodeAction();
        result.title = action.getTitle();
        result.kind = action.getKind() != null ? action.getKind() : "quickfix";
        result.isPreferred = action.getIsPreferred() != null && action.getIsPreferred();
        result.hasEdit = action.getEdit() != null;
        result.hasCommand = action.getCommand() != null;
        return result;
    }

    private LspCodeAction convertCommand(Command command) {
        LspCodeAction result = new LspCodeAction();
        result.title = command.getTitle();
        result.kind = "command";
        result.isPreferred = false;
        result.hasEdit = false;
        result.hasCommand = true;
        return result;
    }

    private String extractHoverContent(Either<List<Either<String, MarkedString>>, MarkupContent> contents) {
        if (contents.isRight()) {
            return contents.getRight().getValue();
        } else if (contents.isLeft()) {
            StringBuilder sb = new StringBuilder();
            for (Either<String, MarkedString> item : contents.getLeft()) {
                if (item.isLeft()) {
                    sb.append(item.getLeft());
                } else {
                    MarkedString ms = item.getRight();
                    if (ms.getLanguage() != null && !ms.getLanguage().isEmpty()) {
                        sb.append("```").append(ms.getLanguage()).append("\n");
                        sb.append(ms.getValue());
                        sb.append("\n```");
                    } else {
                        sb.append(ms.getValue());
                    }
                }
                sb.append("\n\n");
            }
            return sb.toString().trim();
        }
        return null;
    }

    private boolean rangesOverlap(Range a, Range b) {
        return !(a.getEnd().getLine() < b.getStart().getLine() ||
                (a.getEnd().getLine() == b.getStart().getLine() && a.getEnd().getCharacter() < b.getStart().getCharacter()) ||
                a.getStart().getLine() > b.getEnd().getLine() ||
                (a.getStart().getLine() == b.getEnd().getLine() && a.getStart().getCharacter() > b.getEnd().getCharacter()));
    }

    /**
     * 关闭所有已打开的文档
     */
    public void closeAllDocuments() {
        for (String uri : new ArrayList<>(openedDocuments)) {
            try {
                for (LspServerManager.LspServerHandle handle : serverManager.getReadyServers()) {
                    DidCloseTextDocumentParams params = new DidCloseTextDocumentParams(
                            new TextDocumentIdentifier(uri)
                    );
                    handle.getServer().getTextDocumentService().didClose(params);
                }
            } catch (Exception e) {
                logger.warn("Error closing document: {}", uri, e);
            }
        }
        openedDocuments.clear();
    }
}
