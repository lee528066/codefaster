package com.coderfaster.agent.config.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 配置初始化器
 * 负责在启动时检查配置文件，如不存在则引导用户创建
 */
public class ConfigInitializer {

    private static final Logger log = LoggerFactory.getLogger(ConfigInitializer.class);
    
    /**
     * 初始化配置（交互式）
     * @return 加载或创建的配置
     */
    public static LocalConfig initialize() {
        return initialize(true);
    }
    
    /**
     * 初始化配置
     * @param interactive 是否交互式引导
     * @return 加载或创建的配置
     */
    public static LocalConfig initialize(boolean interactive) {
        try {
            // 尝试加载现有配置
            if (LocalConfig.configExists()) {
                LocalConfig config = LocalConfig.load();
                if (config.isValid()) {
                    log.info("已加载本地配置：{}", LocalConfig.getConfigPath());
                    return config;
                } else {
                    log.warn("配置文件无效，需要重新配置");
                }
            }
            
            // 配置不存在或无效，需要创建
            if (interactive) {
                return createConfigInteractively();
            } else {
                throw new IllegalStateException("配置文件不存在且非交互模式");
            }
            
        } catch (IOException e) {
            log.error("加载配置失败：{}", e.getMessage());
            if (interactive) {
                return createConfigInteractively();
            } else {
                throw new RuntimeException("配置加载失败", e);
            }
        }
    }
    
    /**
     * 交互式创建配置
     */
    private static LocalConfig createConfigInteractively() {
        System.out.println("\n========================================");
        System.out.println("  CodeFaster 配置向导");
        System.out.println("========================================\n");
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            LocalConfig.LocalConfigBuilder builder = LocalConfig.builder();
            
            // 1. 选择账号类型
            System.out.println("请选择账号类型：");
            System.out.println("  1) 普通账号（使用 DashScope API）");
            System.out.println("  2) Code Plan 账号（企业协议）");
            System.out.print("请输入选项 (1/2，默认 1): ");
            
            String authChoice = reader.readLine().trim();
            if ("2".equals(authChoice)) {
                builder.authType("CODE_PLAN");
                System.out.println("已选择：Code Plan 账号\n");
            } else {
                builder.authType("NORMAL");
                System.out.println("已选择：普通账号\n");
            }
            
            // 2. 输入 API Key
            System.out.print("请输入 API Key: ");
            String apiKey = reader.readLine().trim();
            if (apiKey.isEmpty()) {
                throw new IllegalArgumentException("API Key 不能为空");
            }
            builder.apiKey(apiKey);
            System.out.println("✓ API Key 已设置\n");
            
            // 3. 选择模型
            System.out.println("请选择默认模型：");
            System.out.println("  1) qwen3.5-plus (推荐)");
            System.out.println("  2) qwen3-coder-plus");
            System.out.println("  3) qwen-max");
            System.out.print("请输入选项 (1/2/3，默认 1): ");
            
            String modelChoice = reader.readLine().trim();
            switch (modelChoice) {
                case "2":
                    builder.modelName("qwen3-coder-plus");
                    break;
                case "3":
                    builder.modelName("qwen-max");
                    break;
                default:
                    builder.modelName("qwen3.5-plus");
            }
            System.out.println("✓ 模型已设置：" + builder.build().getModelName() + "\n");
            
            // 4. 确认并保存
            LocalConfig config = builder.build();
            System.out.println("正在保存配置...");
            config.save();
            
            System.out.println("\n========================================");
            System.out.println("  配置完成！");
            System.out.println("  配置文件：" + LocalConfig.getConfigPath());
            System.out.println("========================================\n");
            
            return config;
            
        } catch (IOException e) {
            log.error("配置创建失败：{}", e.getMessage());
            throw new RuntimeException("配置创建失败", e);
        }
    }
    
    /**
     * 快速检查配置是否存在
     * @return true 如果配置有效
     */
    public static boolean hasValidConfig() {
        try {
            if (!LocalConfig.configExists()) {
                return false;
            }
            LocalConfig config = LocalConfig.load();
            return config.isValid();
        } catch (Exception e) {
            return false;
        }
    }
}
