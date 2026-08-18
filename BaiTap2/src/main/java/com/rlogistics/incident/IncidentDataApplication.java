package com.rlogistics.incident;

import com.rlogistics.incident.dto.IncidentExtraction;
import com.rlogistics.incident.entity.IncidentReport;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class IncidentDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentDataApplication.class, args);
    }

    @Bean
    public CommandLineRunner testDefensiveMapping() {
        return args -> {
            System.out.println("=== THỬ NGHIỆM KHỞI TẠO DTO VÀ MAPPING LẬP TRÌNH PHÒNG THỦ ===");
            
            // Dữ liệu bóc tách thô giả định từ LLM (BeanOutputConverter)
            IncidentExtraction rawDto = new IncidentExtraction(
                "29C-12345",
                "Nguyễn Văn Tài",
                "Cao tốc Hà Nội - Hải Phòng",
                "Lật xe",
                "Xe bị lật do đường trơn ướt, làm hỏng hàng hóa",
                15000000.0,
                "HIGH"
            );

            System.out.println("1. Khởi tạo Record DTO thành công: " + rawDto);

            // Chuyển đổi sang JPA Entity thông qua Factory Method lập trình phòng thủ
            IncidentReport entity = IncidentReport.createFromExtraction(rawDto);
            
            System.out.println("2. Mapping sang JPA Entity thành công:");
            System.out.println("   - Biển số: " + entity.getVehiclePlate());
            System.out.println("   - Tài xế: " + entity.getDriverName());
            System.out.println("   - Mức độ: " + entity.getSeverity());
            System.out.println("   - Trạng thái DB mặc định: " + entity.getStatus());
            System.out.println("=== KHÔNG GẶP BẤT KỲ LỖI RUNTIME NÀO ===");
        };
    }
}
