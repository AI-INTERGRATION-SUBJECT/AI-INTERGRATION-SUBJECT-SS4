package com.rlogistics.incident.controller;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller kiểm tra và đối soát cấu hình profile / model LLM đang hoạt động.
 */
@RestController
@RequestMapping("/api/v1/incident")
public class SystemConfigController {

    private final Environment environment;

    public SystemConfigController(Environment environment) {
        this.environment = environment;
    }

    /**
     * Endpoint đối soát xem profile đã nạp đúng cấu hình tương ứng chưa.
     */
    @GetMapping("/config")
    public Map<String, Object> getActiveSystemConfig() {
        Map<String, Object> configInfo = new HashMap<>();

        // 1. Lấy danh sách active profiles hiện tại
        String[] activeProfiles = environment.getActiveProfiles();
        String currentProfile = activeProfiles.length > 0 ? activeProfiles[0] : "default";

        configInfo.put("activeProfile", currentProfile);

        // 2. Xác định mô hình và URL tương ứng dựa vào profile đang active
        if ("cloud".equalsIgnoreCase(currentProfile)) {
            configInfo.put("activeModel", environment.getProperty("spring.ai.openai.chat.options.model", "N/A"));
            configInfo.put("baseUrl", environment.getProperty("spring.ai.openai.base-url", "N/A"));
            configInfo.put("provider", "OpenRouter (Cloud)");
        } else {
            configInfo.put("activeModel", environment.getProperty("spring.ai.ollama.chat.options.model", "qwen2.5-coder:7b"));
            configInfo.put("baseUrl", environment.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"));
            configInfo.put("provider", "Ollama (Local)");
        }

        configInfo.put("applicationName", environment.getProperty("spring.application.name"));
        configInfo.put("status", "ACTIVE");

        return configInfo;
    }
}
