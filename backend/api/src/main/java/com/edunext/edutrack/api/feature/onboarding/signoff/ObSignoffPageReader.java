package com.edunext.edutrack.api.feature.onboarding.signoff;

import com.edunext.edutrack.domain.onboarding.ObSignoff;
import com.edunext.edutrack.domain.onboarding.ObSignoffKind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A-121 · what OB-09 renders, read once the code is proved.
 *
 * <h2>Its own reads rather than a call into the staff services</h2>
 *
 * <p>{@code ObJourneyStepLifecycleService#checklistFor} already assembles a
 * checklist and it is tempting to reuse. It is not reused, and the reason is
 * the surface rather than the duplication: that method serves staff, its shape
 * is free to grow a field the day somebody needs one on OB-06, and it would
 * grow that field <em>here</em> too — on an unauthenticated page, for an
 * external reader, without anybody deciding. A public projection that names its
 * own columns cannot widen by accident.
 *
 * <p>Plain SQL through {@link JdbcClient} for the reason
 * {@code PriorityUsageRepository} gives one package over: this is a projection
 * across five tables for one screen, and a grouped projection is not what a
 * {@code JpaRepository} is for.
 *
 * <h2>PAN is not in any query here, and that is not an oversight</h2>
 *
 * <p>{@code ob_clients} carries {@code pan_ciphertext}. Plan §3 masks it for
 * everyone except OB Admin and Manager and audits the unmasked reads; this
 * caller is neither, holds no account at all, and the page has no field for it.
 * Selecting the column and dropping it later would put it one careless mapper
 * away from the wire.
 */
@Component
class ObSignoffPageReader {

    /**
     * The client, and the product when the sign-off hangs off a journey.
     *
     * <p>{@code LEFT JOIN} on the product: a row is worth returning even if the
     * catalogue entry behind it has gone, and an {@code INNER JOIN} would turn
     * that into a page that renders nothing at all.
     */
    private static final String HEADER = """
            SELECT c.name           AS client_name,
                   p.name           AS product_name,
                   s.name           AS step_name
              FROM ob_signoffs      sg
              JOIN ob_clients       c  ON c.id = sg.ob_client_id
              LEFT JOIN ob_journeys j  ON j.id = sg.journey_id
              LEFT JOIN ob_products p  ON p.id = j.product_id
              LEFT JOIN ob_journey_steps s ON s.id = sg.step_id
             WHERE sg.id = ?
            """;

    /**
     * The Task List, with {@code is_mandatory} joined back from the template
     * row — C-111's join, because {@code ob_journey_step_items} does not carry
     * the flag and the instance side has always read it from the prototype.
     *
     * <p>{@code COALESCE(..., TRUE)} matches C-102's column default: an item
     * whose template row has gone is treated as mandatory, which is the safe
     * direction — it can only ever hold a completion back, never wave one
     * through.
     */
    private static final String CHECKLIST = """
            SELECT i.id,
                   i.step_id,
                   i.sequence,
                   i.label,
                   COALESCE(t.is_mandatory, TRUE) AS is_mandatory,
                   i.answer IS NOT NULL           AS is_done,
                   i.answered_at
              FROM ob_journey_step_items i
              LEFT JOIN ob_journey_template_step_items t ON t.id = i.template_item_id
             WHERE i.step_id = ?
             ORDER BY i.sequence ASC, i.id ASC
            """;

    private final JdbcClient jdbc;

    ObSignoffPageReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Everything the session response carries beyond the token itself.
     *
     * <p>The checklist is read only for a {@code STEP} sign-off. A
     * {@code GO_LIVE} one is about the journey rather than one service and the
     * contract says its checklist is empty — asking for the items of a null
     * {@code step_id} would return every row whose step is null, which is not
     * empty and is not this client's.
     */
    Page read(ObSignoff signoff) {
        Header header = jdbc.sql(HEADER)
                .param(signoff.getId())
                .query((rs, row) -> new Header(
                        rs.getString("client_name"),
                        rs.getString("product_name"),
                        rs.getString("step_name")))
                .optional()
                .orElseGet(() -> new Header(null, null, null));

        List<PublicSignoffOtpDtos.ChecklistItem> checklist =
                signoff.getKind() == ObSignoffKind.STEP && signoff.getStepId() != null
                        ? checklistFor(signoff.getStepId())
                        : List.of();

        return new Page(header.clientName(), header.productName(), header.stepName(), checklist);
    }

    private List<PublicSignoffOtpDtos.ChecklistItem> checklistFor(long stepId) {
        return jdbc.sql(CHECKLIST)
                .param(stepId)
                .query((rs, row) -> {
                    java.sql.Timestamp answeredAt = rs.getTimestamp("answered_at");
                    return new PublicSignoffOtpDtos.ChecklistItem(
                            rs.getLong("id"),
                            rs.getLong("step_id"),
                            rs.getInt("sequence"),
                            rs.getString("label"),
                            rs.getBoolean("is_mandatory"),
                            rs.getBoolean("is_done"),
                            answeredAt == null ? null : answeredAt.toInstant());
                })
                .list();
    }

    private record Header(String clientName, String productName, String stepName) {
    }

    /** @param stepTitle {@code ob_journey_steps.name} — the contract calls it a title. */
    record Page(String clientName,
                String productName,
                String stepTitle,
                List<PublicSignoffOtpDtos.ChecklistItem> checklist) {
    }
}
