package com.coderfaster.agent.utils;

import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 大模型的工具类
 * @author youye.lw
 * @date 2026/2/27
 */
public class ModelUtils {

    private static final List<String> MULTI_MODAL_MODELS = List.of("qwen3.5-plus");

    /**
     * 判断是否是多模态模式
     *
     * @param modelName 模型名称
     * @return 是否是多模态模式
     */
    public static boolean isMultiModalMode(String modelName) {
        if (StringUtils.isBlank(modelName)) {
            return false;
        }
        if (modelName.contains("vl")) {
            return true;
        }
        return MULTI_MODAL_MODELS.contains(modelName);
    }
}
