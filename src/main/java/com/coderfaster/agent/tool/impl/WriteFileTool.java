package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.PathResolver;
import com.coderfaster.agent.tool.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * WriteFile 工具 - 将内容写入本地文件系统中的指定文件。
 *
 * 基于 coderfaster-agent write-file 实现
 *
 * 用户可以修改 `content`。如果修改了，响应中会说明。
 */
public class WriteFileTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(WriteFileTool.class);

    private static final String DESCRIPTION =
        "Writes content to a specified file in the local filesystem.\n\n" +
        "The user has the ability to modify `content`. If modified, this will be stated in the response.";

    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("file_path", "string",
                "The absolute path to the file to write to (e.g., '/home/user/project/file.txt'). " +
                "Relative paths are not supported.")
            .addProperty("content", "string",
                "The content to write to the file.")
            .setRequired(Arrays.asList("file_path", "content"))
            .build();

        return new ToolSchema(
            ToolNames.WRITE_FILE,
            DESCRIPTION,
            ToolKind.EDIT,
            parameters,
            "1.0.0"
        );
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String filePathParam = params.path("file_path").asText("");
        String content = params.path("content").asText("");

        logger.debug("WriteFile: path={}, content.length={}", filePathParam, content.length());

        if (filePathParam.isEmpty()) {
            return ToolResult.failure("Missing or empty \"file_path\"");
        }

        // 解析路径（支持相对路径）
        Path path = PathResolver.resolve(filePathParam, context);
        String filePath = path.toAbsolutePath().toString();

        // 验证工作空间边界
        if (!PathResolver.isWithinWorkspace(path, context)) {
            return ToolResult.failure(
                "File path must be within workspace directory: " + 
                PathResolver.getWorkingDirectory(context)
            );
        }

        // 执行文件写入
        return performFileWrite(path, filePath, content);
    }

    /**
     * 执行文件写入操作。
     */
    private ToolResult performFileWrite(Path path, String filePath, String content) {
        try {
            // 检查路径是否是目录
            if (Files.exists(path) && Files.isDirectory(path)) {
                return ToolResult.failure("Path is a directory, not a file: " + filePath);
            }

            // 检查是新建文件还是覆盖
            boolean isNewFile = !Files.exists(path);

            // 准备文件写入
            prepareFileWrite(path);

            // 写入文件
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));

            // 生成成功消息
            return buildSuccessResult(filePath, content, isNewFile);

        } catch (IOException e) {
            return handleWriteError(filePath, e);
        }
    }

    /**
     * 准备文件写入，创建必要的父目录。
     */
    private void prepareFileWrite(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
    }

    /**
     * 构建成功写入的结果消息。
     */
    private ToolResult buildSuccessResult(String filePath, String content, boolean isNewFile) {
        String successMessage;
        if (isNewFile) {
            successMessage = "Successfully created and wrote to new file: " + filePath + ".";
        } else {
            successMessage = "Successfully overwrote file: " + filePath + ".";
        }

        // 统计行数以获取信息
        int lineCount = content.isEmpty() ? 0 : content.split("\n", -1).length;
        successMessage += String.format(" (%d lines)", lineCount);

        return ToolResult.success(successMessage);
    }

    /**
     * 处理文件写入错误。
     */
    private ToolResult handleWriteError(String filePath, IOException e) {
        logger.error("Error writing file: {}", filePath, e);

        String errorType = getErrorType(e);
        String errorMsg = "Error writing to file '" + filePath + "': " + e.getMessage();
        if (errorType != null) {
            errorMsg += " (" + errorType + ")";
        }

        return ToolResult.failure(errorMsg);
    }

    /**
     * 从 IOException 获取描述性的错误类型。
     */
    private String getErrorType(IOException e) {
        String message = e.getMessage();
        if (message != null) {
            message = message.toLowerCase();
            if (message.contains("permission denied") || message.contains("access denied")) {
                return "PERMISSION_DENIED";
            }
            if (message.contains("no space left") || message.contains("disk full")) {
                return "NO_SPACE_LEFT";
            }
            if (message.contains("is a directory")) {
                return "TARGET_IS_DIRECTORY";
            }
        }
        return null;
    }

    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("file_path") || params.path("file_path").asText("").isEmpty()) {
            return "The 'file_path' parameter is required and must be non-empty.";
        }
        if (!params.has("content")) {
            return "The 'content' parameter is required.";
        }
        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return true; // 写入操作默认需要确认
    }
}
