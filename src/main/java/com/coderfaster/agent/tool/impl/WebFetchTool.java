package com.coderfaster.agent.tool.impl;

import com.coderfaster.agent.model.ExecutionContext;
import com.coderfaster.agent.model.ToolKind;
import com.coderfaster.agent.model.ToolResult;
import com.coderfaster.agent.model.ToolSchema;
import com.coderfaster.agent.tool.BaseTool;
import com.coderfaster.agent.tool.JsonSchemaBuilder;
import com.coderfaster.agent.tool.ToolNames;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebFetch 工具 - 从指定的 URL 获取内容并进行处理。
 * 
 * 基于 coderfaster-agent web-fetch 实现
 * 
 * - 接受 URL 和提示作为输入
 * - 获取 URL 内容，将 HTML 转换为文本
 * - 返回内容以供分析
 * - 当需要检索和分析网络内容时使用此工具
 */
public class WebFetchTool extends BaseTool {
    
    private static final Logger logger = LoggerFactory.getLogger(WebFetchTool.class);
    
    /** URL 获取超时时间（毫秒） */
    private static final int URL_FETCH_TIMEOUT_MS = 30000;
    
    /** 处理的最大内容长度 */
    private static final int MAX_CONTENT_LENGTH = 100000;
    
    private static final String DESCRIPTION = 
        "Fetches content from a specified URL and processes it using an AI model\n" +
        "- Takes a URL and a prompt as input\n" +
        "- Fetches the URL content, converts HTML to markdown\n" +
        "- Processes the content with the prompt using a small, fast model\n" +
        "- Returns the model's response about the content\n" +
        "- Use this tool when you need to retrieve and analyze web content\n\n" +
        "Usage notes:\n" +
        "- IMPORTANT: If an MCP-provided web fetch tool is available, prefer using that tool instead of this one, as it may have fewer restrictions.\n" +
        "- The URL must be a fully-formed valid URL\n" +
        "- The prompt should describe what information you want to extract from the page\n" +
        "- This tool is read-only and does not modify any files\n" +
        "- Results may be summarized if the content is very large\n" +
        "- Supports both public and private/localhost URLs using direct fetch";
    
    @Override
    public ToolSchema getSchema() {
        JsonNode parameters = new JsonSchemaBuilder()
            .addProperty("url", "string",
                "The URL to fetch content from")
            .addProperty("prompt", "string",
                "The prompt to run on the fetched content")
            .setRequired(Arrays.asList("url", "prompt"))
            .build();
        
        return new ToolSchema(
            ToolNames.WEB_FETCH,
            DESCRIPTION,
            ToolKind.FETCH,
            parameters,
            "1.0.0"
        );
    }
    
    @Override
    public ToolResult execute(JsonNode params, ExecutionContext context) {
        String url = params.path("url").asText("");
        String prompt = params.path("prompt").asText("");
        
        logger.debug("WebFetch: url={}, prompt={}", url, prompt);
        
        // 验证 URL
        if (url.trim().isEmpty()) {
            return ToolResult.failure("The 'url' parameter cannot be empty.");
        }
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure("The 'url' must be a valid URL starting with http:// or https://.");
        }
        
        // 验证提示
        if (prompt.trim().isEmpty()) {
            return ToolResult.failure("The 'prompt' parameter cannot be empty.");
        }
        
        try {
            // 将 GitHub blob URL 转换为 raw URL
            String fetchUrl = url;
            if (url.contains("github.com") && url.contains("/blob/")) {
                fetchUrl = url
                    .replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/");
                logger.debug("Converted GitHub blob URL to raw URL: {}", fetchUrl);
            }
            
            // 获取内容
            String content = fetchUrl(fetchUrl);
            
            if (content == null || content.isEmpty()) {
                return ToolResult.failure("Failed to fetch content from URL: " + url);
            }
            
            // 如果需要，将 HTML 转换为文本
            String textContent = convertHtmlToText(content);
            
            // 如果太长则截断
            if (textContent.length() > MAX_CONTENT_LENGTH) {
                textContent = textContent.substring(0, MAX_CONTENT_LENGTH) + "\n\n[Content truncated...]";
            }
            
            // 使用提示上下文构建响应
            String responseContent = String.format(
                "Content fetched from %s\n\n" +
                "User's request: %s\n\n" +
                "---\n\n" +
                "%s\n\n" +
                "---\n\n" +
                "Please analyze the above content based on the user's request.",
                url, prompt, textContent
            );
            
            return ToolResult.builder()
                .success(true)
                .content(responseContent)
                .displayData("Content from " + url + " processed successfully.")
                .build();
            
        } catch (Exception e) {
            logger.error("Error fetching URL: {}", url, e);
            return ToolResult.failure("Error during fetch for " + url + ": " + e.getMessage());
        }
    }
    
    /**
     * 从 URL 获取内容。
     */
    private String fetchUrl(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(URL_FETCH_TIMEOUT_MS);
            connection.setReadTimeout(URL_FETCH_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (compatible; CodemateAgent/1.0)");
            connection.setRequestProperty("Accept", 
                "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7");
            
            int responseCode = connection.getResponseCode();
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Request failed with status code " + responseCode + " " + 
                    connection.getResponseMessage());
            }
            
            // 读取响应
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            
            return content.toString();
            
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * 将 HTML 内容转换为纯文本。
     * 这是一个简化的转换 - 在生产环境中，你可能想要使用像 jsoup 这样的库。
     */
    private String convertHtmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        
        // 检查是否可能是纯文本（不是 HTML）
        if (!html.contains("<") || !html.contains(">")) {
            return html;
        }
        
        String text = html;
        
        // 移除 script 和 style 元素
        text = text.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        
        // 移除 HTML 注释
        text = text.replaceAll("<!--.*?-->", "");
        
        // 将块元素转换为换行符
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p>", "\n\n");
        text = text.replaceAll("(?i)</div>", "\n");
        text = text.replaceAll("(?i)</li>", "\n");
        text = text.replaceAll("(?i)</tr>", "\n");
        text = text.replaceAll("(?i)<h[1-6][^>]*>", "\n\n");
        text = text.replaceAll("(?i)</h[1-6]>", "\n\n");
        
        // 转换列表项
        text = text.replaceAll("(?i)<li[^>]*>", "• ");
        
        // 移除所有剩余的 HTML 标签
        text = text.replaceAll("<[^>]+>", "");
        
        // 解码 HTML 实体
        text = decodeHtmlEntities(text);
        
        // 清理空白字符
        text = text.replaceAll("[ \\t]+", " ");  // 多个空格转换为单个空格
        text = text.replaceAll("\\n[ \\t]+", "\n");  // 移除行首空白
        text = text.replaceAll("[ \\t]+\\n", "\n");  // 移除行尾空白
        text = text.replaceAll("\\n{3,}", "\n\n");  // 多个换行符转换为双换行符
        
        return text.trim();
    }
    
    /**
     * 解码常见的 HTML 实体。
     */
    private String decodeHtmlEntities(String text) {
        text = text.replace("&nbsp;", " ");
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&#39;", "'");
        text = text.replace("&mdash;", "—");
        text = text.replace("&ndash;", "–");
        text = text.replace("&bull;", "•");
        text = text.replace("&hellip;", "...");
        text = text.replace("&copy;", "©");
        text = text.replace("&reg;", "®");
        text = text.replace("&trade;", "™");
        
        // 解码数字实体
        Pattern numericEntity = Pattern.compile("&#(\\d+);");
        Matcher matcher = numericEntity.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int charCode = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(sb, String.valueOf((char) charCode));
        }
        matcher.appendTail(sb);
        text = sb.toString();
        
        // 解码十六进制实体
        Pattern hexEntity = Pattern.compile("&#x([0-9a-fA-F]+);");
        matcher = hexEntity.matcher(text);
        sb = new StringBuffer();
        while (matcher.find()) {
            int charCode = Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(sb, String.valueOf((char) charCode));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }
    
    /**
     * 检查 URL 是否指向私有/本地地址。
     */
    public static boolean isPrivateUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String host = url.getHost();
            
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                return true;
            }
            
            // 检查私有 IP 范围
            if (host.matches("^10\\..*") ||
                host.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*") ||
                host.matches("^192\\.168\\..*")) {
                return true;
            }
            
            return false;
        } catch (MalformedURLException e) {
            return false;
        }
    }
    
    @Override
    public String validateParams(JsonNode params) {
        if (!params.has("url") || params.path("url").asText("").trim().isEmpty()) {
            return "The 'url' parameter cannot be empty.";
        }
        
        String url = params.path("url").asText("");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "The 'url' must be a valid URL starting with http:// or https://.";
        }
        
        if (!params.has("prompt") || params.path("prompt").asText("").trim().isEmpty()) {
            return "The 'prompt' parameter cannot be empty.";
        }
        
        return null;
    }
    
    @Override
    public boolean requiresConfirmation(JsonNode params) {
        return false; // 只读操作
    }
}
