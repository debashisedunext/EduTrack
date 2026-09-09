package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * B-113 · {@code ob_notification_templates}.
 *
 * <p>The two derived fields — {@code isMandatory} and {@code isDeliverable} —
 * are not read here and are not columns. This repository returns what is
 * stored; {@link ObTemplateService} adds what is derived, so there is one place
 * that knows each rule.
 */
@Repository
class ObTemplateRepository {

    private static final String COLUMNS = """
            id, event_code, category, channel, recipients,
            subject_template, body_template, is_active
            """;

    /**
     * <p>Ordered by category then event so OB-12's list groups the way the
     * screen renders it, without the client sorting a set it did not choose the
     * order of. Not paginated, and the contract does not ask for it: the row
     * count is the event catalogue's, which is twenty.
     */
    private static final String LIST = """
            SELECT %s
              FROM ob_notification_templates
             WHERE 1 = 1
            %%s
             ORDER BY category ASC, event_code ASC
            """.formatted(COLUMNS);

    private static final String BY_ID = """
            SELECT %s
              FROM ob_notification_templates
             WHERE id = ?
            """.formatted(COLUMNS);

    /**
     * <p>{@code COALESCE} per field, so a {@code PATCH} that names one field
     * leaves the rest alone without the service having to read-modify-write.
     * The read it does anyway is for the precondition and the mandatory check,
     * not for the update.
     */
    private static final String UPDATE = """
            UPDATE ob_notification_templates
               SET subject_template = COALESCE(?, subject_template),
                   body_template    = COALESCE(?, body_template),
                   recipients       = COALESCE(?, recipients),
                   is_active        = COALESCE(?, is_active),
                   updated_by       = ?
             WHERE id = ?
            """;

    private final JdbcClient jdbc;

    ObTemplateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * <p>The two filters are appended as clauses rather than passed as
     * {@code (? IS NULL OR col = ?)} pairs. The pair form is one statement for
     * every combination, which reads well and costs a table scan on the filtered
     * case because MySQL cannot use the index behind an {@code OR ? IS NULL} —
     * and it means passing nulls as bind values, which the parameter API is not
     * annotated to accept. Two clauses and two conditional binds instead.
     */
    List<Stored> list(ObChannel channel, ObTemplateDtos.Category category) {
        StringBuilder clauses = new StringBuilder();
        List<Object> params = new ArrayList<>(2);
        if (channel != null) {
            clauses.append(" AND channel = ?");
            params.add(channel.name());
        }
        if (category != null) {
            clauses.append(" AND category = ?");
            params.add(category.name());
        }
        return jdbc.sql(LIST.formatted(clauses.toString()))
                .params(params)
                .query(ObTemplateRepository::map)
                .list();
    }

    Optional<Stored> findById(long id) {
        return jdbc.sql(BY_ID).param(id).query(ObTemplateRepository::map).optional();
    }

    void update(long id, ObTemplateDtos.UpdateRequest request, Long actorUserId) {
        jdbc.sql(UPDATE)
                .param(request.subjectTemplate())
                .param(request.bodyTemplate())
                .param(request.recipients() == null ? null : String.join(",", request.recipients()))
                .param(request.isActive())
                .param(actorUserId)
                .param(id)
                .update();
    }

    private static Stored map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Stored(
                rs.getLong("id"),
                rs.getString("event_code"),
                ObTemplateDtos.Category.valueOf(rs.getString("category")),
                ObChannel.valueOf(rs.getString("channel")),
                splitRecipients(rs.getString("recipients")),
                rs.getString("subject_template"),
                rs.getString("body_template"),
                rs.getBoolean("is_active"));
    }

    /**
     * <p>Blank entries are dropped rather than preserved. The column is a
     * comma-separated list and {@code ck_ob_notification_templates_recipients}
     * only refuses the empty string, so {@code "STEP_OWNER,"} is storable —
     * and an empty role code resolving to nobody would be a silent
     * non-delivery.
     */
    private static List<String> splitRecipients(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> recipients = new ArrayList<>();
        for (String part : Arrays.asList(stored.split(","))) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                recipients.add(trimmed);
            }
        }
        return List.copyOf(recipients);
    }

    /** What the table holds, before the two derived fields are added. */
    record Stored(long id, String eventCode, ObTemplateDtos.Category category, ObChannel channel,
                  List<String> recipients, String subjectTemplate, String bodyTemplate,
                  boolean isActive) {
    }
}
