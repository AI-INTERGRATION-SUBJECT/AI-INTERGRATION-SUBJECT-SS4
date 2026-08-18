package com.rlogistics.incident;

import com.rlogistics.incident.dto.IncidentExtraction;
import com.rlogistics.incident.service.IncidentETLService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class IncidentETLApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentETLApplication.class, args);
    }

    @Bean
    public CommandLineRunner testETLService(IncidentETLService etlService) {
        return args -> {
            System.out.println("\n=== KIỂM THỬ THỬ NGHIỆM HELPER LÀM SẠCH MARKDOWN BLOCK ===");
            String markdownJson = "```json\n{\n  \"orderCode\": \"ORD-9988\",\n  \"licensePlate\": \"29C-12345\",\n  \"incidentType\": \"Lật xe\",\n  \"urgency\": \"HIGH\"\n}\n```";
            String cleaned = etlService.cleanMarkdownBlocks(markdownJson);
            System.out.println("Chuỗi sau làm sạch:\n" + cleaned);

            System.out.println("\n=== THỬ NGHIỆM KỊCH BẢN VALIDATION VÀ ROLLBACK ===");
            try {
                // Tin nhắn thiếu biển số xe hợp lệ
                etlService.processReport("Tài xế báo sự cố đơn hàng ORD-1111 nhưng quên báo biển số");
            } catch (Exception e) {
                System.out.println(" Bắt ngoại lệ Validation thành công, Rollback Transaction: " + e.getMessage());
            }
        };
    }
}
