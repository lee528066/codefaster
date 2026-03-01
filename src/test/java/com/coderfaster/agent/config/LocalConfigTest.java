package com.coderfaster.agent.config;

import com.coderfaster.agent.config.local.LocalConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class LocalConfigTest {

    @Test
    public void testConfigPath() {
        Path configPath = LocalConfig.getConfigPath();
        assertNotNull(configPath);
        assertTrue(configPath.endsWith("setting.json"));
    }

    @Test
    public void testConfigValidation() {
        LocalConfig config = LocalConfig.builder()
                .authType("NORMAL")
                .apiKey("sk-test123")
                .modelName("qwen3.5-plus")
                .build();
        assertTrue(config.isValid());
    }

    @Test
    public void testConfigValidation_InvalidApiKey() {
        LocalConfig config = LocalConfig.builder()
                .authType("NORMAL")
                .apiKey("")
                .modelName("qwen3.5-plus")
                .build();
        assertFalse(config.isValid());
    }

    @Test
    public void testCodePlanBaseUrl() {
        LocalConfig config = LocalConfig.builder()
                .authType("CODE_PLAN")
                .apiKey("sk-test123")
                .modelName("qwen3.5-plus")
                .build();
        assertTrue(config.isCodePlan());
        assertEquals("https://codeplan.aliyun.com/api", config.getEffectiveBaseUrl());
    }
}
