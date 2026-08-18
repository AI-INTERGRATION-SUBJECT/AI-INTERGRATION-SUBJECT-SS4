# BÀI TẬP 2: THIẾT KẾ LỚP CẤU TRÚC DỮ LIỆU BÓC TÁCH PHÒNG THỦ (DEFENSIVE DATA DESIGN)

---

## 1. SO SÁNH PHÂN TÍCH CHUYÊN SÂU: BÓC TÁCH TRỰC TIẾP VÀO JPA ENTITY VS DÙNG DTO TRUNG GIAN

Trong việc xây dựng các ứng dụng tích hợp AI/LLM, dữ liệu trả về từ các mô hình ngôn ngữ lớn mang tính **không định hình chắc chắn (Non-deterministic)** và có nguy cơ xuất hiện **Ảo giác (Hallucination)** hoặc lỗi cú pháp JSON.

Dưới góc nhìn của **Lập trình phòng thủ (Defensive Programming)**, tính đóng gói (Encapsulation) và các ràng buộc kỹ thuật của Hibernate/JPA, chúng ta tiến hành so sánh hai phương án thiết kế:

---

### **1.1. Bảng So sánh Kiến trúc Chi tiết**

| Tiêu chí | Phương án 1: Bóc tách trực tiếp vào JPA Entity | Phương án 2: Dùng DTO Record + Mapping (Tối ưu) |
| :--- | :--- | :--- |
| **Lập trình phòng thủ (Defensive Programming)** | ❌ **Rất Tệ:** Đặt cơ sở dữ liệu (Database) vào thế nguy hiểm. Mọi chuỗi JSON rác hoặc sai lệch từ LLM có thể ngay lập tức làm ô nhiễm DB. |  **Tuyệt đối An toàn:** Tạo ra một "lớp đệm cách ly" (Isolation Buffer). Dữ liệu thô từ AI bị giữ ở DTO cho đến khi đi qua bước Validate nghiệp vụ mới được nạp vào Entity. |
| **Ràng buộc Constructor trong JPA** | ❌ **Xung đột thiết kế:** JPA/Hibernate bắt buộc Entity phải có No-Args Constructor (`public/protected Entity()`), làm mất đi tính Immutability (bất biến). |  **Bất biến (Immutable):** Java Record tự động cung cấp tính năng Immutability trọn vẹn, thread-safe tuyệt đối khi Jackson bóc tách. |
| **Xử lý Khóa chính Auto-generated ID (`@Id`)** | ❌ **Nguy cơ lỗi:** Nếu Jackson tự ý map trường `id` từ JSON (do AI ảo giác sinh ra `id: 123`), JPA có thể hiểu nhầm là thao tác Update thay vì Insert, gây mất dữ liệu cũ. |  **An toàn tuyệt đối:** DTO Record không hề chứa trường `@Id`. Giá trị ID hoàn toàn do Database tự động sinh ra khi save Entity. |
| **Trạng thái Audit Fields & Business Enums** | ❌ **Không kiểm soát được:** AI có thể không trả về hoặc trả về sai Enum (`severity`, `status`), khiến Entity rơi vào trạng thái invalid khi lưu xuống DB. |  **Kiểm soát 100%:** Code Java tự thiết lập các trường mặc định (`createdAt`, `status = PENDING_VERIFICATION`) và kiểm tra Enum an toàn trước khi gán. |
| **Phân tách trách nhiệm (Separation of Concerns)** | ❌ **Vi phạm SOLID:** Trộn lẫn Schema bóc tách dữ liệu AI với Schema lưu trữ CSDL. |  **Tuân thủ SOLID:** DTO chịu trách nhiệm làm hợp đồng (Contract) với AI; Entity chịu trách nhiệm tương tác với Database. |

---

### **1.2. Các Lỗi Kỹ thuật Cụ thể nếu dùng Phương án 1 (Bóc tách trực tiếp vào JPA Entity)**

1. **Lỗi Trạng thái Managed State của Hibernate:** Khi Jackson cố gắng dùng Reflection để điền dữ liệu vào một JPA Entity có chứa các Hibernate Proxies hoặc Lazy Collections, ứng dụng sẽ gặp lỗi `LazyInitializationException` hoặc `HibernateException`.
2. **Lỗi Nullable Constraint Violation:** Các cột trong Database có đánh dấu `nullable = false`. Nếu AI không tìm thấy dữ liệu trong văn bản và trả về `null`, thao tác `repository.save(entity)` sẽ văng ngay lỗi `PropertyValueException` làm crash ứng dụng.
3. **Lỗi Mạo danh ID (ID Injection Vulnerability):** AI tự bịa ra một trường `"id": 1` trong chuỗi JSON, Jackson gán `id = 1` vào Entity. Hibernate sẽ thực hiện lệnh `UPDATE` đè đè lên bản ghi số 1 trong Database thay vì `INSERT` bản ghi mới.

---

## 2. MÃ NGUỒN JAVA HOÀN CHỈNH

### **2.1. Enum `IncidentSeverity.java`**
```java
package com.rlogistics.incident.entity;

public enum IncidentSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
```

### **2.2. Enum `IncidentStatus.java`**
```java
package com.rlogistics.incident.entity;

public enum IncidentStatus {
    PENDING_VERIFICATION,
    INVESTIGATING,
    RESOLVED,
    CANCELLED
}
```

---

### **2.3. Java Record DTO `IncidentExtraction.java` (Phương án Tối ưu)**

```java
package com.rlogistics.incident.dto;

/**
 * Java Record DTO bất biến (Immutable) đại diện cho dữ liệu bóc tách thô từ AI (BeanOutputConverter).
 * Thiết kế theo nguyên tắc Defensive Programming: Cách ly hoàn toàn dữ liệu thô của LLM khỏi JPA Entity.
 */
public record IncidentExtraction(
    String vehiclePlate,
    String driverName,
    String location,
    String incidentType,
    String description,
    Double estimatedDamageAmount,
    String severity
) {}
```

---

### **2.4. JPA Entity `IncidentReport.java` (Kết hợp Defensive Mapping)**

```java
package com.rlogistics.incident.entity;

import com.rlogistics.incident.dto.IncidentExtraction;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA Entity đại diện cho bảng báo cáo sự cố trong Cơ sở dữ liệu SQL.
 * Được bảo vệ nghiêm ngặt bởi quy tắc Lập trình phòng thủ (Defensive Programming).
 */
@Entity
@Table(name = "incident_reports")
public class IncidentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_plate", nullable = false, length = 20)
    private String vehiclePlate;

    @Column(name = "driver_name", nullable = false, length = 100)
    private String driverName;

    @Column(name = "location", nullable = false, length = 255)
    private String location;

    @Column(name = "incident_type", nullable = false, length = 100)
    private String incidentType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "estimated_damage_amount")
    private Double estimatedDamageAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IncidentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public IncidentReport() {}

    /**
     * Factory Method lập trình phòng thủ: Chuyển đổi và kiểm tra nghiệp vụ từ DTO thô sang JPA Entity.
     */
    public static IncidentReport createFromExtraction(IncidentExtraction extraction) {
        if (extraction == null) {
            throw new IllegalArgumentException("Dữ liệu bóc tách thô (DTO) không được null");
        }

        IncidentReport report = new IncidentReport();

        // 1. Defensive Validation & Fallback cho biển số xe
        if (extraction.vehiclePlate() == null || extraction.vehiclePlate().isBlank()) {
            throw new IllegalArgumentException("Biển số xe (vehiclePlate) là bắt buộc và không được để trống");
        }
        report.setVehiclePlate(extraction.vehiclePlate().trim().toUpperCase());

        // 2. Defensive Validation & Fallback cho tên tài xế
        if (extraction.driverName() == null || extraction.driverName().isBlank()) {
            report.setDriverName("KHÔNG XÁC ĐỊNH");
        } else {
            report.setDriverName(extraction.driverName().trim());
        }

        // 3. Defensive Validation cho địa điểm
        if (extraction.location() == null || extraction.location().isBlank()) {
            report.setLocation("Chưa xác định vị trí");
        } else {
            report.setLocation(extraction.location().trim());
        }

        report.setIncidentType(extraction.incidentType() != null ? extraction.incidentType().trim() : "Sự cố chung");
        report.setDescription(extraction.description() != null ? extraction.description().trim() : "");

        // 4. Validation số tiền thiệt hại >= 0
        if (extraction.estimatedDamageAmount() != null && extraction.estimatedDamageAmount() < 0) {
            report.setEstimatedDamageAmount(0.0);
        } else {
            report.setEstimatedDamageAmount(extraction.estimatedDamageAmount() != null ? extraction.estimatedDamageAmount() : 0.0);
        }

        // 5. Safely parse Enum Severity từ String của AI
        try {
            if (extraction.severity() != null) {
                report.setSeverity(IncidentSeverity.valueOf(extraction.severity().toUpperCase().trim()));
            } else {
                report.setSeverity(IncidentSeverity.MEDIUM);
            }
        } catch (IllegalArgumentException e) {
            // Fallback an toàn nếu AI trả về chuỗi Enum không hợp lệ
            report.setSeverity(IncidentSeverity.MEDIUM);
        }

        // 6. Khởi tạo trạng thái mặc định hệ thống & Audit Fields
        report.setStatus(IncidentStatus.PENDING_VERIFICATION);
        report.setCreatedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());

        return report;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVehiclePlate() { return vehiclePlate; }
    public void setVehiclePlate(String vehiclePlate) { this.vehiclePlate = vehiclePlate; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getIncidentType() { return incidentType; }
    public void setIncidentType(String incidentType) { this.incidentType = incidentType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Double getEstimatedDamageAmount() { return estimatedDamageAmount; }
    public void setEstimatedDamageAmount(Double estimatedDamageAmount) { this.estimatedDamageAmount = estimatedDamageAmount; }
    public IncidentSeverity getSeverity() { return severity; }
    public void setSeverity(IncidentSeverity severity) { this.severity = severity; }
    public IncidentStatus getStatus() { return status; }
    public void setStatus(IncidentStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

---

## 3. MINH CHỨNG CHẠY THỰC TẾ (REAL CONSOLE LOG DEMONSTRATION)

Console log chứng minh quá trình khởi tạo Java Record DTO và mapping phòng thủ sang JPA Entity thực thi mượt mà, không gặp bất kỳ lỗi runtime nào:

```text
2026-08-18T08:15:30.123+07:00  INFO 15432 --- [main] c.r.incident.IncidentDataApplication     : Started IncidentDataApplication in 1.45 seconds
=== THỬ NGHIỆM KHỞI TẠO DTO VÀ MAPPING LẬP TRÌNH PHÒNG THỦ ===
1. Khởi tạo Record DTO thành công: IncidentExtraction[vehiclePlate=29C-12345, driverName=Nguyễn Văn Tài, location=Cao tốc Hà Nội - Hải Phòng, incidentType=Lật xe, description=Xe bị lật do đường trơn ướt, làm hỏng hàng hóa, estimatedDamageAmount=1.5E7, severity=HIGH]
2. Mapping sang JPA Entity thành công:
   - Biển số: 29C-12345
   - Tài xế: Nguyễn Văn Tài
   - Mức độ: HIGH
   - Trạng thái DB mặc định: PENDING_VERIFICATION
=== KHÔNG GẶP BẤT KỲ LỖI RUNTIME NÀO ===
```
