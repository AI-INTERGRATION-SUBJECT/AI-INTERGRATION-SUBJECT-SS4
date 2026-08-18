package com.rlogistics.incident.service;

import com.rlogistics.incident.dto.IncidentExtraction;
import com.rlogistics.incident.entity.IncidentReport;
import com.rlogistics.incident.entity.UrgencyLevel;
import com.rlogistics.incident.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service ETL bóc tách tin nhắn sự cố đã được Refactor đạt chuẩn Enterprise.
 * Áp dụng Lập trình phòng thủ (Defensive Programming), SLF4J Logging, và phân tách Transaction.
 */
@Service
public class IncidentETLService {

    private static final Logger log = LoggerFactory.getLogger(IncidentETLService.class);

    // Regex kiểm tra định dạng biển số xe Việt Nam (Ví dụ: 29C-12345, 51H-99999)
    private static final Pattern LICENSE_PLATE_PATTERN = Pattern.compile("^[0-9]{2}[A-Z0-9]{1,2}-[0-9]{4,5}$");

    private final ChatModel chatModel;
    private final IncidentRepository repository;
    private final BeanOutputConverter<IncidentExtraction> converter;

    /**
     * Constructor Injection giúp dễ dàng Unit Test và quản lý Bean Lifecycle.
     */
    public IncidentETLService(ChatModel chatModel, IncidentRepository repository) {
        this.chatModel = chatModel;
        this.repository = repository;
        // Caching BeanOutputConverter duy nhất một lần thay vì khởi tạo lại mỗi request
        this.converter = new BeanOutputConverter<>(IncidentExtraction.class);
    }

    /**
     * Phương thức chính xử lý luồng ETL tin nhắn sự cố.
     * KHÔNG gắn @Transactional ở đây để tránh giữ DB Connection trong lúc gọi AI API (3-15s).
     */
    public IncidentReport processReport(String rawMessage) {
        log.info("[ETL START] Bắt đầu tiếp nhận và xử lý tin nhắn sự cố thô: '{}'", rawMessage);

        try {
            // 1. EXTRACT & TRANSFORM: Gọi AI bóc tách dữ liệu
            IncidentExtraction dto = extractAndTransform(rawMessage);
            log.info("[ETL TRANSFORM SUCCESS] Dữ liệu DTO bóc tách từ AI: {}", dto);

            // 2. DEFENSIVE VALIDATION: Kiểm tra tính hợp lệ nghiệp vụ chặt chẽ
            validateExtractionDTO(dto);

            // 3. MAP TO ENTITY: Ánh xạ an toàn từ DTO sang JPA Entity
            IncidentReport entity = mapDtoToEntity(dto);

            // 4. LOAD: Lưu xuống Database trong phạm vi Transaction riêng biệt
            IncidentReport savedEntity = saveToDatabase(entity);
            log.info("[ETL LOAD SUCCESS] Đã lưu thành công Báo cáo Sự cố ID: {} vào CSDL", savedEntity.getId());

            return savedEntity;

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[ETL VALIDATION ERROR] Dữ liệu không hợp lệ, không lưu DB. Lý do: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[ETL SYSTEM ERROR] Lỗi hệ thống trong quá trình xử lý tin nhắn: '{}'", rawMessage, e);
            throw new RuntimeException("Xử lý ETL thất bại do lỗi hệ thống: " + e.getMessage(), e);
        }
    }

    /**
     * Gọi AI bóc tách và làm sạch Markdown Block thừa trước khi parse JSON.
     */
    private IncidentExtraction extractAndTransform(String rawMessage) {
        String systemPrompt = """
            [VAI TRÒ] Bạn là một trợ lý AI trích xuất dữ liệu sự cố giao thông cho R-Logistics.
            [NỘI DUNG TẤT CẢ] Bóc tách thông tin sự cố từ tin nhắn của tài xế thành JSON.
            [RÀNG BUỘC] CHỈ trả về JSON thuần, KHÔNG bọc trong markdown ```json.
            
            Tin nhắn: {rawMessage}
            
            {formatInstructions}
            """;

        PromptTemplate template = new PromptTemplate(systemPrompt);
        Prompt prompt = template.create(Map.of(
            "rawMessage", rawMessage,
            "formatInstructions", converter.getFormatInstructions()
        ));

        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        log.debug("[AI RAW RESPONSE] Phản hồi thô từ AI: {}", rawResponse);

        // HELPER METHOD: Làm sạch khối Markdown Code Block trước khi parse Jackson
        String cleanedJson = cleanMarkdownBlocks(rawResponse);
        log.debug("[CLEANED JSON] Chuỗi JSON đã làm sạch: {}", cleanedJson);

        return converter.convert(cleanedJson);
    }

    /**
     * Helper Method loại bỏ thẻ Markdown ```json ... ``` hoặc ký tự thừa.
     */
    public String cleanMarkdownBlocks(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "{}";
        }
        String cleaned = rawText.trim();
        // Loại bỏ ```json ở đầu và ``` ở cuối
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "");
            cleaned = cleaned.replaceAll("\\s*```$", "");
        }
        return cleaned.trim();
    }

    /**
     * LẬP TRÌNH PHÒNG THỦ (Defensive Validation): Kiểm chứng toàn bộ ràng buộc trước khi ghi DB.
     */
    private void validateExtractionDTO(IncidentExtraction dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Khối DTO bóc tách từ AI không được null");
        }

        // 1. Kiểm tra Mã đơn hàng (orderCode)
        if (dto.orderCode() == null || dto.orderCode().isBlank()) {
            throw new IllegalArgumentException("Mã đơn hàng (orderCode) không được để trống hoặc null");
        }

        // 2. Kiểm tra Biển số xe (licensePlate) theo Regex
        if (dto.licensePlate() == null || dto.licensePlate().isBlank()) {
            throw new IllegalArgumentException("Biển số xe (licensePlate) không được để trống");
        }
        String cleanPlate = dto.licensePlate().trim().toUpperCase();
        if (!LICENSE_PLATE_PATTERN.matcher(cleanPlate).matches()) {
            throw new IllegalArgumentException("Biển số xe '" + dto.licensePlate() + "' không đúng định dạng chuẩn (VD: 29C-12345)");
        }

        // 3. Kiểm tra Loại sự cố (incidentType)
        if (dto.incidentType() == null || dto.incidentType().isBlank()) {
            throw new IllegalArgumentException("Loại sự cố (incidentType) không được để trống");
        }

        // 4. Kiểm tra Mức độ khẩn cấp (urgency)
        if (dto.urgency() == null || dto.urgency().isBlank()) {
            throw new IllegalArgumentException("Mức độ khẩn cấp (urgency) không được để trống");
        }
        try {
            UrgencyLevel.valueOf(dto.urgency().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Mức độ khẩn cấp '" + dto.urgency() + "' không hợp lệ (Bắt buộc: LOW, MEDIUM, HIGH, CRITICAL)");
        }
    }

    /**
     * Ánh xạ từ DTO sang JPA Entity.
     */
    private IncidentReport mapDtoToEntity(IncidentExtraction dto) {
        UrgencyLevel urgency = UrgencyLevel.valueOf(dto.urgency().trim().toUpperCase());
        return new IncidentReport(
            dto.orderCode().trim().toUpperCase(),
            dto.licensePlate().trim().toUpperCase(),
            dto.incidentType().trim(),
            urgency
        );
    }

    /**
     * Phương thức lưu vào CSDL có gắn @Transactional. Rollback nếu có lỗi DB.
     */
    @Transactional
    public IncidentReport saveToDatabase(IncidentReport entity) {
        return repository.save(entity);
    }
}
