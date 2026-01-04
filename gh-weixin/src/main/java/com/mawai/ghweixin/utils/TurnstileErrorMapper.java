package com.mawai.ghweixin.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turnstile错误码映射工具类
 * 根据官方文档错误码返回友好的中文提示
 */
public class TurnstileErrorMapper {

    private static final Map<String, String> ERROR_MESSAGES = new HashMap<>();

    static {
        // 官方错误码映射
        ERROR_MESSAGES.put("missing-input-secret", "配置错误：缺少密钥");
        ERROR_MESSAGES.put("invalid-input-secret", "配置错误：密钥无效");
        ERROR_MESSAGES.put("missing-input-response", "验证失败：缺少验证token");
        ERROR_MESSAGES.put("invalid-input-response", "验证失败：token无效或已过期");
        ERROR_MESSAGES.put("bad-request", "请求错误：参数格式不正确");
        ERROR_MESSAGES.put("timeout-or-duplicate", "验证超时或重复提交");
        ERROR_MESSAGES.put("internal-error", "服务暂时不可用，请稍后重试");
    }

    /**
     * 根据错误码列表获取友好的错误提示
     *
     * @param errorCodes 错误码列表
     * @return 友好的错误提示
     */
    public static String getErrorMessage(List<String> errorCodes) {
        if (errorCodes == null || errorCodes.isEmpty()) {
            return "验证失败，请重试";
        }

        // 返回第一个错误的友好提示
        String firstError = errorCodes.get(0);
        return ERROR_MESSAGES.getOrDefault(firstError, "验证失败：" + firstError);
    }
}