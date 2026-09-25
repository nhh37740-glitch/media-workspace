package com.mediaworkspace.application.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON serialization used as the idempotency request fingerprint.
 *
 * <p>Object members are sorted by name and numbers are normalized, so two requests that differ
 * only in field order or in a numeric spelling such as {@code 1.0} versus {@code 1} produce the
 * same fingerprint and are treated as the same request. Timestamps are compared as the instant
 * they denote, which is what the contract requires.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CanonicalJson() {
    }

    /** Sorts and normalizes a parsed JSON tree, then serializes it with UTF-8 and no whitespace. */
    public static byte[] canonicalize(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(normalize(node));
        } catch (Exception e) {
            throw new IllegalArgumentException("cannot canonicalize JSON", e);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return MAPPER.nullNode();
        }
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                sorted.put(field.getKey(), normalize(field.getValue()));
            }
            ObjectNode result = MAPPER.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(normalize(item)));
            items.forEach(result::add);
            return result;
        }
        if (node.isNumber()) {
            // A decimal point or exponent is removed so 1.0 and 1 fingerprint identically.
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.scale() <= 0) {
                return MAPPER.getNodeFactory().numberNode(value.toBigIntegerExact());
            }
            return MAPPER.getNodeFactory().numberNode(value);
        }
        return node;
    }
}
