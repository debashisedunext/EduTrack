package com.edunext.edutrack.domain.onboarding.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Map;

/**
 * B-110 · how {@code ob_notification_outbox.payload} is written and read.
 *
 * <p>A private mapper rather than the Spring-managed one, on purpose. The
 * {@code worker} process has no {@code ObjectMapper} bean — it is a
 * non-web Boot application and never asked for one — and {@code api}'s is
 * tuned for HTTP responses. The payload is neither: it is a small map of
 * template variables that has to read back exactly as it was written by
 * whichever process wrote it, so both sides share one fixed configuration
 * here and nothing an application customises can change the column's shape.
 *
 * <p>Values should be JSON-native — strings, numbers, booleans, nested maps
 * and lists. Dates go in as ISO-8601 strings, formatted by the caller: a
 * template wants "10 Sep 2026" or "2026-09-10", never an epoch, and the
 * caller is the one that knows the recipient's timezone.
 */
public final class ObOutboxJson {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private ObOutboxJson() {
    }

    /** @throws IllegalArgumentException if a value cannot be serialised */
    public static String write(Map<String, Object> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("ob-outbox: payload is not serialisable", e);
        }
    }

    /**
     * @return the payload as a map; empty for null, blank, or a JSON value
     *         that is not an object — MySQL guarantees the column is valid
     *         JSON, so a top-level array is the only way to get here
     * @throws IllegalArgumentException if the text is not JSON at all
     */
    public static Map<String, Object> read(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, OBJECT);
            return parsed == null ? Map.of() : parsed;
        } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException notAnObject) {
            return Map.of();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("ob-outbox: payload is not JSON", e);
        }
    }
}
