package com.logpark.coldchain.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 轻量 JSON 工具：解析证据文本中内嵌的结构化片段（Jackson 随 Spring Boot 已提供）。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static List<Map<String, Object>> parseList(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw ApiException.badRequest("JSON 解析失败: " + e.getMessage());
        }
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
