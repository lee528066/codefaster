package com.coderfaster.agent.config.local;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地配置文件模型
 * 对应 ~/.codefaster/setting.json
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocalConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalConfig.class);
    
    /**
     * 认证类型：CODE_PLAN 或 NORMAL
     */
    @Builder.Default
    private String authType = "NORMAL";
    
    /**
     * API Key
     */
    private String apiKey;
    
    /**
     * 使用的模型名称
     */
    @Builder.Default
    private String modelName = "qwen3.5-plus";
    
    /**
     * Code Plan 专用 Base URL
     */
    @Builder.Default
    private String codePlanBaseUrl = "https://codeplan.aliyun.com/api";
    
    /**
     * 普通账号 Base URL
     */
    @Builder.Default
    private String normalBaseUrl = "https://dashscope.aliyuncs.com/api";
    
    /**
     * 是否使用 Code Plan（费用协议）
     */
    public boolean isCodePlan() {
        return "CODE_PLAN".equalsIgnoreCase(authType);
    }
    
    /**
     * 获取当前有效的 Base URL
     */
    public String getEffectiveBaseUrl() {
        return isCodePlan() ? codePlanBaseUrl : normalBaseUrl;
    }
    
    /**
     * 配置文件路径
     */
    public static Path getConfigPath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".codefaster", "setting.json");
    }
    
    /**
     * 配置目录路径
     */
    public static Path getConfigDir() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".codefaster");
    }
    
    /**
     * 检查配置文件是否存在
     */
    public static boolean configExists() {
        return Files.exists(getConfigPath());
    }
    
    /**
     * 加载配置文件
     */
    public static LocalConfig load() throws IOException {
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            throw new IOException("配置文件不存在：" + configPath);
        }
        
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(configPath.toFile(), LocalConfig.class);
    }
    
    /**
     * 保存配置文件
     */
    public void save() throws IOException {
        Path configPath = getConfigPath();
        
        // 确保目录存在
        Files.createDirectories(configPath.getParent());
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), this);
        
        log.info("配置文件已保存：{}", configPath);
    }
    
    /**
     * 验证配置是否完整
     */
    public boolean isValid() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return false;
        }
        if (modelName == null || modelName.trim().isEmpty()) {
            return false;
        }
        return true;
    }
}
