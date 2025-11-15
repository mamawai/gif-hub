package com.mawai.ghgif.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mawai.ghgif.config.OpenAiModerationProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 内容审核服务 - 仅使用了文本审核
 * 使用OpenAI Moderation API (omni-moderation-latest模型)
 * 通过API中转服务访问
 * 使用OkHttp实现高性能HTTP请求
 */
@Slf4j
@Service
public class ModerationService {

    private static final String MODERATIONS_ENDPOINT = "/moderations";
    private static final String MEDIA_TYPE_JSON = "application/json; charset=utf-8";
    private static final double DEFAULT_THRESHOLD = 0.5;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OkHttpClient httpClient;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.base-url:https://api.openai-proxy.org/v1}")
    private String baseUrl;

    @Value("${openai.moderation.enabled:true}")
    private boolean moderationEnabled;

    @Value("${openai.moderation.model:omni-moderation-latest}")
    private String model;

    @Value("${openai.moderation.timeout:3000}")
    private int timeoutMs;

    @Value("${openai.moderation.fail-open:true}")
    private boolean failOpen;

    private final OpenAiModerationProperties moderationProperties;

    private Map<String, Double> categoryThresholds;

    public ModerationService(OpenAiModerationProperties moderationProperties) {
        this.moderationProperties = moderationProperties;
    }

    /**
     * 初始化OkHttpClient
     */
    @PostConstruct
    public void init() {
        Map<String, Double> thresholds = moderationProperties.getThresholds();
        this.categoryThresholds = thresholds != null ? new HashMap<>(thresholds) : new HashMap<>();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        log.info("ModerationService initialized: baseUrl={}, model={}, timeout={}ms", 
                baseUrl, model, timeoutMs);
        log.info("Custom thresholds loaded: {}", categoryThresholds);
    }

    /**
     * 审核文本内容
     */
    public ModerationResult moderateText(String text) {
        if (!moderationEnabled) {
            log.warn("内容审核已禁用，跳过审核");
            return ModerationResult.safe();
        }

        if (text == null || text.trim().isEmpty()) {
            return ModerationResult.safe();
        }

        try {
            Map<String, Object> request = Map.of("model", model, "input", text);
            JsonNode response = sendModerationRequest(request);
            return parseModerationResponse(response);
        } catch (Exception e) {
            return handleModerationError("文本", text, e);
        }
    }

    /**
     * 发送审核请求到OpenAI API
     */
    private JsonNode sendModerationRequest(Map<String, Object> request) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(request);
        RequestBody body = RequestBody.create(jsonBody, okhttp3.MediaType.parse(MEDIA_TYPE_JSON));
        
        Request httpRequest = new Request.Builder()
                .url(baseUrl + MODERATIONS_ENDPOINT)
                .post(body)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new ModerationException("审核API调用失败: HTTP " + response.code());
            }
            
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new ModerationException("审核API返回空响应");
            }
            
            return objectMapper.readTree(responseBody.string());
        }
    }

    /**
     * 处理审核错误
     */
    private ModerationResult handleModerationError(String type, String content, Exception e) {
        String truncated = content.length() > 50 ? content.substring(0, 50) + "..." : content;
        log.error("{}审核失败: content='{}', error={}", type, truncated, e.getMessage());
        return failOpen ? ModerationResult.safe() : ModerationResult.error("审核服务暂时不可用，请稍后重试");
    }

    /**
     * 提取响应中的第一个结果
     */
    private JsonNode extractFirstResult(JsonNode response) {
        JsonNode results = response.get("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            log.warn("审核响应格式异常: {}", response);
            return null;
        }
        return results.get(0);
    }

    /**
     * 提取所有类别分数
     */
    private Map<String, Double> extractScores(JsonNode result) {
        Map<String, Double> scores = new HashMap<>();
        JsonNode categoryScores = result.get("category_scores");
        if (categoryScores != null && categoryScores.isObject()) {
            categoryScores.fields().forEachRemaining(entry ->
                scores.put(entry.getKey(), entry.getValue().asDouble())
            );
        }
        return scores;
    }

    /**
     * 提取OpenAI判定的违规类别
     */
    private List<String> extractOpenAiViolations(JsonNode result) {
        List<String> violations = new ArrayList<>();
        JsonNode categories = result.get("categories");
        if (categories != null && categories.isObject()) {
            categories.fields().forEachRemaining(entry -> {
                if (entry.getValue().asBoolean()) {
                    violations.add(entry.getKey());
                }
            });
        }
        return violations;
    }

    /**
     * 使用自定义阈值检查违规类别
     */
    private List<String> checkCustomThresholds(Map<String, Double> scores) {
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() >= categoryThresholds.getOrDefault(
                        entry.getKey(), DEFAULT_THRESHOLD))
                .peek(entry -> log.debug("类别 {} 违规: score={}, threshold={}",
                        entry.getKey(), entry.getValue(),
                        categoryThresholds.getOrDefault(entry.getKey(), DEFAULT_THRESHOLD)))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 解析审核响应（优先判断OpenAI flag，再使用自定义阈值）
     */
    private ModerationResult parseModerationResponse(JsonNode response) {
        try {
            JsonNode result = extractFirstResult(response);
            if (result == null) return ModerationResult.safe();
            
            // 1. 优先判断OpenAI的flag，如果OpenAI都认为违规，直接拦截
            boolean openAiFlagged = result.path("flagged").asBoolean(false);
            if (openAiFlagged) {
                Map<String, Double> scores = extractScores(result);
                List<String> violations = extractOpenAiViolations(result);
                log.warn("OpenAI判定违规 - 违规类别: {}, 分数: {}", violations, scores);
                return ModerationResult.flagged(violations, scores);
            }
            
            // 2. OpenAI认为安全，使用自定义阈值二次判断
            Map<String, Double> scores = extractScores(result);
            List<String> customViolations = checkCustomThresholds(scores);
            
            if (customViolations.isEmpty()) {
                log.debug("内容审核通过（OpenAI安全 + 自定义阈值通过），分数: {}", scores);
                return ModerationResult.safe();
            }
            
            log.info("自定义阈值判定违规 - 违规类别: {}, 分数: {}", customViolations, scores);
            return ModerationResult.flagged(customViolations, scores);
        } catch (Exception e) {
            log.error("解析审核响应失败: {}", e.getMessage());
            return ModerationResult.safe();
        }
    }

    /**
     * 审核结果类
     */
    @Getter
    public static class ModerationResult {
        private final boolean safe;
        private final boolean error;
        private final String errorMessage;
        private final List<String> violatedCategories;
        private final Map<String, Double> categoryScores;

        private ModerationResult(boolean safe, boolean error, String errorMessage, 
                                List<String> violatedCategories, Map<String, Double> categoryScores) {
            this.safe = safe;
            this.error = error;
            this.errorMessage = errorMessage;
            this.violatedCategories = violatedCategories != null ? violatedCategories : new ArrayList<>();
            this.categoryScores = categoryScores != null ? categoryScores : new HashMap<>();
        }

        public static ModerationResult safe() {
            return new ModerationResult(true, false, null, null, null);
        }

        public static ModerationResult flagged(List<String> violatedCategories, Map<String, Double> categoryScores) {
            return new ModerationResult(false, false, null, violatedCategories, categoryScores);
        }

        public static ModerationResult error(String errorMessage) {
            return new ModerationResult(false, true, errorMessage, null, null);
        }

        public boolean isFlagged() {
            return !safe && !error;
        }

        /**
         * 获取中文违规提示信息（包含分数详情）
         */
        public String getViolationMessage() {
            if (safe) {
                return null;
            }
            if (error) {
                return errorMessage;
            }
            
            // 将英文类别转换为中文提示
            Map<String, String> categoryNames = getCategoryNames();

            List<String> details = violatedCategories.stream()
                    .map(cat -> {
                        String cnName = categoryNames.getOrDefault(cat, cat);
                        Double score = categoryScores.get(cat);
                        if (score != null) {
                            return String.format("%s(%.2f)", cnName, score);
                        }
                        return cnName;
                    })
                    .toList();
            
            return "内容违规，包含：" + String.join("、", details);
        }
        
        /**
         * 获取简化的违规提示信息（不含分数）
         */
        public String getSimpleViolationMessage() {
            if (safe || error) {
                return getViolationMessage();
            }
            
            Map<String, String> categoryNames = getCategoryNames();
            List<String> chineseCategories = violatedCategories.stream()
                    .map(cat -> categoryNames.getOrDefault(cat, cat))
                    .toList();
            
            return "内容违规，包含：" + String.join("、", chineseCategories);
        }

        @NotNull
        private static Map<String, String> getCategoryNames() {
            Map<String, String> categoryNames = new HashMap<>();
            categoryNames.put("sexual", "色情内容");
            categoryNames.put("hate", "仇恨言论");
            categoryNames.put("harassment", "骚扰信息");
            categoryNames.put("self-harm", "自残内容");
            categoryNames.put("sexual/minors", "未成年色情");
            categoryNames.put("hate/threatening", "威胁性仇恨言论");
            categoryNames.put("violence/graphic", "暴力血腥");
            categoryNames.put("self-harm/intent", "自残意图");
            categoryNames.put("self-harm/instructions", "自残教唆");
            categoryNames.put("harassment/threatening", "威胁性骚扰");
            categoryNames.put("violence", "暴力内容");
            return categoryNames;
        }
    }

    /**
     * 审核异常
     */
    public static class ModerationException extends RuntimeException {
        public ModerationException(String message) {
            super(message);
        }

        public ModerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
