package com.edunext.edutrack.api.feature.tickets.attachments;

import com.edunext.edutrack.domain.tickets.TicketAttachment;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * C-028 · the one query the tombstone needs and {@code TicketAttachmentRepository}
 * does not have: <b>every</b> row on a ticket, deleted ones included.
 *
 * <p>Everything C-023 – C-027 reads goes through
 * {@code findByTicketIdAndIsDeletedFalseOrderByCreatedAtAsc}, which is right for
 * all of them — an upload counts live rows against the caps, and a gallery draws
 * live files. §4B.4's deletion rule is the first thing in the feature that has to
 * see a row precisely <em>because</em> it is gone.
 *
 * <h2>Why a second repository rather than a method on the first</h2>
 *
 * <p>{@code TicketAttachmentRepository} lives in {@code backend/domain}, which is
 * Stream A's. One derived-query method is a small change, but it is still a change
 * to a shared module in a stream that does not own it, and the working agreement
 * asks for sign-off rather than a quiet edit. This feature has been here twice
 * already and answered the same way both times: C-025 used the domain repository
 * exactly as it found it, and C-027 declared {@code AttachmentSettingsRepository}
 * in this package rather than adding an entity to {@code domain}. A second Spring
 * Data interface over the same entity costs nothing at runtime — the proxies share
 * one persistence context — and it keeps C-028 inside Stream C's paths entirely.
 *
 * <p>Extends {@link Repository} rather than {@code JpaRepository}, deliberately.
 * The broad interface would publish {@code save}, {@code delete} and
 * {@code deleteById} on a second bean over a table whose whole rule is that rows
 * are tombstoned rather than removed. Nothing here should be able to hard-delete
 * one, so nothing here is given the method — the same argument
 * {@link AttachmentStorage} makes for not injecting an {@code S3Client}.
 *
 * <p><b>Named for the rows, not for the table.</b> Spring derives a bean name from
 * the simple class name, and {@code TicketAttachmentRepository} is taken by
 * {@code domain} — a collision takes out every {@code @SpringBootTest} in the
 * module with a message naming neither, which is the trap
 * {@link AttachmentSettingsRepository} documents at length.
 *
 * <p>No {@code @Repository} annotation: Spring Data discovers interfaces
 * extending {@link Repository} through repository scanning, and the stereotype
 * would be decoration. {@link AttachmentSettingsRepository} carries one because
 * it is a hand-written class rather than a derived proxy.
 */
interface AttachmentRows extends Repository<TicketAttachment, Long> {

    /**
     * Every attachment on a ticket in upload order, tombstones included.
     *
     * <p>Ordered by {@code createdAt} and not by {@code deletedAt}, so a removed
     * file keeps its place in the sequence — a tombstone that jumped to the end of
     * the gallery would misdescribe when the file was there, and the gallery is
     * read as a chronology.
     *
     * <p>Bounded by §4B.4's twenty-file cap plus however many tombstones the
     * ticket has accumulated, and served by {@code ix_attachments_ticket}. Unlike
     * the live listing this one has no ceiling from the caps — a ticket where
     * files are repeatedly attached and removed grows without one — which is
     * noted rather than guarded: at one row per removal it is a reporting concern
     * long before it is a query concern.
     */
    List<TicketAttachment> findByTicketIdOrderByCreatedAtAsc(Long ticketId);
}
