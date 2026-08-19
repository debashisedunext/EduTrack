package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.common.pagination.PageMeta;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A-071 · the wire shape of {@code GET /audit-logs}, per
 * {@code contracts/openapi.yaml} § {@code AuditLogListResponse}.
 *
 * <p>Read-only in the strongest sense available in Java: there is no request
 * body type in this file, because the route takes none and never will. S-16 is
 * "export only, never editable", and the absence of a {@code CreateAuditLog}
 * or {@code PatchAuditLog} record here is the shape of that at the DTO layer.
 */
final class AuditDtos {

    private AuditDtos() {
    }

    /**
     * The contract's {@code UserRef}, minus the fields this screen has no use
     * for.
     *
     * <p>Declared here rather than imported from another feature, which is what
     * every other feature in this codebase does — {@code ChatDtos},
     * {@code CommentDtos} and {@code ResourceDtos} each carry their own. The
     * contract inlines it into ten schemas and the generated client produces
     * one TypeScript type regardless, so the duplication is in Java only, and
     * the alternative is a shared DTO package, which is the layer folder
     * CLAUDE.md forbids.
     *
     * <p>{@code avatarUrl} and {@code handle} are omitted rather than sent as
     * null: nothing on S-16 renders an avatar, and every field added here is a
     * join this query would have to carry over a table that only grows.
     */
    record UserRef(long id, String displayName, String role) {
    }

    /**
     * One audited action.
     *
     * @param entityId the subject, as a string, because that is what the
     *                 contract declares and because half the subjects in this
     *                 product are ticket codes rather than numbers. The
     *                 database keeps the two apart in {@code entity_id} and
     *                 {@code entity_ref}; the wire has never needed to.
     * @param actor    null for SYSTEM — a scanner or the mail engine, with no
     *                 human behind it. Null rather than a fabricated
     *                 {@code {id: 0, displayName: "System"}}, so that the
     *                 client renders the word and the data does not claim a
     *                 user id that no row has.
     * @param detail   the contract's free-form object. Absent — not an empty
     *                 object — where nothing was recorded; see
     *                 {@link #detailOf}.
     */
    @Schema(name = "AuditLogEntry")
    record Entry(
            long id,
            UserRef actor,
            String action,
            String entityType,
            String entityId,
            String ipAddress,
            String userAgent,
            Map<String, Object> detail,
            Instant createdAt) {
    }

    @Schema(name = "AuditLogListResponse")
    record ListResponse(List<Entry> data, PageMeta meta) {
    }

    /**
     * {@code old_value} and {@code new_value} as the contract's {@code detail}.
     *
     * <p>The table stores two nullable columns; the contract declares one
     * free-form object. Mapping them to {@code {old, new}} keeps the wire
     * stable if a later row ever carries a third thing worth saying, which two
     * fixed fields could not express without a contract change.
     *
     * <p><b>Null when there is nothing, never {@code {}}.</b> Most rows carry no
     * before-and-after at all — {@link AuditInterceptor} sees that a request
     * happened, not what a service changed underneath it — and an empty object
     * would render as a detail panel that appears to have loaded and found the
     * change to be nothing. Absent renders as "no detail recorded", which is
     * true.
     */
    static Map<String, Object> detailOf(String oldValue, String newValue) {
        if (oldValue == null && newValue == null) {
            return null;
        }
        Map<String, Object> detail = new LinkedHashMap<>(2);
        if (oldValue != null) {
            detail.put("old", oldValue);
        }
        if (newValue != null) {
            detail.put("new", newValue);
        }
        return detail;
    }
}
