package com.edunext.edutrack.domain.onboarding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObAttachmentRepository extends JpaRepository<ObAttachment, Long> {

    /**
     * C-106's document-checklist gate: clean, non-tombstoned attachments on
     * one step. Deliberately not filtered by {@link ObAttachmentKind} —
     * the template checklist names a document by label, not by kind, and
     * nothing on this row links back to which checklist entry it satisfies
     * (see {@code ObJourneyTemplateStepDoc}'s own note); the gate can only
     * count attachments against required entries, not match them one to one.
     */
    long countByStepIdAndScanStatusAndDeletedAtIsNull(Long stepId, ObAttachmentScanStatus scanStatus);

    /**
     * B-107 · every file filed against one client, <b>tombstones included</b>.
     *
     * <p>Not filtered by {@code deletedAt}, unlike the gate above, and the
     * difference is the point: a gate counts what is usable, where OB-05's
     * document card has to be able to say "removed by X on date". Which
     * tombstones are shown is a service decision, not a query one — see
     * {@code ObClientAttachmentService.isVisibleTombstone}.
     *
     * <p>Ordered by id rather than {@code createdAt}: the column is written by
     * the database default at insert, and two files uploaded inside the same
     * microsecond would otherwise order arbitrarily between reads. Ids are
     * monotonic and are the order they arrived.
     */
    List<ObAttachment> findByObClientIdOrderByIdAsc(Long obClientId);

    /**
     * B-107 · one file, resolved by <b>both</b> ids.
     *
     * <p>A real attachment id under a client that is not the one in the path
     * comes back empty and the caller answers 404, exactly as every nested
     * onboarding route resolves its child. Doing it in one query rather than
     * loading by id and comparing means "no such row" and "not on this client"
     * leave by the same line and stay indistinguishable.
     */
    Optional<ObAttachment> findByIdAndObClientId(Long id, Long obClientId);

    /**
     * C-121 · every file filed against one prerequisite task instance,
     * tombstones included — {@code findByObClientIdOrderByIdAsc}'s shape, one
     * owner arm over, for CP-04's submission list.
     */
    List<ObAttachment> findByPrereqTaskIdOrderByIdAsc(Long prereqTaskId);

    /**
     * C-121 · one file, resolved by both ids — {@code findByIdAndObClientId}'s
     * reasoning verbatim, so a submission id under the wrong task answers
     * "not found" rather than leaking whether it exists elsewhere.
     */
    Optional<ObAttachment> findByIdAndPrereqTaskId(Long id, Long prereqTaskId);
}
