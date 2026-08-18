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

    /**
     * Constructor mặc định bắt buộc cho Hibernate/JPA.
     */
    public IncidentReport() {}

    /**
     * Factory Method lập trình phòng thủ: Chuyển đổi và kiểm tra nghiệp vụ từ DTO thô sang JPA Entity.
     *
     * @param extraction DTO bóc tách thô từ AI
     * @return JPA Entity IncidentReport hợp lệ sẵn sàng lưu xuống DB
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
            // AI trả về string không khớp Enum -> Fallback an toàn về MEDIUM
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
