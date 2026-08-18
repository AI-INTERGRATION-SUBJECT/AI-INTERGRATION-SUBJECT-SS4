package com.rlogistics.incident.controller;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * REST Controller xử lý Stream SSE phân tích sự cố Logistics với Dynamic ChatOptions.
 */
@RestController
@RequestMapping("/api/v1/incident")
@CrossOrigin(origins = "*")
public class IncidentStreamController {

    private final ChatModel chatModel;

    public IncidentStreamController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * API Endpoint Stream SSE phân tích sự cố với tham số cấu hình động.
     *
     * @param rawMessage Nội dung mô tả sự cố thô từ Ban điều hành
     * @param temp Độ sáng tạo / chính xác của mô hình (default: 0.5)
     * @param maxTokens Giới hạn số lượng token tối đa (default: 1000)
     * @param response Đối tượng ServerHttpResponse để bổ sung Header X-Accel-Buffering
     * @return Luồng Flux<String> dữ liệu dạng Server-Sent Events (SSE)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamIncidentAnalysis(
            @RequestParam String rawMessage,
            @RequestParam(defaultValue = "0.5") Double temp,
            @RequestParam(defaultValue = "1000") Integer maxTokens,
            ServerHttpResponse response
    ) {
        // 1. Bổ sung Header X-Accel-Buffering: no ngăn chặn Nginx reverse proxy đệm dữ liệu stream SSE
        response.getHeaders().add("X-Accel-Buffering", "no");

        // 2. Khởi tạo Dynamic OpenAiChatOptions cho từng Request cụ thể
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withTemperature(temp)
                .withMaxTokens(maxTokens)
                .build();

        // 3. Đóng gói Prompt chứa tin nhắn và tham số cấu hình động
        Prompt prompt = new Prompt(rawMessage, options);

        // 4. Thực thi stream bất đồng bộ và xử lý null-safe cho từng chunk phát sinh
        return chatModel.stream(prompt)
                .map(chatResponse -> Optional.ofNullable(chatResponse)
                        .map(res -> res.getResult())
                        .map(result -> result.getOutput())
                        .map(output -> output.getText())
                        .orElse("")
                )
                .filter(text -> !text.isEmpty());
    }
}
