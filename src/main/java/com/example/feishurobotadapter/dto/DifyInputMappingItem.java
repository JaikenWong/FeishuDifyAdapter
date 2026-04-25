package com.example.feishurobotadapter.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

public record DifyInputMappingItem(
        String variable,
        String source
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<DifyInputMappingItem> fromConfigJson(
            String json, String fallbackNameVar, String fallbackEmployeeNoVar) {
        List<DifyInputMappingItem> fallback = List.of(
                new DifyInputMappingItem(fallbackNameVar, "display_name"),
                new DifyInputMappingItem(fallbackEmployeeNoVar, "employee_no")
        );
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            List<DifyInputMappingItem> list = MAPPER.readValue(json,
                    new TypeReference<List<DifyInputMappingItem>>() {});
            if (list == null || list.isEmpty()) {
                return fallback;
            }
            return list;
        } catch (Exception ex) {
            return fallback;
        }
    }
}
