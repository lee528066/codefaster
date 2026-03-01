package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LSP 工具 - 用于代码智能的语言服务器协议工具。
 * <p>
 * 基于 coderfaster-agent lsp 实现
 * <p>
 * 支持多种操作：
 * - goToDefinition: 查找符号定义位置
 * - findReferences: 查找符号的所有引用
 * - hover: 获取悬停信息（文档、类型信息）
 * - documentSymbol: 获取文档中的所有符号
 * - workspaceSymbol: 在工作空间中搜索符号
 * - goToImplementation: 查找接口或抽象方法的实现
 * - prepareCallHierarchy: 获取指定位置的调用层级项
 * - incomingCalls: 查找调用给定函数的所有函数
 * - outgoingCalls: 查找给定函数调用的所有函数
 * - diagnostics: 获取文件的诊断消息
 * - workspaceDiagnostics: 获取工作空间中的所有诊断
 * - codeActions: 获取指定位置的可用代码操作
 */
public class LspTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(LspTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 用于处理操作的 LSP 客户端
     */
    private LspClient lspClient;

    /**
     * 有效的 LSP 操作
     */
    private static final List<String> VALID_OPERATIONS = Arrays.asList(
            "goToDefinition",
            "findReferences",
            "hover",
            "documentSymbol",
            "workspaceSymbol",
            "goToImplementation",
            "prepareCallHierarchy",
            "incomingCalls",
            "outgoingCalls",
            "diagnostics",
            "workspaceDiagnostics",
            "codeActions"
    );

    /**
     * 需要 filePath 和 line 的操作
     */
    private static final Set<String> LOCATION_REQUIRED_OPERATIONS = new HashSet<>(Arrays.asList(
            "goToDefinition", "findReferences", "hover", "goToImplementation", "prepareCallHierarchy"
    ));

    /**
     * 只需要 filePath 的操作
     */
    private static final Set<String> FILE_REQUIRED_OPERATIONS = new HashSet<>(Arrays.asList(
            "documentSymbol", "diagnostics"
    ));

    /**
     * 需要 query 的操作
     */
    private static final Set<String> QUERY_REQUIRED_OPERATIONS = new HashSet<>(Arrays.asList(
            "workspaceSymbol"
    ));

    private static final String DESCRIPTION =
            "Language Server Protocol (LSP) tool for code intelligence: definitions, references, hover, " +
                    "symbols, call hierarchy, diagnostics, and code actions.\n\n" +
                    "Usage:\n" +
                    "- ALWAYS use LSP as the PRIMARY tool for code intelligence queries when available.\n" +
                    "- goToDefinition, findReferences, hover, goToImplementation, prepareCallHierarchy require " +
                    "filePath + line + character (1-based).\n" +
                    "- documentSymbol and diagnostics require filePath.\n" +
                    "- workspaceSymbol requires query string.\n" +
                    "- incomingCalls and outgoingCalls require callHierarchyItem from prepareCallHierarchy.\n" +
                    "- codeActions requires filePath and range (line, character, endLine, endCharacter).\n\n" +
                    "Operations:\n" +
                    "- goToDefinition: Find where a symbol is defined\n" +
                    "- findReferences: Find all references to a symbol\n" +
                    "- hover: Get hover information (documentation, type info)\n" +
                    "- documentSymbol: Get all symbols in a document\n" +
                    "- workspaceSymbol: Search for symbols across the workspace\n" +
                    "- goToImplementation: Find implementations of an interface/abstract method\n" +
                    "- prepareCallHierarchy: Get call hierarchy item at a position\n" +
                    "- incomingCalls: Find all functions that call the given function\n" +
                    "- outgoingCalls: Find all functions called by the given function\n" +
                    "- diagnostics: Get diagnostic messages for a file\n" +
                    "- workspaceDiagnostics: Get all diagnostics across the workspace\n" +
                    "- codeActions: Get available code actions (quick fixes, refactorings)";

    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
                .addEnumProperty("operation", VALID_OPERATIONS, "Operation to perform")
                .addProperty("filePath", "string",
                        "File path (absolute or workspace-relative)")
                .addProperty("line", "number",
                        "1-based line number when targeting a specific file location")
                .addProperty("character", "number",
                        "1-based character/column number when targeting a specific file location")
                .addProperty("endLine", "number",
                        "End line for range-based operations (1-based)")
                .addProperty("endCharacter", "number",
                        "End character for range-based operations (1-based)")
                .addProperty("includeDeclaration", "boolean",
                        "Whether to include the declaration in reference results")
                .addProperty("query", "string",
                        "Query string for workspace symbol search")
                .addProperty("callHierarchyItem", "object",
                        "Call hierarchy item from a previous call hierarchy operation")
                .addProperty("serverName", "string",
                        "Optional server name override")
                .addProperty("limit", "number",
                        "Optional maximum number of results")
                .setRequired(Arrays.asList("operation"))
                .build();

        return new ToolSchema(
                ToolNames.LSP,
                DESCRIPTION,
                ToolKind.IDE,
                parameters,
                "1.0.0"
        );
    }

    /**
     * 设置用于处理操作的 LSP 客户端。
     */
    public void setLspClient(LspClient client) {
        this.lspClient = client;
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String operation = params.path("operation").asText("");

        logger.debug("LSP: operation={}", operation);

        // 验证操作
        if (operation.isEmpty() || !VALID_OPERATIONS.contains(operation)) {
            return ToolResult.failure(
                    "Invalid or missing operation. Valid operations: " + String.join(", ", VALID_OPERATIONS)
            );
        }

        // 检查 LSP 是否可用
        if (lspClient == null || !lspClient.isEnabled()) {
            return ToolResult.failure(
                    "LSP " + getOperationLabel(operation) + " is unavailable (LSP disabled or not initialized)."
            );
        }

        // 根据操作验证必需的参数
        String validationError = validateOperationParams(operation, params);
        if (validationError != null) {
            return ToolResult.failure(validationError);
        }

        try {
            // 执行操作
            switch (operation) {
                case "goToDefinition":
                    return executeDefinitions(params);
                case "findReferences":
                    return executeReferences(params);
                case "hover":
                    return executeHover(params);
                case "documentSymbol":
                    return executeDocumentSymbols(params);
                case "workspaceSymbol":
                    return executeWorkspaceSymbols(params);
                case "goToImplementation":
                    return executeImplementations(params);
                case "prepareCallHierarchy":
                    return executePrepareCallHierarchy(params);
                case "incomingCalls":
                    return executeIncomingCalls(params);
                case "outgoingCalls":
                    return executeOutgoingCalls(params);
                case "diagnostics":
                    return executeDiagnostics(params);
                case "workspaceDiagnostics":
                    return executeWorkspaceDiagnostics(params);
                case "codeActions":
                    return executeCodeActions(params);
                default:
                    return ToolResult.failure("Unsupported LSP operation: " + operation);
            }
        } catch (Exception e) {
            logger.error("Error executing LSP operation: {}", e.getMessage(), e);
            return ToolResult.failure("LSP " + getOperationLabel(operation) + " failed: " + e.getMessage());
        }
    }

    /**
     * 根据操作类型验证参数。
     */
    private String validateOperationParams(String operation, JsonNode params) {
        if (LOCATION_REQUIRED_OPERATIONS.contains(operation)) {
            if (!params.has("filePath") || params.path("filePath").asText("").isEmpty()) {
                return "filePath is required for " + operation + " operation.";
            }
            if (!params.has("line") || !params.path("line").isNumber()) {
                return "line is required for " + operation + " operation.";
            }
        }

        if (FILE_REQUIRED_OPERATIONS.contains(operation)) {
            if (!params.has("filePath") || params.path("filePath").asText("").isEmpty()) {
                return "filePath is required for " + operation + " operation.";
            }
        }

        if (QUERY_REQUIRED_OPERATIONS.contains(operation)) {
            if (!params.has("query") || params.path("query").asText("").isEmpty()) {
                return "query is required for " + operation + " operation.";
            }
        }

        if (operation.equals("incomingCalls") || operation.equals("outgoingCalls")) {
            if (!params.has("callHierarchyItem")) {
                return "callHierarchyItem is required for " + operation + " operation.";
            }
        }

        if (operation.equals("codeActions")) {
            if (!params.has("filePath") || params.path("filePath").asText("").isEmpty()) {
                return "filePath is required for codeActions operation.";
            }
        }

        return null;
    }

    /**
     * 获取人类可读的操作标签。
     */
    private String getOperationLabel(String operation) {
        switch (operation) {
            case "goToDefinition":
                return "go-to-definition";
            case "findReferences":
                return "find-references";
            case "hover":
                return "hover";
            case "documentSymbol":
                return "document symbols";
            case "workspaceSymbol":
                return "workspace symbol search";
            case "goToImplementation":
                return "go-to-implementation";
            case "prepareCallHierarchy":
                return "prepare call hierarchy";
            case "incomingCalls":
                return "incoming calls";
            case "outgoingCalls":
                return "outgoing calls";
            case "diagnostics":
                return "diagnostics";
            case "workspaceDiagnostics":
                return "workspace diagnostics";
            case "codeActions":
                return "code actions";
            default:
                return operation;
        }
    }

    // 操作实现（委托给 LSP 客户端）

    private ToolResult executeDefinitions(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int line = params.path("line").asInt();
        int character = params.path("character").asInt(1);
        int limit = params.path("limit").asInt(20);

        List<LspLocation> definitions = lspClient.definitions(filePath, line, character, limit);

        if (definitions.isEmpty()) {
            return ToolResult.success("No definitions found at " + filePath + ":" + line + ":" + character);
        }

        StringBuilder result = new StringBuilder();
        result.append("Definitions found:\n");
        for (int i = 0; i < definitions.size(); i++) {
            LspLocation loc = definitions.get(i);
            result.append(String.format("%d. %s:%d:%d%n", i + 1, loc.uri, loc.startLine, loc.startChar));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeReferences(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int line = params.path("line").asInt();
        int character = params.path("character").asInt(1);
        boolean includeDeclaration = params.path("includeDeclaration").asBoolean(false);
        int limit = params.path("limit").asInt(50);

        List<LspLocation> references = lspClient.references(filePath, line, character, includeDeclaration, limit);

        if (references.isEmpty()) {
            return ToolResult.success("No references found at " + filePath + ":" + line + ":" + character);
        }

        StringBuilder result = new StringBuilder();
        result.append("References found:\n");
        for (int i = 0; i < references.size(); i++) {
            LspLocation loc = references.get(i);
            result.append(String.format("%d. %s:%d:%d%n", i + 1, loc.uri, loc.startLine, loc.startChar));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeHover(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int line = params.path("line").asInt();
        int character = params.path("character").asInt(1);

        String hoverInfo = lspClient.hover(filePath, line, character);

        if (hoverInfo == null || hoverInfo.isEmpty()) {
            return ToolResult.success("No hover information found at " + filePath + ":" + line + ":" + character);
        }

        return ToolResult.success("Hover information:\n" + hoverInfo);
    }

    private ToolResult executeDocumentSymbols(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int limit = params.path("limit").asInt(50);

        List<LspSymbol> symbols = lspClient.documentSymbols(filePath, limit);

        if (symbols.isEmpty()) {
            return ToolResult.success("No document symbols found in " + filePath);
        }

        StringBuilder result = new StringBuilder();
        result.append("Document symbols:\n");
        for (int i = 0; i < symbols.size(); i++) {
            LspSymbol sym = symbols.get(i);
            result.append(String.format("%d. %s (%s) at line %d%n", i + 1, sym.name, sym.kind, sym.line));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeWorkspaceSymbols(JsonNode params) {
        String query = params.path("query").asText();
        int limit = params.path("limit").asInt(20);

        List<LspSymbol> symbols = lspClient.workspaceSymbols(query, limit);

        if (symbols.isEmpty()) {
            return ToolResult.success("No symbols found for query: " + query);
        }

        StringBuilder result = new StringBuilder();
        result.append("Workspace symbols for \"" + query + "\":\n");
        for (int i = 0; i < symbols.size(); i++) {
            LspSymbol sym = symbols.get(i);
            result.append(String.format("%d. %s (%s) in %s:%d%n", i + 1, sym.name, sym.kind, sym.uri, sym.line));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeImplementations(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int line = params.path("line").asInt();
        int character = params.path("character").asInt(1);
        int limit = params.path("limit").asInt(20);

        List<LspLocation> implementations = lspClient.implementations(filePath, line, character, limit);

        if (implementations.isEmpty()) {
            return ToolResult.success("No implementations found at " + filePath + ":" + line + ":" + character);
        }

        StringBuilder result = new StringBuilder();
        result.append("Implementations found:\n");
        for (int i = 0; i < implementations.size(); i++) {
            LspLocation loc = implementations.get(i);
            result.append(String.format("%d. %s:%d:%d%n", i + 1, loc.uri, loc.startLine, loc.startChar));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executePrepareCallHierarchy(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int line = params.path("line").asInt();
        int character = params.path("character").asInt(1);
        int limit = params.path("limit").asInt(20);

        List<LspCallHierarchyItem> items = lspClient.prepareCallHierarchy(filePath, line, character, limit);

        if (items.isEmpty()) {
            return ToolResult.success("No call hierarchy items found at " + filePath + ":" + line + ":" + character);
        }

        StringBuilder result = new StringBuilder();
        result.append("Call hierarchy items:\n");
        for (int i = 0; i < items.size(); i++) {
            LspCallHierarchyItem item = items.get(i);
            result.append(String.format("%d. %s (%s) at %s:%d%n", i + 1, item.name, item.kind, item.uri, item.line));
        }

        // 添加 JSON 表示以供后续调用使用
        try {
            String json = objectMapper.writeValueAsString(items);
            result.append("\n\nCall hierarchy items (JSON):\n").append(json);
        } catch (Exception e) {
            logger.warn("Could not serialize call hierarchy items", e);
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeIncomingCalls(JsonNode params) {
        JsonNode itemNode = params.path("callHierarchyItem");
        int limit = params.path("limit").asInt(20);

        List<LspCallHierarchyItem> calls = lspClient.incomingCalls(itemNode, limit);

        if (calls.isEmpty()) {
            return ToolResult.success("No incoming calls found.");
        }

        StringBuilder result = new StringBuilder();
        result.append("Incoming calls:\n");
        for (int i = 0; i < calls.size(); i++) {
            LspCallHierarchyItem item = calls.get(i);
            result.append(String.format("%d. %s (%s) at %s:%d%n", i + 1, item.name, item.kind, item.uri, item.line));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeOutgoingCalls(JsonNode params) {
        JsonNode itemNode = params.path("callHierarchyItem");
        int limit = params.path("limit").asInt(20);

        List<LspCallHierarchyItem> calls = lspClient.outgoingCalls(itemNode, limit);

        if (calls.isEmpty()) {
            return ToolResult.success("No outgoing calls found.");
        }

        StringBuilder result = new StringBuilder();
        result.append("Outgoing calls:\n");
        for (int i = 0; i < calls.size(); i++) {
            LspCallHierarchyItem item = calls.get(i);
            result.append(String.format("%d. %s (%s) at %s:%d%n", i + 1, item.name, item.kind, item.uri, item.line));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeDiagnostics(JsonNode params) {
        String filePath = params.path("filePath").asText();

        List<LspDiagnostic> diagnostics = lspClient.diagnostics(filePath);

        if (diagnostics.isEmpty()) {
            return ToolResult.success("No diagnostics found in " + filePath);
        }

        StringBuilder result = new StringBuilder();
        result.append("Diagnostics for " + filePath + ":\n");
        for (int i = 0; i < diagnostics.size(); i++) {
            LspDiagnostic diag = diagnostics.get(i);
            result.append(String.format("%d. [%s] %d:%d: %s%n",
                    i + 1, diag.severity.toUpperCase(), diag.line, diag.character, diag.message));
        }

        return ToolResult.success(result.toString());
    }

    private ToolResult executeWorkspaceDiagnostics(JsonNode params) {
        int limit = params.path("limit").asInt(50);

        Map<String, List<LspDiagnostic>> allDiagnostics = lspClient.workspaceDiagnostics(limit);

        if (allDiagnostics.isEmpty()) {
            return ToolResult.success("No diagnostics found in the workspace.");
        }

        StringBuilder result = new StringBuilder();
        result.append("Workspace diagnostics:\n");

        int totalIssues = 0;
        for (Map.Entry<String, List<LspDiagnostic>> entry : allDiagnostics.entrySet()) {
            result.append("\n" + entry.getKey() + ":\n");
            for (LspDiagnostic diag : entry.getValue()) {
                result.append(String.format("  [%s] %d:%d: %s%n",
                        diag.severity.toUpperCase(), diag.line, diag.character, diag.message));
                totalIssues++;
            }
        }

        result.insert(0, "Found " + totalIssues + " issues in " + allDiagnostics.size() + " files.\n");

        return ToolResult.success(result.toString());
    }

    private ToolResult executeCodeActions(JsonNode params) {
        String filePath = params.path("filePath").asText();
        int line = params.path("line").asInt(1);
        int character = params.path("character").asInt(1);
        int endLine = params.path("endLine").asInt(line);
        int endCharacter = params.path("endCharacter").asInt(character);
        int limit = params.path("limit").asInt(20);

        List<LspCodeAction> actions = lspClient.codeActions(filePath, line, character, endLine, endCharacter, limit);

        if (actions.isEmpty()) {
            return ToolResult.success("No code actions available at " + filePath + ":" + line + ":" + character);
        }

        StringBuilder result = new StringBuilder();
        result.append("Code actions:\n");
        for (int i = 0; i < actions.size(); i++) {
            LspCodeAction action = actions.get(i);
            String flags = "";
            if (action.isPreferred) flags += " ★";
            if (action.hasEdit) flags += " (has edit)";
            if (action.hasCommand) flags += " (has command)";
            result.append(String.format("%d. %s [%s]%s%n", i + 1, action.title, action.kind, flags));
        }

        return ToolResult.success(result.toString());
    }

    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("operation") || params.path("operation").asText("").isEmpty()) {
            return "The 'operation' parameter is required.";
        }
        String operation = params.path("operation").asText();
        if (!VALID_OPERATIONS.contains(operation)) {
            return "Invalid operation. Valid operations: " + String.join(", ", VALID_OPERATIONS);
        }
        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // LSP 操作是只读的
    }

    // LSP 数据类

    public static class LspLocation {
        /**
         * 文件的 URI 路径
         */
        public String uri;
        /**
         * 起始行号（从1开始）
         */
        public int startLine;
        /**
         * 起始列号（从1开始）
         */
        public int startChar;
        /**
         * 结束行号（从1开始）
         */
        public int endLine;
        /**
         * 结束列号（从1开始）
         */
        public int endChar;
    }

    public static class LspSymbol {
        /**
         * 符号名称
         */
        public String name;
        /**
         * 符号类型（如：class、method、field等）
         */
        public String kind;
        /**
         * 符号所在文件的 URI 路径
         */
        public String uri;
        /**
         * 符号所在行号（从1开始）
         */
        public int line;
        /**
         * 符号所在的容器名称（如类名、命名空间等）
         */
        public String containerName;
    }

    public static class LspCallHierarchyItem {
        /**
         * 调用层级项的名称
         */
        public String name;
        /**
         * 调用层级项的类型（如：method、function等）
         */
        public String kind;
        /**
         * 调用层级项所在文件的 URI 路径
         */
        public String uri;
        /**
         * 调用层级项所在行号（从1开始）
         */
        public int line;
        /**
         * 调用层级项的详细信息
         */
        public String detail;
    }

    public static class LspDiagnostic {
        /**
         * 诊断严重级别：error（错误）、warning（警告）、info（信息）、hint（提示）
         */
        public String severity;
        /**
         * 诊断所在行号（从1开始）
         */
        public int line;
        /**
         * 诊断所在列号（从1开始）
         */
        public int character;
        /**
         * 诊断消息内容
         */
        public String message;
        /**
         * 诊断代码标识
         */
        public String code;
        /**
         * 诊断来源（如：编译器、linter等）
         */
        public String source;
    }

    public static class LspCodeAction {
        /**
         * 代码操作的标题
         */
        public String title;
        /**
         * 代码操作的类型（如：quickfix、refactor等）
         */
        public String kind;
        /**
         * 是否为首选操作
         */
        public boolean isPreferred;
        /**
         * 是否包含编辑操作
         */
        public boolean hasEdit;
        /**
         * 是否包含命令操作
         */
        public boolean hasCommand;
    }

    /**
     * LSP 客户端操作接口。
     */
    /**
     * LSP 客户端操作接口。
     */
    public interface LspClient {
        /**
         * 检查 LSP 客户端是否已启用。
         *
         * @return 如果 LSP 客户端已启用则返回 true，否则返回 false
         */
        boolean isEnabled();

        /**
         * 查找符号的定义位置。
         *
         * @param filePath 文件路径
         * @param line 行号（从1开始）
         * @param character 列号（从1开始）
         * @param limit 返回结果的最大数量
         * @return 定义位置列表
         */
        List<LspLocation> definitions(String filePath, int line, int character, int limit);

        /**
         * 查找符号的所有引用位置。
         *
         * @param filePath 文件路径
         * @param line 行号（从1开始）
         * @param character 列号（从1开始）
         * @param includeDeclaration 是否包含声明位置
         * @param limit 返回结果的最大数量
         * @return 引用位置列表
         */
        List<LspLocation> references(String filePath, int line, int character, boolean includeDeclaration, int limit);

        /**
         * 获取指定位置的悬停信息。
         *
         * @param filePath 文件路径
         * @param line 行号（从1开始）
         * @param character 列号（从1开始）
         * @return 悬停信息文本
         */
        String hover(String filePath, int line, int character);

        /**
         * 获取文档中的所有符号。
         *
         * @param filePath 文件路径
         * @param limit 返回结果的最大数量
         * @return 文档符号列表
         */
        List<LspSymbol> documentSymbols(String filePath, int limit);

        /**
         * 在工作区中搜索符号。
         *
         * @param query 搜索查询字符串
         * @param limit 返回结果的最大数量
         * @return 工作区符号列表
         */
        List<LspSymbol> workspaceSymbols(String query, int limit);

        /**
         * 查找接口或抽象类的实现位置。
         *
         * @param filePath 文件路径
         * @param line 行号（从1开始）
         * @param character 列号（从1开始）
         * @param limit 返回结果的最大数量
         * @return 实现位置列表
         */
        List<LspLocation> implementations(String filePath, int line, int character, int limit);

        /**
         * 准备调用层级结构。
         *
         * @param filePath 文件路径
         * @param line 行号（从1开始）
         * @param character 列号（从1开始）
         * @param limit 返回结果的最大数量
         * @return 调用层级项列表
         */
        List<LspCallHierarchyItem> prepareCallHierarchy(String filePath, int line, int character, int limit);

        /**
         * 获取指定项的传入调用。
         *
         * @param item 调用层级项的 JSON 表示
         * @param limit 返回结果的最大数量
         * @return 传入调用列表
         */
        List<LspCallHierarchyItem> incomingCalls(JsonNode item, int limit);

        /**
         * 获取指定项的传出调用。
         *
         * @param item 调用层级项的 JSON 表示
         * @param limit 返回结果的最大数量
         * @return 传出调用列表
         */
        List<LspCallHierarchyItem> outgoingCalls(JsonNode item, int limit);

        /**
         * 获取文件的诊断信息（错误、警告等）。
         *
         * @param filePath 文件路径
         * @return 诊断信息列表
         */
        List<LspDiagnostic> diagnostics(String filePath);

        /**
         * 获取工作区的所有诊断信息。
         *
         * @param limit 返回结果的最大数量
         * @return 文件路径到诊断信息列表的映射
         */
        Map<String, List<LspDiagnostic>> workspaceDiagnostics(int limit);

        /**
         * 获取指定位置的代码操作（如快速修复、重构等）。
         *
         * @param filePath 文件路径
         * @param line 起始行号（从1开始）
         * @param character 起始列号（从1开始）
         * @param endLine 结束行号（从1开始）
         * @param endCharacter 结束列号（从1开始）
         * @param limit 返回结果的最大数量
         * @return 代码操作列表
         */
        List<LspCodeAction> codeActions(String filePath, int line, int character, int endLine, int endCharacter, int limit);
    }
}
