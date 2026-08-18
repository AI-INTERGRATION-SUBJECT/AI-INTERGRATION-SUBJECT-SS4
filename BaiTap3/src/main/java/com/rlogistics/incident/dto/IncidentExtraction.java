package com.rlogistics.incident.dto;

/**
 * Java Record DTO bất biến chứa kết quả bóc tách từ LLM.
 */
public record IncidentExtraction(
    String orderCode,
    String licensePlate,
    String incidentType,
    String urgency
) {}
