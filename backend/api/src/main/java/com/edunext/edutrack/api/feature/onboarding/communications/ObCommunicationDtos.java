package com.edunext.edutrack.api.feature.onboarding.communications;

import com.edunext.edutrack.common.pagination.PageMeta;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * C-112 · the wire shapes for the communication timelines, matching
 * {@code contracts/openapi.yaml}'s {@code ObStepCommunication} and
 * {@code ObClientCommunication}.
 *
 * <h2>Two shapes rather than one, and the second is not the first plus a
 * wrapper</h2>
 *
 * <p>{@link ObStepCommunication} is one service's own timeline: the reader
 * already knows which service they are looking at, so naming it on every row
 * would be four repeated fields per entry. {@link ObClientCommunication} is
 * the stitched view, where <em>which service</em> is the only thing
 * distinguishing two adjacent rows and is therefore the point.
 *
 * <p>The contract spells the second out rather than composing it with {@code
 * allOf}, and this file follows it — a record that extended another would not
 * exist in Java anyway, and {@code ObWireConformanceTest} matches by simple
 * name against whichever schema the contract declares.
 */
final class ObCommunicationDtos {

    private ObCommunicationDtos() {
    }

    /**
     * The five values a person may record. The other three
     * ({@code COMMENT}, {@code ESCALATION}, {@code SYSTEM}) exist on the read
     * side and are written by the portal, C-126's escalation mirror and the
     * module itself — never by this route, which is why the create request's
     * pattern is narrower than the response enum.
     */
    static final String WRITEABLE_CHANNELS = "CALL|EMAIL|MEETING|WHATSAPP|OTHER";

    /** {@code STAFF} · {@code CLIENT} · {@code SYSTEM} — {@code ck_ob_comms_author}'s three shapes. */
    static final String AUTHOR_STAFF = "STAFF";
    static final String AUTHOR_CLIENT = "CLIENT";
    static final String AUTHOR_SYSTEM = "SYSTEM";

    /** What a {@code SYSTEM} row renders as, since it has neither author column set. */
    static final String SYSTEM_AUTHOR_NAME = "System";

    /** {@code UserRef} — one field, one convention, duplicated per package on {@code CallerIdentityAccess}'s own precedent. */
    record UserRef(long id, String displayName) {

        static UserRef of(Long id, String displayName) {
            return id == null ? null : new UserRef(id, displayName);
        }
    }

    /**
     * One entry on one service's timeline.
     *
     * <p>{@code recordedBy} is the <b>staff</b> identity and is null on a
     * client or system row; {@code authorName} is the string the row renders
     * whichever side wrote it. Two fields rather than one because they answer
     * different questions — one links to a user, the other is text.
     */
    record ObStepCommunication(
            long id,
            long stepId,
            String channel,
            Instant occurredAt,
            String summary,
            boolean isClientVisible,
            String authorType,
            String authorName,
            UserRef recordedBy,
            Instant createdAt) {

        static ObStepCommunication of(ObCommunicationRepository.Row row) {
            return new ObStepCommunication(
                    row.id(), row.stepId(), row.channel(), row.occurredAt(), row.summary(),
                    row.isClientVisible(), row.authorType(), authorNameOf(row),
                    UserRef.of(row.authorUserId(), row.authorUserName()), row.createdAt());
        }
    }

    /** One entry on the client-level stitched view — the same entry, plus where it came from. */
    record ObClientCommunication(
            long id,
            long obClientId,
            long journeyId,
            String productName,
            long stepId,
            String stepName,
            int stepSequence,
            String channel,
            Instant occurredAt,
            String summary,
            boolean isClientVisible,
            String authorType,
            String authorName,
            UserRef recordedBy,
            Instant createdAt) {

        static ObClientCommunication of(ObCommunicationRepository.Row row) {
            return new ObClientCommunication(
                    row.id(), row.obClientId(), row.journeyId(), row.productName(),
                    row.stepId(), row.stepName(), row.stepSequence(),
                    row.channel(), row.occurredAt(), row.summary(), row.isClientVisible(),
                    row.authorType(), authorNameOf(row),
                    UserRef.of(row.authorUserId(), row.authorUserName()), row.createdAt());
        }
    }

    /**
     * The one string a timeline row renders.
     *
     * <p>Falls back to the author type's own word rather than to null or to an
     * empty string: the contract makes {@code authorName} required, and a row
     * whose author cannot be rendered is exactly what {@code
     * ck_ob_comms_author} exists to make unrepresentable. A staff row whose
     * user has since been deleted is the one case that reaches the fallback,
     * and {@code fk_ob_comms_author_user} makes even that impossible today.
     */
    private static String authorNameOf(ObCommunicationRepository.Row row) {
        String name = switch (row.authorType() == null ? "" : row.authorType()) {
            case AUTHOR_STAFF -> row.authorUserName();
            case AUTHOR_CLIENT -> row.authorContactName();
            default -> SYSTEM_AUTHOR_NAME;
        };
        return name == null || name.isBlank() ? SYSTEM_AUTHOR_NAME : name;
    }

    record ObStepCommunicationResponse(ObStepCommunication data) {
    }

    record ObStepCommunicationListResponse(List<ObStepCommunication> data, PageMeta meta) {
    }

    record ObClientCommunicationListResponse(List<ObClientCommunication> data, PageMeta meta) {
    }

    /**
     * Capture, not delivery — the contract's own opening line. Recording that
     * a call happened is not the same act as sending a mail, and §7's outbox
     * is what does the second.
     *
     * <p><b>{@code isClientVisible} is a boxed {@link Boolean} and absent
     * means false.</b> A primitive would deserialise a missing field to false
     * too, but boxing keeps "the caller did not say" distinguishable from
     * "the caller said no" for the validation layer, and the default lives in
     * one place ({@code ObCommunicationService}) rather than in Jackson's.
     * Defaulting the other way is the failure the DDL calls unrecoverable:
     * an internal note the client has already read cannot be un-read.
     */
    record ObStepCommunicationCreateRequest(
            @NotBlank @Pattern(regexp = WRITEABLE_CHANNELS,
                    message = "must be one of CALL, EMAIL, MEETING, WHATSAPP, OTHER")
            String channel,
            @NotNull Instant occurredAt,
            @NotBlank @Size(max = 4000) String summary,
            Boolean isClientVisible) {
    }
}
