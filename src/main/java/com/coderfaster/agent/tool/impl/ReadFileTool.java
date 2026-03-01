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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * ReadFile 工具 - 读取并返回指定文件的内容。
 *
 * 基于 coderfaster-agent read-file 实现
 *
 * 如果文件较大，内容将被截断。工具的响应会明确指示是否发生了截断，
 * 并提供如何使用 'offset' 和 'limit' 参数读取更多文件内容的详细信息。
 *
 * 处理文本、图像（PNG、JPG、GIF、WEBP、SVG、BMP）和 PDF 文件。
 * 对于文本文件，可以读取特定的行范围。
 */
public class ReadFileTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    /** 默认读取的最大行数 */
    private static final int DEFAULT_MAX_LINES = 2000;

    /** 最大可读取的文件大小 (10MB) */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /** 支持的图像扩展名 */
    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".bmp"
    );

    /** 需要检测的二进制文件扩展名 */
    private static final List<String> BINARY_EXTENSIONS = Arrays.asList(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
        ".zip", ".tar", ".gz", ".rar", ".7z",
        ".exe", ".dll", ".so", ".dylib",
        ".class", ".jar", ".war",
        ".mp3", ".mp4", ".avi", ".mov", ".wmv",
        ".bin", ".dat"
    );

    private static final String DESCRIPTION =
        "Reads and returns the content of a specified file. If the file is large, the content " +
        "will be truncated. The tool's response will clearly indicate if truncation has occurred " +
        "and will provide details on how to read more of the file using the 'offset' and 'limit' " +
        "parameters. Handles text, images (PNG, JPG, GIF, WEBP, SVG, BMP), and PDF files. " +
        "For text files, it can read specific line ranges.";

    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("absolute_path", "string",
                "The absolute path to the file to read (e.g., '/home/user/project/file.txt'). " +
                "Relative paths are not supported. You must provide an absolute path.")
            .addProperty("offset", "number",
                "Optional: For text files, the 0-based line number to start reading from. " +
                "Requires 'limit' to be set. Use for paginating through large files.")
            .addProperty("limit", "number",
                "Optional: For text files, maximum number of lines to read. Use with 'offset' " +
                "to paginate through large files. If omitted, reads the entire file (if feasible, " +
                "up to a default limit).")
            .setRequired(Arrays.asList("absolute_path"))
            .build();

        return new ToolSchema(
            ToolNames.READ_FILE,
            DESCRIPTION,
            ToolKind.READ,
            parameters,
            "1.0.0"
        );
    }

    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String pathParam = params.path("absolute_path").asText("");
        Integer offset = params.has("offset") && !params.path("offset").isNull()
            ? params.path("offset").asInt() : null;
        Integer limit = params.has("limit") && !params.path("limit").isNull()
            ? params.path("limit").asInt() : null;

        logger.debug("ReadFile: path={}, offset={}, limit={}", pathParam, offset, limit);

        if (pathParam.isEmpty()) {
            return ToolResult.failure("The 'absolute_path' parameter must be non-empty.");
        }

        // 解析路径（支持相对路径）
        Path filePath = PathResolver.resolve(pathParam, context);
        String absolutePath = filePath.toAbsolutePath().toString();

        // 验证文件存在性和类型
        ToolResult fileValidation = validateFileExistence(filePath, absolutePath);
        if (!fileValidation.isSuccess()) {
            return fileValidation;
        }

        // 验证偏移量和限制参数
        ToolResult rangeValidation = validateOffsetAndLimit(offset, limit);
        if (!rangeValidation.isSuccess()) {
            return rangeValidation;
        }

        // 处理文件读取
        return processFileReading(filePath, absolutePath, offset, limit);
    }

    /**
     * 验证文件是否存在以及是否为文件类型。
     */
    private ToolResult validateFileExistence(Path filePath, String absolutePath) {
        if (!Files.exists(filePath)) {
            return ToolResult.failure("File not found: " + absolutePath);
        }

        if (Files.isDirectory(filePath)) {
            return ToolResult.failure("Path is a directory, not a file: " + absolutePath);
        }

        return ToolResult.success("");
    }

    /**
     * 验证偏移量和限制参数的有效性。
     */
    private ToolResult validateOffsetAndLimit(Integer offset, Integer limit) {
        if (offset != null && offset < 0) {
            return ToolResult.failure("Offset must be a non-negative number");
        }
        if (limit != null && limit <= 0) {
            return ToolResult.failure("Limit must be a positive number");
        }
        return ToolResult.success("");
    }

    /**
     * 处理文件读取逻辑，根据文件类型选择合适的读取方式。
     */
    private ToolResult processFileReading(Path filePath, String absolutePath,
                                          Integer offset, Integer limit) {
        try {
            // 检查文件大小
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE) {
                return ToolResult.failure(
                    "File is too large to read (" + formatSize(fileSize) +
                    "). Maximum supported size is " + formatSize(MAX_FILE_SIZE)
                );
            }

            String extension = getFileExtension(absolutePath).toLowerCase();

            // 处理图像文件
            if (isImageFile(extension)) {
                return readImageFile(filePath, extension);
            }

            // 处理二进制文件
            if (isBinaryFile(extension)) {
                return ToolResult.failure(
                    "Cannot read binary file: " + absolutePath +
                    ". This file type (" + extension + ") is not supported for reading."
                );
            }

            // 处理文本文件
            return readTextFile(filePath, offset, limit);

        } catch (IOException e) {
            logger.error("Error reading file: {}", absolutePath, e);
            return ToolResult.failure("Error reading file: " + e.getMessage());
        }
    }

    /**
     * 使用可选的偏移量和限制读取文本文件。
     */
    private ToolResult readTextFile(Path filePath, Integer offset, Integer limit) throws IOException {
        List<String> allLines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int totalLines = allLines.size();

        // 处理空文件
        if (totalLines == 0) {
            return ToolResult.success("File is empty.");
        }

        // 计算有效的偏移量和限制
        int effectiveOffset = offset != null ? offset : 0;
        int effectiveLimit = limit != null ? limit : DEFAULT_MAX_LINES;

        // 验证偏移量
        if (effectiveOffset >= totalLines) {
            return ToolResult.failure(
                "Offset " + effectiveOffset + " is beyond file length (" + totalLines + " lines)"
            );
        }

        // 计算结束行
        int endLine = Math.min(effectiveOffset + effectiveLimit, totalLines);
        boolean isTruncated = endLine < totalLines || effectiveOffset > 0;

        // 提取行
        List<String> selectedLines = allLines.subList(effectiveOffset, endLine);

        // 格式化输出（带行号）
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < selectedLines.size(); i++) {
            int lineNumber = effectiveOffset + i + 1;  // 基于1的行号
            content.append(String.format("%6d|%s%n", lineNumber, selectedLines.get(i)));
        }

        String result = content.toString();
        String displayResult;

        if (isTruncated) {
            int startLine = effectiveOffset + 1;  // 基于1
            int shownEndLine = effectiveOffset + selectedLines.size();
            displayResult = String.format(
                "Showing lines %d-%d of %d total lines.%n%n---%n%n%s",
                startLine, shownEndLine, totalLines, result
            );
        } else {
            displayResult = result;
        }

        return ToolResult.success(displayResult);
    }

    /**
     * 读取图像文件并返回 base64 编码的内容。
     */
    private ToolResult readImageFile(Path filePath, String extension) throws IOException {
        byte[] imageBytes = Files.readAllBytes(filePath);
        String base64Content = Base64.getEncoder().encodeToString(imageBytes);

        String mimeType = getMimeType(extension);

        // 对于 SVG 文件，我们可以返回文本内容
        if (".svg".equals(extension)) {
            String svgContent = new String(imageBytes, StandardCharsets.UTF_8);
            return ToolResult.builder()
                .success(true)
                .content("SVG image content:\n" + svgContent)
                .displayData("data:" + mimeType + ";base64," + base64Content)
                .build();
        }

        // 对于其他图像，返回尺寸和 base64
        try {
            BufferedImage image = ImageIO.read(filePath.toFile());
            if (image != null) {
                int width = image.getWidth();
                int height = image.getHeight();
                return ToolResult.builder()
                    .success(true)
                    .content(String.format(
                        "Image file: %s%nDimensions: %dx%d pixels%nSize: %s%nFormat: %s",
                        filePath.getFileName(), width, height,
                        formatSize(imageBytes.length), extension.substring(1).toUpperCase()
                    ))
                    .displayData("data:" + mimeType + ";base64," + base64Content)
                    .build();
            }
        } catch (Exception e) {
            logger.warn("Could not read image dimensions: {}", e.getMessage());
        }

        // 如果无法读取尺寸，则返回不带尺寸的结果
        return ToolResult.builder()
            .success(true)
            .content(String.format(
                "Image file: %s%nSize: %s%nFormat: %s",
                filePath.getFileName(), formatSize(imageBytes.length),
                extension.substring(1).toUpperCase()
            ))
            .displayData("data:" + mimeType + ";base64," + base64Content)
            .build();
    }

    /**
     * 检查文件扩展名是否表示图像文件。
     */
    private boolean isImageFile(String extension) {
        return IMAGE_EXTENSIONS.contains(extension);
    }

    /**
     * 检查文件扩展名是否表示二进制文件。
     */
    private boolean isBinaryFile(String extension) {
        return BINARY_EXTENSIONS.contains(extension);
    }

    /**
     * 获取包含点号的文件扩展名。
     */
    private String getFileExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot);
    }

    /**
     * 获取图像扩展名对应的 MIME 类型。
     */
    private String getMimeType(String extension) {
        switch (extension) {
            case ".png": return "image/png";
            case ".jpg":
            case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".svg": return "image/svg+xml";
            case ".bmp": return "image/bmp";
            default: return "application/octet-stream";
        }
    }

    /**
     * 格式化文件大小以供显示。
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("absolute_path") || params.path("absolute_path").asText("").isEmpty()) {
            return "The 'absolute_path' parameter is required and must be non-empty.";
        }
        return null;
    }

    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 读取操作不需要确认
    }
}
