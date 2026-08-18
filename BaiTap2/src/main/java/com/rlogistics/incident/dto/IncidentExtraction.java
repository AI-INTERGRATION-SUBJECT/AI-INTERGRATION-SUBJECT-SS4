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
