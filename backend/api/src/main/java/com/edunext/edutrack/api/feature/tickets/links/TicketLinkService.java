package com.edunext.edutrack.api.feature.tickets.links;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketLink;
import com.edunext.edutrack.domain.tickets.TicketLinkRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * C-064 · ticket linking, blueprint §16 item 17 and §7.5's create-form row.
 *
 * <h2>One row per relationship, and how BLOCKS/BLOCKED_BY stay one row</h2>
 *
 * <p>{@code ticket_links} stores exactly one row for a relationship, never a
 * pair. A caller may pick either {@code BLOCKS} or {@code BLOCKED_BY} — both
 * are legitimate things to mean, and blueprint §7.5 offers both — so
 * {@link #canonicalize} rewrites a submitted {@code BLOCKED_BY} into the
 * {@code BLOCKS} row the <em>other</em> ticket would have written, swapping
 * source and target. "A is blocked by B" and "B blocks A" are the same fact
 * and must produce the identical row, or {@code linkedTickets} would show the
 * relationship once on each ticket's own two rows instead of once as a pair
 * of readings of one row — and a later unlink would leave the other half
 * standing.
 *
 * <p>{@code RELATES_TO} is symmetric and canonicalised by ordering the two
 * ids — the smaller becomes {@code source_ticket_id} — for the identical
 * reason: "A relates to B" and "B relates to A" must collide rather than
 * produce two rows.
 *
 * <p>{@code DUPLICATE_OF} is genuinely directional (the duplicate names the
 * original, never the other way round) and is stored exactly as submitted.
 *
 * <h2>Reading a row from either side</h2>
 *
 * <p>{@link #toView} answers "how does this row read from ticket X" for
 * whichever ticket is asked — the stored type if X is the source, its
 * {@link TicketLinkType#inverse()} if X is the target. This is the one place
 * that logic exists; {@link #create} and {@link #viewsFor} both call it
 * rather than each re-deriving the label, which is what stops the create
 * response and the {@code /full} panel from ever disagreeing about what a row
 * means.
 */
@Service
public class TicketLinkService {

    private static final String TICKET_LINKED = "TICKET_LINKED";
    private static final String TICKET_UNLINKED = "TICKET_UNLINKED";

    private final ScopedTickets tickets;
    private final TicketLinkRepository links;
    private final TicketJournal journal;
    private final TicketLinkUserRefs people;

    TicketLinkService(ScopedTickets tickets,
                      TicketLinkRepository links,
                      TicketJournal journal,
                      TicketLinkUserRefs people) {
        this.tickets = tickets;
        this.links = links;
        this.journal = journal;
        this.people = people;
    }

    /**
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         404, identically for either ticket not existing and either one
     *         being out of this caller's scope (A-035)
     * @throws NotSubmittableLinkTypeException 400; {@code linkType} was {@code DUPLICATED_BY}
     * @throws SelfTicketLinkException         400; target names the same ticket as the path
     * @throws DuplicateTicketLinkException    409; this relationship already exists
     */
    @Transactional
    TicketLinkDtos.LinkedTicketView create(Authentication caller, String ticketCode,
                                           TicketLinkDtos.CreateLinkRequest request) {

        // Scope first, and only once for each side. requireByCode answers 404
        // identically for a code that does not exist and one out of scope —
        // both tickets get the same treatment, so a caller cannot use this
        // route to probe which ticket ids exist outside their own scope.
        Ticket source = tickets.requireByCode(caller, ticketCode);

        TicketLinkType type = parseType(request.linkType());
        if (type == TicketLinkType.DUPLICATED_BY) {
            throw new NotSubmittableLinkTypeException();
        }

        Ticket target = tickets.requireByCode(caller, request.targetTicketId());
        if (source.getId().equals(target.getId())) {
            throw new SelfTicketLinkException();
        }

        Canonical canonical = canonicalize(type, source.getId(), target.getId());
        if (links.existsBySourceTicketIdAndTargetTicketIdAndLinkType(
                canonical.sourceId(), canonical.targetId(), canonical.type().name())) {
            throw new DuplicateTicketLinkException();
        }

        TicketLink row = new TicketLink();
        row.setSourceTicketId(canonical.sourceId());
        row.setTargetTicketId(canonical.targetId());
        row.setLinkType(canonical.type().name());
        row.setCreatedBy(actorId(caller));
        row = links.save(row);

        // One history row, on the ticket the caller acted from, worded as
        // they submitted it — "BLOCKED_BY CRM-26-00312" for a caller who
        // picked "Is blocked by", even though the row just saved may store
        // the canonical BLOCKS in the opposite direction. What happened is
        // what the caller asked for; the storage direction is this class's
        // business, not the audit trail's.
        journal.append(linkedEntry(source, type, target, caller));

        return toView(row, source.getId(), TicketLinkDtos.TicketRef.of(target),
                people.resolve(List.of(row.getCreatedBy())).get(row.getCreatedBy()));
    }

    /**
     * @throws com.edunext.edutrack.api.security.scope.TicketNotFoundException
     *         404; the ticket does not exist or is out of scope
     * @throws TicketLinkNotFoundException
     *         404; {@code linkId} does not exist, or names a link touching
     *         neither end of this ticket
     */
    @Transactional
    void delete(Authentication caller, String ticketCode, long linkId) {
        Ticket ticket = tickets.requireByCode(caller, ticketCode);

        TicketLink row = links.findById(linkId).orElseThrow(TicketLinkNotFoundException::new);
        boolean touchesThisTicket = row.getSourceTicketId().equals(ticket.getId())
                || row.getTargetTicketId().equals(ticket.getId());
        if (!touchesThisTicket) {
            // Same answer as a row that does not exist at all — a caller
            // must not learn "link 41 is real, just not yours" by probing
            // ids across tickets, on AttachmentNotFoundException's precedent.
            throw new TicketLinkNotFoundException();
        }

        long otherId = row.getSourceTicketId().equals(ticket.getId())
                ? row.getTargetTicketId() : row.getSourceTicketId();
        // Best-effort, scoped: the other ticket may be out of this caller's
        // scope even though the link row itself is visible from this side.
        // The history entry names it when it can and falls back to the raw
        // id rather than failing the whole removal over a label.
        String otherCode = tickets.byIds(caller, List.of(otherId)).stream()
                .findFirst().map(Ticket::getTicketCode).orElse("ticket #" + otherId);

        String type = labelFrom(row, ticket.getId());
        links.delete(row);

        journal.append(unlinkedEntry(ticket, type, otherCode, caller));
    }

    /**
     * Every link touching {@code ticket}, as it reads from {@code ticket}'s
     * own side — for {@code detail.TicketDetailService}'s
     * {@code linkedTickets}.
     *
     * <p>The far-side ticket is resolved through {@link ScopedTickets#byIds},
     * so a link to a ticket outside this caller's scope is silently dropped
     * rather than shown with a broken or leaking reference — the same
     * "absence, not refusal" contract {@code byIds} itself keeps. A caller
     * who could not see the other ticket directly must not learn its title
     * or level by looking at this one's linked-tickets panel instead.
     */
    public List<TicketLinkDtos.LinkedTicketView> viewsFor(Authentication caller, Ticket ticket) {
        List<TicketLink> outgoing = links.findBySourceTicketId(ticket.getId());
        List<TicketLink> incoming = links.findByTargetTicketId(ticket.getId());

        List<TicketLink> all = new ArrayList<>(outgoing.size() + incoming.size());
        all.addAll(outgoing);
        all.addAll(incoming);
        if (all.isEmpty()) {
            return List.of();
        }

        List<Long> otherIds = all.stream()
                .map(row -> row.getSourceTicketId().equals(ticket.getId())
                        ? row.getTargetTicketId() : row.getSourceTicketId())
                .distinct()
                .toList();
        Map<Long, Ticket> others = tickets.byIds(caller, otherIds).stream()
                .collect(Collectors.toMap(Ticket::getId, t -> t, (first, duplicate) -> first));

        Map<Long, TicketLinkDtos.UserRef> creators = people.resolve(
                all.stream().map(TicketLink::getCreatedBy).toList());

        return all.stream()
                .map(row -> {
                    long otherId = row.getSourceTicketId().equals(ticket.getId())
                            ? row.getTargetTicketId() : row.getSourceTicketId();
                    Ticket other = others.get(otherId);
                    if (other == null) {
                        return null;
                    }
                    return toView(row, ticket.getId(), TicketLinkDtos.TicketRef.of(other),
                            creators.get(row.getCreatedBy()));
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TicketLinkDtos.LinkedTicketView::id))
                .toList();
    }

    // ── canonicalisation ────────────────────────────────────────────────────

    private record Canonical(TicketLinkType type, long sourceId, long targetId) {
    }

    private static Canonical canonicalize(TicketLinkType type, long sourceId, long targetId) {
        return switch (type) {
            case BLOCKED_BY -> new Canonical(TicketLinkType.BLOCKS, targetId, sourceId);
            case RELATES_TO -> sourceId <= targetId
                    ? new Canonical(TicketLinkType.RELATES_TO, sourceId, targetId)
                    : new Canonical(TicketLinkType.RELATES_TO, targetId, sourceId);
            case BLOCKS, DUPLICATE_OF -> new Canonical(type, sourceId, targetId);
            case DUPLICATED_BY -> throw new NotSubmittableLinkTypeException();
        };
    }

    private static TicketLinkDtos.LinkedTicketView toView(TicketLink row, long fromTicketId,
                                                           TicketLinkDtos.TicketRef otherRef,
                                                           TicketLinkDtos.UserRef createdBy) {
        return new TicketLinkDtos.LinkedTicketView(
                row.getId(), labelFrom(row, fromTicketId), otherRef, row.getCreatedAt(), createdBy);
    }

    /** How {@code row} reads from {@code fromTicketId}'s own side — see the class javadoc. */
    private static String labelFrom(TicketLink row, long fromTicketId) {
        boolean fromIsSource = row.getSourceTicketId().equals(fromTicketId);
        TicketLinkType stored = TicketLinkType.valueOf(row.getLinkType());
        return (fromIsSource ? stored : stored.inverse()).name();
    }

    private static TicketLinkType parseType(String raw) {
        try {
            return TicketLinkType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException notAType) {
            throw new UnknownLinkTypeException(raw);
        }
    }

    private static TicketHistory linkedEntry(Ticket source, TicketLinkType asSubmitted,
                                             Ticket target, Authentication caller) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(source.getId());
        entry.setCycleNo(source.getCurrentCycleNo());
        entry.setEventType(TICKET_LINKED);
        entry.setFieldName("linkedTicket");
        entry.setNewValue(asSubmitted.name() + " " + target.getTicketCode());
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        return entry;
    }

    private static TicketHistory unlinkedEntry(Ticket ticket, String type, String otherCode,
                                               Authentication caller) {
        TicketHistory entry = new TicketHistory();
        entry.setTicketId(ticket.getId());
        entry.setCycleNo(ticket.getCurrentCycleNo());
        entry.setEventType(TICKET_UNLINKED);
        entry.setFieldName("linkedTicket");
        entry.setOldValue(type + " " + otherCode);
        Long actor = actorId(caller);
        entry.setActorId(actor);
        entry.setActorType(actor == null ? "SYSTEM" : "USER");
        return entry;
    }

    private static Long actorId(Authentication caller) {
        return Optional.ofNullable(caller)
                .flatMap(CallerIdentity::of)
                .map(CallerIdentity::userId)
                .orElse(null);
    }
}
