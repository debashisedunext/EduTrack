package com.edunext.edutrack.api.feature.tickets.links;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketHistory;
import com.edunext.edutrack.domain.tickets.TicketLink;
import com.edunext.edutrack.domain.tickets.TicketLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-064 · ticket linking, blueprint §16 item 17.
 *
 * <p>The assertions cluster on the two things that are invisible when they go
 * wrong: <b>canonicalisation</b> — a {@code BLOCKED_BY} submitted from either
 * ticket has to land on the identical row, or the relationship silently
 * duplicates itself the first time somebody describes it from the other
 * side — and <b>the inverse label</b>, {@link TicketLinkService#viewsFor}'s
 * one job, which a wrong reading turns into a ticket that claims to block
 * something that in fact blocks it.
 */
class TicketLinkServiceTest {

    private static final long TICKET_A = 100L;
    private static final long TICKET_B = 200L;
    private static final String CODE_A = "CRM-26-00100";
    private static final String CODE_B = "CRM-26-00200";
    private static final long ACTOR = 7L;

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketLinkRepository links = mock(TicketLinkRepository.class);
    private final TicketJournal journal = mock(TicketJournal.class);
    private final TicketLinkUserRefs people = mock(TicketLinkUserRefs.class);

    private final TicketLinkService service = new TicketLinkService(tickets, links, journal, people);

    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(ACTOR, "priya", "Priya Nair", "PM", List.of(), List.of()),
            "n/a", "ticket.update_progress");

    private Ticket ticketA;
    private Ticket ticketB;

    @BeforeEach
    void setUp() {
        ticketA = ticket(TICKET_A, CODE_A);
        ticketB = ticket(TICKET_B, CODE_B);
        when(tickets.requireByCode(any(), eq(CODE_A))).thenReturn(ticketA);
        when(tickets.requireByCode(any(), eq(CODE_B))).thenReturn(ticketB);
        when(links.save(any(TicketLink.class))).thenAnswer(call -> {
            TicketLink saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 900L);
            ReflectionTestUtils.setField(saved, "createdAt", Instant.parse("2026-08-19T09:00:00Z"));
            return saved;
        });
        // Collections.emptyMap(), not Map.of() — Map.of()'s get(null) throws,
        // and an unattributed row (createdBy null) is a real case this has to
        // tolerate: TicketLinkUserRefs.resolve returns a HashMap-backed map in
        // production, which answers null for a null key rather than throwing.
        when(people.resolve(anyCollection())).thenReturn(java.util.Collections.emptyMap());
    }

    // ── canonicalisation — one row per relationship ──────────────────────────

    @Nested
    @DisplayName("BLOCKS / BLOCKED_BY canonicalisation")
    class BlocksCanonicalisation {

        /** "A blocks B" is stored exactly as submitted. */
        @Test
        @DisplayName("BLOCKS is stored as submitted")
        void blocksStoredDirectly() {
            service.create(caller, CODE_A, request(CODE_B, "BLOCKS"));

            TicketLink saved = savedLink();
            assertThat(saved.getSourceTicketId()).isEqualTo(TICKET_A);
            assertThat(saved.getTargetTicketId()).isEqualTo(TICKET_B);
            assertThat(saved.getLinkType()).isEqualTo("BLOCKS");
        }

        /**
         * "A is blocked by B" means B blocks A — the row this writes must be
         * byte-identical to the one {@code BLOCKS} from B's side would write,
         * or the relationship would exist twice depending on who described it.
         */
        @Test
        @DisplayName("BLOCKED_BY is rewritten to the equivalent BLOCKS row, source and target swapped")
        void blockedByIsCanonicalised() {
            service.create(caller, CODE_A, request(CODE_B, "BLOCKED_BY"));

            TicketLink saved = savedLink();
            assertThat(saved.getSourceTicketId()).isEqualTo(TICKET_B);
            assertThat(saved.getTargetTicketId()).isEqualTo(TICKET_A);
            assertThat(saved.getLinkType()).isEqualTo("BLOCKS");
        }

        /** The duplicate check runs against the canonical triple, not the submitted one. */
        @Test
        @DisplayName("a BLOCKED_BY that duplicates an existing BLOCKS is refused")
        void blockedByCollidesWithExistingBlocks() {
            when(links.existsBySourceTicketIdAndTargetTicketIdAndLinkType(TICKET_B, TICKET_A, "BLOCKS"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_B, "BLOCKED_BY")))
                    .isInstanceOf(DuplicateTicketLinkException.class);

            verify(links, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("RELATES_TO canonicalisation")
    class RelatesToCanonicalisation {

        /** Ordered by id so "A relates to B" and "B relates to A" collide. */
        @Test
        @DisplayName("is stored with the smaller ticket id as source, regardless of submission direction")
        void orderedBySmallerId() {
            service.create(caller, CODE_B, request(CODE_A, "RELATES_TO"));

            TicketLink saved = savedLink();
            assertThat(saved.getSourceTicketId()).isEqualTo(TICKET_A);
            assertThat(saved.getTargetTicketId()).isEqualTo(TICKET_B);
        }
    }

    @Nested
    @DisplayName("DUPLICATE_OF")
    class DuplicateOf {

        /** Genuinely directional — the duplicate always names the original. */
        @Test
        @DisplayName("is stored exactly as submitted, with no swap")
        void storedDirectly() {
            service.create(caller, CODE_A, request(CODE_B, "DUPLICATE_OF"));

            TicketLink saved = savedLink();
            assertThat(saved.getSourceTicketId()).isEqualTo(TICKET_A);
            assertThat(saved.getTargetTicketId()).isEqualTo(TICKET_B);
            assertThat(saved.getLinkType()).isEqualTo("DUPLICATE_OF");
        }

        /** Never a submittable input — it exists only as a computed label. */
        @Test
        @DisplayName("DUPLICATED_BY is refused with no row written")
        void duplicatedByIsRefused() {
            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_B, "DUPLICATED_BY")))
                    .isInstanceOf(NotSubmittableLinkTypeException.class);

            verify(links, org.mockito.Mockito.never()).save(any());
            verifyNoInteractions(journal);
        }
    }

    // ── the edges ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        @DisplayName("a target naming the same ticket as the path is refused before any lookup of the target")
        void selfLinkRefused() {
            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_A, "RELATES_TO")))
                    .isInstanceOf(SelfTicketLinkException.class);

            verify(links, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("an unrecognised linkType is refused")
        void unknownTypeRefused() {
            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_B, "SUPERSEDES")))
                    .isInstanceOf(UnknownLinkTypeException.class);
        }

        @Test
        @DisplayName("an exact duplicate is 409 and nothing is written")
        void duplicateRefused() {
            when(links.existsBySourceTicketIdAndTargetTicketIdAndLinkType(TICKET_A, TICKET_B, "RELATES_TO"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_B, "RELATES_TO")))
                    .isInstanceOf(DuplicateTicketLinkException.class);

            verify(links, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("an out-of-scope source ticket is 404 and nothing else is consulted")
        void outOfScopeSourceIs404() {
            when(tickets.requireByCode(any(), eq(CODE_A))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_B, "RELATES_TO")))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(journal);
            verifyNoInteractions(links);
        }

        @Test
        @DisplayName("an out-of-scope target ticket is 404, identical to one that does not exist")
        void outOfScopeTargetIs404() {
            when(tickets.requireByCode(any(), eq(CODE_B))).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.create(caller, CODE_A, request(CODE_B, "RELATES_TO")))
                    .isInstanceOf(TicketNotFoundException.class);

            verify(links, org.mockito.Mockito.never()).save(any());
        }
    }

    // ── the history rows ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("history")
    class History {

        @Test
        @DisplayName("TICKET_LINKED is worded as the caller submitted it, even when the row is canonicalised")
        void linkedEntryUsesSubmittedDirection() {
            service.create(caller, CODE_A, request(CODE_B, "BLOCKED_BY"));

            TicketHistory entry = appendedEntry();
            assertThat(entry.getTicketId()).isEqualTo(TICKET_A);
            assertThat(entry.getEventType()).isEqualTo("TICKET_LINKED");
            assertThat(entry.getNewValue()).isEqualTo("BLOCKED_BY " + CODE_B);
            assertThat(entry.getActorId()).isEqualTo(ACTOR);
            assertThat(entry.getActorType()).isEqualTo("USER");
        }

        @Test
        @DisplayName("delete writes TICKET_UNLINKED naming this ticket's own reading of the removed row")
        void unlinkedEntryOnDelete() {
            TicketLink row = new TicketLink();
            ReflectionTestUtils.setField(row, "id", 900L);
            row.setSourceTicketId(TICKET_B);
            row.setTargetTicketId(TICKET_A);
            row.setLinkType("BLOCKS");
            when(links.findById(900L)).thenReturn(Optional.of(row));
            when(tickets.byIds(any(), eq(List.of(TICKET_B)))).thenReturn(List.of(ticketB));

            service.delete(caller, CODE_A, 900L);

            verify(links).delete(row);
            TicketHistory entry = appendedEntry();
            assertThat(entry.getTicketId()).isEqualTo(TICKET_A);
            assertThat(entry.getEventType()).isEqualTo("TICKET_UNLINKED");
            // A blocks-onto-A read from A's side is BLOCKED_BY — the inverse.
            assertThat(entry.getOldValue()).isEqualTo("BLOCKED_BY " + CODE_B);
        }
    }

    // ── deletion scope ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("a linkId touching neither end of this ticket is 404, same as one that does not exist")
        void notTouchingThisTicketIs404() {
            TicketLink foreign = new TicketLink();
            ReflectionTestUtils.setField(foreign, "id", 900L);
            foreign.setSourceTicketId(555L);
            foreign.setTargetTicketId(556L);
            foreign.setLinkType("RELATES_TO");
            when(links.findById(900L)).thenReturn(Optional.of(foreign));

            assertThatThrownBy(() -> service.delete(caller, CODE_A, 900L))
                    .isInstanceOf(TicketLinkNotFoundException.class);

            verify(links, org.mockito.Mockito.never()).delete(any());
        }

        @Test
        @DisplayName("a linkId that does not exist at all is 404")
        void missingIs404() {
            when(links.findById(900L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(caller, CODE_A, 900L))
                    .isInstanceOf(TicketLinkNotFoundException.class);
        }
    }

    // ── viewsFor — the inverse label ─────────────────────────────────────────

    @Nested
    @DisplayName("viewsFor")
    class ViewsFor {

        @Test
        @DisplayName("a row where this ticket is the source reads as stored")
        void asSource() {
            TicketLink row = new TicketLink();
            ReflectionTestUtils.setField(row, "id", 1L);
            row.setSourceTicketId(TICKET_A);
            row.setTargetTicketId(TICKET_B);
            row.setLinkType("BLOCKS");
            when(links.findBySourceTicketId(TICKET_A)).thenReturn(List.of(row));
            when(links.findByTargetTicketId(TICKET_A)).thenReturn(List.of());
            when(tickets.byIds(any(), eq(List.of(TICKET_B)))).thenReturn(List.of(ticketB));

            List<TicketLinkDtos.LinkedTicketView> views = service.viewsFor(caller, ticketA);

            assertThat(views).hasSize(1);
            assertThat(views.get(0).linkType()).isEqualTo("BLOCKS");
            assertThat(views.get(0).ticket().ticketId()).isEqualTo(CODE_B);
        }

        /** The whole point of the feature: B did not write "blocked by", A did. */
        @Test
        @DisplayName("a row where this ticket is the target reads as the inverse")
        void asTarget() {
            TicketLink row = new TicketLink();
            ReflectionTestUtils.setField(row, "id", 1L);
            row.setSourceTicketId(TICKET_A);
            row.setTargetTicketId(TICKET_B);
            row.setLinkType("BLOCKS");
            when(links.findBySourceTicketId(TICKET_B)).thenReturn(List.of());
            when(links.findByTargetTicketId(TICKET_B)).thenReturn(List.of(row));
            when(tickets.byIds(any(), eq(List.of(TICKET_A)))).thenReturn(List.of(ticketA));

            List<TicketLinkDtos.LinkedTicketView> views = service.viewsFor(caller, ticketB);

            assertThat(views).hasSize(1);
            assertThat(views.get(0).linkType()).isEqualTo("BLOCKED_BY");
            assertThat(views.get(0).ticket().ticketId()).isEqualTo(CODE_A);
        }

        /**
         * A-035's leak rule extended to the far side of a link: the caller must
         * not learn the title or level of a ticket they could not otherwise see.
         */
        @Test
        @DisplayName("a link to a ticket outside this caller's scope is dropped, not shown broken")
        void outOfScopeOtherSideIsDropped() {
            TicketLink row = new TicketLink();
            ReflectionTestUtils.setField(row, "id", 1L);
            row.setSourceTicketId(TICKET_A);
            row.setTargetTicketId(TICKET_B);
            row.setLinkType("RELATES_TO");
            when(links.findBySourceTicketId(TICKET_A)).thenReturn(List.of(row));
            when(links.findByTargetTicketId(TICKET_A)).thenReturn(List.of());
            // Scoped batch fetch answers empty — TICKET_B is not visible to this caller.
            when(tickets.byIds(any(), eq(List.of(TICKET_B)))).thenReturn(List.of());

            assertThat(service.viewsFor(caller, ticketA)).isEmpty();
        }

        @Test
        @DisplayName("no links at all answers an empty list without querying the other side")
        void noLinks() {
            when(links.findBySourceTicketId(TICKET_A)).thenReturn(List.of());
            when(links.findByTargetTicketId(TICKET_A)).thenReturn(List.of());

            assertThat(service.viewsFor(caller, ticketA)).isEmpty();
            verify(tickets, org.mockito.Mockito.never()).byIds(any(), anyCollection());
        }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private TicketLink savedLink() {
        ArgumentCaptor<TicketLink> captor = ArgumentCaptor.forClass(TicketLink.class);
        verify(links).save(captor.capture());
        return captor.getValue();
    }

    private TicketHistory appendedEntry() {
        ArgumentCaptor<TicketHistory> captor = ArgumentCaptor.forClass(TicketHistory.class);
        verify(journal).append(captor.capture());
        return captor.getValue();
    }

    private static TicketLinkDtos.CreateLinkRequest request(String targetCode, String type) {
        return new TicketLinkDtos.CreateLinkRequest(targetCode, type);
    }

    private static Ticket ticket(long id, String code) {
        Ticket t = new Ticket();
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "ticketCode", code);
        t.setTitle("Fixture ticket " + code);
        t.setLevel("MEDIUM");
        t.setOriginalLevel("MEDIUM");
        t.setStatus("IN_PROGRESS");
        t.setCurrentCycleNo((short) 1);
        return t;
    }
}
