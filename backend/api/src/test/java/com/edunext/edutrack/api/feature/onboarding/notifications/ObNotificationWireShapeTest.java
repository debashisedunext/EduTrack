package com.edunext.edutrack.api.feature.onboarding.notifications;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-112 · what {@code GET /onboarding/notifications} actually puts on the wire.
 *
 * <p><strong>This exists because the meta is nested in Java and flat in the
 * contract.</strong> {@link ObNotificationDtos.Meta} composes
 * {@link PageMeta} to satisfy A-053's {@code PaginationRulesTest}, and
 * {@code @JsonUnwrapped} is the only thing keeping the JSON shape the
 * contract's {@code allOf: [Meta, {unreadCount}]}. Nothing else would catch
 * that annotation being dropped in a refactor: the Java compiles, every other
 * test in this package reads {@code meta().page().hasMore()} and passes, and
 * the failure surfaces as a bell badge that is undefined in a browser.
 *
 * <p>Plain Jackson rather than a MockMvc slice, because the question is about
 * the annotation and not about the controller — and a spun-up web context would
 * be forty seconds to assert three key names.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ObNotificationWireShapeTest {

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    @Test
    @DisplayName("meta is flat: nextCursor, hasMore and unreadCount are siblings")
    void metaIsFlat() throws Exception {
        JsonNode meta = json.valueToTree(
                new ObNotificationDtos.Meta(new PageMeta("42", true), 7));

        assertThat(meta.path("nextCursor").asText()).isEqualTo("42");
        assertThat(meta.path("hasMore").asBoolean()).isTrue();
        assertThat(meta.path("unreadCount").asInt()).isEqualTo(7);
        // The Java field name must not reach the wire — a `page` object here
        // would mean the client reads meta.page.nextCursor and the contract
        // says meta.nextCursor.
        assertThat(meta.has("page")).isFalse();
    }

    @Test
    void the_last_page_sends_a_null_cursor_rather_than_omitting_it() throws Exception {
        JsonNode meta = json.valueToTree(new ObNotificationDtos.Meta(PageMeta.last(), 0));

        // Present-and-null, not absent: a client that reads `nextCursor === null`
        // as "the end" and `undefined` as "ask again" would loop.
        assertThat(meta.has("nextCursor")).isTrue();
        assertThat(meta.path("nextCursor").isNull()).isTrue();
        assertThat(meta.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    void an_entry_carries_the_fields_the_contract_names() throws Exception {
        JsonNode body = json.valueToTree(new ObNotificationDtos.ObNotificationListResponse(
                List.of(new ObNotificationDtos.ObNotification(
                        9, "TAT_BREACHED", "ESCALATION",
                        "Overdue by 2 days: Data Migration",
                        "The onboarding is held up until this closes.",
                        77L, 12L, 34L, false,
                        Instant.parse("2026-09-04T10:00:00Z"),
                        "/onboarding/clients/77")),
                new ObNotificationDtos.Meta(PageMeta.last(), 1)));

        JsonNode entry = body.path("data").get(0);
        assertThat(entry.path("id").asLong()).isEqualTo(9);
        assertThat(entry.path("eventKey").asText()).isEqualTo("TAT_BREACHED");
        assertThat(entry.path("category").asText()).isEqualTo("ESCALATION");
        assertThat(entry.path("isRead").asBoolean()).isFalse();
        assertThat(entry.path("deepLink").asText()).isEqualTo("/onboarding/clients/77");
        assertThat(entry.path("obClientId").asLong()).isEqualTo(77);
    }

    @Test
    @DisplayName("an entry that belongs to no client sends null ids, not zeroes")
    void nullContextIdsStayNull() throws Exception {
        JsonNode entry = json.valueToTree(new ObNotificationDtos.ObNotification(
                9, "CLIENT_LOGIN_CREATED", "UPDATE", "A client portal login was created",
                null, null, null, null, true, Instant.parse("2026-09-04T10:00:00Z"), null));

        // Boxed Long, so this is a real risk only if somebody "simplifies" them
        // to long — and 0 is a client id a screen would try to open.
        assertThat(entry.path("obClientId").isNull()).isTrue();
        assertThat(entry.path("journeyId").isNull()).isTrue();
        assertThat(entry.path("stepId").isNull()).isTrue();
        assertThat(entry.path("deepLink").isNull()).isTrue();
        assertThat(entry.path("body").isNull()).isTrue();
    }
}
