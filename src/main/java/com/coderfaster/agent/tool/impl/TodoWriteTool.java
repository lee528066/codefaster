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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * TodoWrite 工具 - 为当前编码会话创建和管理结构化任务列表。
 * 
 * 基于 coderfaster-agent todoWrite 实现
 * 
 * 这有助于跟踪进度、组织复杂任务并展示彻底性。
 * 它还帮助用户了解任务的进度和请求的整体进展。
 */
public class TodoWriteTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(TodoWriteTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /** 有效的待办事项状态 */
    private static final Set<String> VALID_STATUSES = new HashSet<>(Arrays.asList(
        "pending", "in_progress", "completed"
    ));
    
    /** 存储待办事项文件的目录 */
    private static final String TODO_SUBDIR = ".coderfaster/todos";
    
    private static final String DESCRIPTION = 
        "Use this tool to create and manage a structured task list for your current coding session. " +
        "This helps you track progress, organize complex tasks, and demonstrate thoroughness to the user.\n\n" +
        "## When to Use This Tool\n" +
        "Use this tool proactively in these scenarios:\n\n" +
        "1. Complex multi-step tasks - When a task requires 3 or more distinct steps or actions\n" +
        "2. Non-trivial and complex tasks - Tasks that require careful planning or multiple operations\n" +
        "3. User explicitly requests todo list - When the user directly asks you to use the todo list\n" +
        "4. User provides multiple tasks - When users provide a list of things to be done (numbered or comma-separated)\n" +
        "5. After receiving new instructions - Immediately capture user requirements as todos\n" +
        "6. When you start working on a task - Mark it as in_progress BEFORE beginning work\n" +
        "7. After completing a task - Mark it as completed and add any new follow-up tasks\n\n" +
        "## When NOT to Use This Tool\n\n" +
        "Skip using this tool when:\n" +
        "1. There is only a single, straightforward task\n" +
        "2. The task is trivial and tracking it provides no organizational benefit\n" +
        "3. The task can be completed in less than 3 trivial steps\n" +
        "4. The task is purely conversational or informational\n\n" +
        "## Task States\n\n" +
        "- pending: Task not yet started\n" +
        "- in_progress: Currently working on (limit to ONE task at a time)\n" +
        "- completed: Task finished successfully";
    
    @Override
    public ToolSchema getSchema() {
        // 构建待办事项项的模式
        JsonNode todoItemSchema = new JsonSchemaBuilder()
            .addProperty("id", "string", "Unique identifier for the TODO item")
            .addProperty("content", "string", "The description/content of the todo item")
            .addEnumProperty("status", Arrays.asList("pending", "in_progress", "completed"),
                "The current status of the TODO item")
            .setRequired(Arrays.asList("id", "content", "status"))
            .build();
        
        JsonNode parameters = new JsonSchemaBuilder()
            .addArrayProperty("todos", todoItemSchema, "The updated todo list")
            .setRequired(Arrays.asList("todos"))
            .build();
        
        return new ToolSchema(
            ToolNames.TODO_WRITE,
            DESCRIPTION,
            ToolKind.THINK,
            parameters,
            "1.0.0"
        );
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        logger.debug("TodoWrite: params={}", params);
        
        // 提取待办事项数组
        if (!params.has("todos") || !params.path("todos").isArray()) {
            return ToolResult.failure("Parameter 'todos' must be an array.");
        }
        
        ArrayNode todosArray = (ArrayNode) params.path("todos");
        List<TodoItem> todos = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        
        // 验证并解析待办事项
        for (int i = 0; i < todosArray.size(); i++) {
            JsonNode todoNode = todosArray.get(i);
            
            // 验证必填字段
            if (!todoNode.has("id") || todoNode.path("id").asText("").trim().isEmpty()) {
                return ToolResult.failure("Each todo must have a non-empty 'id' string.");
            }
            if (!todoNode.has("content") || todoNode.path("content").asText("").trim().isEmpty()) {
                return ToolResult.failure("Each todo must have a non-empty 'content' string.");
            }
            if (!todoNode.has("status")) {
                return ToolResult.failure("Each todo must have a 'status' field.");
            }
            
            String id = todoNode.path("id").asText();
            String content = todoNode.path("content").asText();
            String status = todoNode.path("status").asText();
            
            // 验证状态
            if (!VALID_STATUSES.contains(status)) {
                return ToolResult.failure(
                    "Each todo must have a valid 'status' (pending, in_progress, completed). Got: " + status
                );
            }
            
            // 检查重复的 ID
            if (seenIds.contains(id)) {
                return ToolResult.failure("Todo IDs must be unique within the array. Duplicate: " + id);
            }
            seenIds.add(id);
            
            todos.add(new TodoItem(id, content, status));
        }
        
        // 获取用于存储的会话 ID
        String sessionId = context != null ? context.getSessionId() : "default";
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "default";
        }
        
        try {
            // 将待办事项写入文件
            writeTodosToFile(todos, sessionId);
            
            // 构建响应
            if (todos.isEmpty()) {
                return ToolResult.builder()
                    .success(true)
                    .content("Todo list has been cleared.\n\n<system_reminder>\n" +
                        "Your todo list is now empty. DO NOT mention this explicitly to the user. " +
                        "You have no pending tasks in your todo list.\n</system_reminder>")
                    .displayData(buildTodoDisplay(todos))
                    .build();
            }
            
            String todosJson = objectMapper.writeValueAsString(todos);
            String llmContent = "Todos have been modified successfully. Ensure that you continue to use the " +
                "todo list to track your progress. Please proceed with the current tasks if applicable.\n\n" +
                "<system_reminder>\nYour todo list has changed. DO NOT mention this explicitly to the user. " +
                "Here are the latest contents of your todo list:\n\n" + todosJson + 
                "\n\nContinue on with the tasks at hand if applicable.\n</system_reminder>";
            
            return ToolResult.builder()
                .success(true)
                .content(llmContent)
                .displayData(buildTodoDisplay(todos))
                .build();
            
        } catch (IOException e) {
            logger.error("Error writing todos: {}", e.getMessage(), e);
            return ToolResult.failure("Failed to modify todos: " + e.getMessage());
        }
    }
    
    /**
     * 将待办事项写入文件以进行持久化。
     */
    private void writeTodosToFile(List<TodoItem> todos, String sessionId) throws IOException {
        String homeDir = System.getProperty("user.home");
        Path todoDir = Paths.get(homeDir, TODO_SUBDIR);
        Files.createDirectories(todoDir);
        
        Path todoFile = todoDir.resolve(sessionId + ".json");
        
        ObjectNode data = objectMapper.createObjectNode();
        ArrayNode todosArray = objectMapper.createArrayNode();
        
        for (TodoItem todo : todos) {
            ObjectNode todoNode = objectMapper.createObjectNode();
            todoNode.put("id", todo.id);
            todoNode.put("content", todo.content);
            todoNode.put("status", todo.status);
            todosArray.add(todoNode);
        }
        
        data.set("todos", todosArray);
        data.put("sessionId", sessionId);
        
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
        Files.write(todoFile, json.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 为待办事项构建显示数据。
     */
    private Object buildTodoDisplay(List<TodoItem> todos) {
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("type", "todo_list");
        
        List<Map<String, String>> todoList = new ArrayList<>();
        for (TodoItem todo : todos) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", todo.id);
            item.put("content", todo.content);
            item.put("status", todo.status);
            todoList.add(item);
        }
        display.put("todos", todoList);
        
        return display;
    }
    
    /**
     * 从文件读取特定会话的待办事项。
     */
    public static List<TodoItem> readTodosForSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "default";
        }
        
        String homeDir = System.getProperty("user.home");
        Path todoFile = Paths.get(homeDir, TODO_SUBDIR, sessionId + ".json");
        
        if (!Files.exists(todoFile)) {
            return new ArrayList<>();
        }
        
        try {
            String content = new String(Files.readAllBytes(todoFile), StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(content);
            
            List<TodoItem> todos = new ArrayList<>();
            if (data.has("todos") && data.path("todos").isArray()) {
                for (JsonNode todoNode : data.path("todos")) {
                    todos.add(new TodoItem(
                        todoNode.path("id").asText(),
                        todoNode.path("content").asText(),
                        todoNode.path("status").asText()
                    ));
                }
            }
            return todos;
            
        } catch (IOException e) {
            logger.error("Error reading todos: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 列出所有可用的待办事项会话文件。
     */
    public static List<String> listTodoSessions() {
        String homeDir = System.getProperty("user.home");
        Path todoDir = Paths.get(homeDir, TODO_SUBDIR);
        
        if (!Files.exists(todoDir)) {
            return new ArrayList<>();
        }
        
        try {
            return Files.list(todoDir)
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .map(p -> p.getFileName().toString().replace(".json", ""))
                .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            logger.error("Error listing todo sessions: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
    
    /**
     * 待办事项数据类。
     */
    public static class TodoItem {
        public final String id;
        public final String content;
        public final String status;
        
        public TodoItem(String id, String content, String status) {
            this.id = id;
            this.content = content;
            this.status = status;
        }
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("todos") || !params.path("todos").isArray()) {
            return "Parameter 'todos' must be an array.";
        }
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 待办事项操作不需要确认
    }
}
