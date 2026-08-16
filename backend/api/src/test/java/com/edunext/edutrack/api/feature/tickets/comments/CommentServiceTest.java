package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C-029 · what §4B.5 asks the write path to guarantee.
 *
 * <p>The assertions cluster around the two things that are invisible when they
 * go wrong: the <b>default</b> (a comment that leaks to a client because nobody
 * set a flag) and the <b>stamp</b> (a comment that cannot be placed in the
 * journey afterwards, which no later task can repair).
 */
class CommentServiceTest {

    private static final long TICKET = 347L;
    private static final long AUTHOR = 12L;
    private static final Instant POSTED_AT = Instant.parse("2026-08-16T10:15:30Z");

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketCommentRepository comments = mock(TicketCommentRepository.class);
    private final CommentRows rows = mock(CommentRows.class);
    private final CommentUserRefs people = mock(CommentUserRefs.class);

    /** Real, not mocked — §3.9's behaviour is the thing being relied on. */
    private final CommentSanitizer sanitizer = new CommentSanitizer();

    private final CommentService service =
            new CommentService(tickets, comments, rows, people, sanitizer);

    /**
     * The three-argument constructor, deliberately: the two-argument one leaves
     * {@code authenticated} false, and {@code CallerIdentity.of} returns empty
     * for an unauthenticated token — so a caller built the short way reaches the
     * service as nobody and every write fails on the author lookup rather than
     * on what the test is about.
     */
    private final Authentication caller = new TestingAuthenticationToken(
            new DevPrincipal(AUTHOR, "ravi", "Ravi Kumar", "DEVELOPER", List.of(), List.of()),
            "n/a", "ticket.update_progress");

    private Ticket ticket;

    @BeforeEach
    void setUp() {
        ticket = new Ticket();
        ticket.setCurrentCycleNo((short) 2);
        ticket.setCurrentStage("DEVELOPMENT");
        ticket.setCommentCount(7);

        when(tickets.require(any(), anyLong())).thenReturn(ticket);
        when(people.resolve(any())).thenReturn(CommentUserRefs.Resolved.empty());
        // save() returns what it was given, with the id and timestamp the
        // database would have supplied.
        //
        // `createdAt` is set reflectively because it has no setter: the column is
        // `DEFAULT CURRENT_TIMESTAMP(6)` and the field is @Generated(INSERT), so
        // the application never writes it and the entity is right not to offer a
        // way. Faking it here is the cost of unit-testing something whose clock
        // is the database's — and it is worth paying, because `editableUntil` is
        // derived from it and §4B.5's five minutes are otherwise only observable
        // in an integration test.
        when(comments.save(any(TicketComment.class))).thenAnswer(call -> {
            TicketComment row = call.getArgument(0);
            row.setId(99L);
            ReflectionTestUtils.setField(row, "createdAt", POSTED_AT);
            return row;
        });
    }

    private CommentDtos.CommentWriteRequest body(String html) {
        return new CommentDtos.CommentWriteRequest(html, null, null, null);
    }

    private TicketComment saved() {
        ArgumentCaptor<TicketComment> captor = ArgumentCaptor.forClass(TicketComment.class);
        org.mockito.Mockito.verify(comments).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("scope")
    class Scope {

        /**
         * A-035, and the ordering that makes it mean anything: the refusal has to
         * happen before the row is built, or a caller who cannot see the ticket
         * still learns it exists from how long the 404 took and whether the
         * counter moved.
         */
        @Test
        void anOutOfScopeTicketIs404AndNothingIsWritten() {
            when(tickets.require(any(), anyLong())).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.create(caller, TICKET, body("<p>hello</p>")))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(comments);
        }

        @Test
        void theSameIsTrueOfTheThreadRead() {
            when(tickets.require(any(), anyLong())).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.list(caller, TICKET, null, null, null))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(rows);
        }
    }

    @Nested
    @DisplayName("§4B.5 · internal, always")
    class Visibility {

        /**
         * The single most consequential line in this feature. §16: "an accidental
         * leak is far costlier than an extra click."
         */
        @Test
        void anOmittedFlagMeansInternal() {
            service.create(caller, TICKET, body("<p>hello</p>"));
            assertThat(saved().isInternal()).isTrue();
        }

        @Test
        void anExplicitFalseMeansInternal() {
            service.create(caller, TICKET,
                    new CommentDtos.CommentWriteRequest("<p>hello</p>", false, null, null));
            assertThat(saved().isInternal()).isTrue();
        }

        @Test
        @DisplayName("only an explicit true is client-visible")
        void anExplicitTrueIsClientVisible() {
            service.create(caller, TICKET,
                    new CommentDtos.CommentWriteRequest("<p>hello</p>", true, null, null));
            assertThat(saved().isInternal()).isFalse();
        }

        @Test
        @DisplayName("and the response says so in the contract's positive form")
        void theResponseInvertsTheColumn() {
            CommentDtos.CommentDto posted = service.create(caller, TICKET, body("<p>hello</p>"));
            assertThat(posted.isClientVisible()).isFalse();
        }
    }

    @Nested
    @DisplayName("§4B.5 · the journey stamp")
    class Stamp {

        /**
         * Written in C-029 although C-032 owns displaying it, because it is the
         * one field that cannot be backfilled — see {@code CommentService.stamp}.
         */
        @Test
        void copiesCycleAndStageFromTheTicketAtTimeOfWriting() {
            service.create(caller, TICKET, body("<p>hello</p>"));

            assertThat(saved().getCycleNo()).isEqualTo((short) 2);
            assertThat(saved().getStageCode()).isEqualTo("DEVELOPMENT");
        }

        /**
         * Null rather than a guess. A real first iteration is also {@code 1}, so
         * writing {@code 1} here would make the guess indistinguishable from the
         * fact and leave C-032 no way to find the rows needing repair.
         */
        @Test
        void leavesIterationNullUntilC042MakesItReadable() {
            service.create(caller, TICKET, body("<p>hello</p>"));
            assertThat(saved().getIterationNo()).isNull();
        }

        @Test
        @DisplayName("the stamp does not follow the ticket afterwards — it is a copy")
        void isACopyRatherThanAJoin() {
            service.create(caller, TICKET, body("<p>hello</p>"));
            TicketComment row = saved();

            ticket.setCurrentStage("QA");
            ticket.setCurrentCycleNo((short) 3);

            assertThat(row.getStageCode()).isEqualTo("DEVELOPMENT");
            assertThat(row.getCycleNo()).isEqualTo((short) 2);
        }
    }

    @Nested
    @DisplayName("§3.9 · sanitisation on the write path")
    class Sanitisation {

        @Test
        @DisplayName("what is stored is the sanitised body, not what was sent")
        void storesTheSanitisedBody() {
            service.create(caller, TICKET, body("<p>ok</p><script>alert(1)</script>"));

            assertThat(saved().getBodyHtml()).isEqualTo("<p>ok</p>").doesNotContain("script");
        }

        @Test
        void writesThePlainTextProjectionAlongside() {
            service.create(caller, TICKET, body("<ul><li>one</li><li>two</li></ul>"));

            assertThat(saved().getBodyText()).isEqualTo("one\ntwo");
        }

        /**
         * The case {@code @NotBlank} on the request cannot reach: a non-blank
         * string that means nothing once §3.9 has run.
         */
        @Test
        void aBodyThatReducesToNothingIsRefused() {
            assertThatThrownBy(() -> service.create(caller, TICKET, body("<script>alert(1)</script>")))
                    .isInstanceOf(InvalidCommentException.class)
                    .satisfies(e -> assertThat(((InvalidCommentException) e).field()).isEqualTo("body"));

            verifyNoInteractions(comments);
        }

        /**
         * The bound is re-applied to the sanitised value, which can be longer
         * than the one Bean Validation measured — see {@code CommentSanitizer}.
         */
        @Test
        void aBodyThatGrowsPastTheBoundWhenEscapedIsRefused() {
            String submitted = "&".repeat(20_000);

            assertThatThrownBy(() -> service.create(caller, TICKET, body(submitted)))
                    .isInstanceOf(InvalidCommentException.class)
                    .hasMessageContaining("100,000");

            verifyNoInteractions(comments);
        }
    }

    @Nested
    @DisplayName("what C-029 does not implement")
    class NotYet {

        /**
         * C-028's notes call "accepted and ignored" a defect on the mirror-image
         * field. A 201 is a promise that what was sent was stored.
         */
        @Test
        void attachmentIdsAreRefusedRatherThanDropped() {
            assertThatThrownBy(() -> service.create(caller, TICKET,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, null, List.of(4L))))
                    .isInstanceOf(InvalidCommentException.class)
                    .satisfies(e -> assertThat(((InvalidCommentException) e).field())
                            .isEqualTo("attachmentIds"));

            verifyNoInteractions(comments);
        }

        @Test
        void anEmptyAttachmentListIsNotARefusal() {
            service.create(caller, TICKET,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, null, List.of()));

            assertThat(saved().getBodyHtml()).isEqualTo("<p>hi</p>");
        }

        /**
         * Null rather than {@code []}, so C-030 can tell "nobody was mentioned"
         * from "mentions were never parsed" when it comes to fan notifications
         * out.
         */
        @Test
        void noMentionsMeansANullColumnRatherThanAnEmptyArray() {
            service.create(caller, TICKET,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, List.of(), null));

            assertThat(saved().getMentionedUserIds()).isNull();
        }

        @Test
        void mentionsAreStoredEvenThoughNothingIsNotifiedYet() {
            service.create(caller, TICKET,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, List.of(5L, 6L), null));

            assertThat(saved().getMentionedUserIds()).containsExactly(5L, 6L);
        }
    }

    @Nested
    @DisplayName("the row itself")
    class Row {

        @Test
        void isAttributedToTheCaller() {
            service.create(caller, TICKET, body("<p>hi</p>"));

            assertThat(saved().getAuthorId()).isEqualTo(AUTHOR);
            assertThat(saved().getTicketId()).isEqualTo(TICKET);
            assertThat(saved().getSource()).isEqualTo("WEB");
        }

        /**
         * The baseline migration names this service as the counter's maintainer —
         * "materialised counters maintained by the service layer on insert and
         * tombstone". Nothing had maintained it, so {@code TicketListDtos
         * .commentCount} served a hard zero on every row since the list shipped.
         */
        @Test
        void movesTheMaterialisedCounterOnTheTicket() {
            service.create(caller, TICKET, body("<p>hi</p>"));

            assertThat(ticket.getCommentCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("a refused comment does not move the counter")
        void doesNotMoveTheCounterOnARefusal() {
            assertThatThrownBy(() -> service.create(caller, TICKET, body("<script>x</script>")))
                    .isInstanceOf(InvalidCommentException.class);

            assertThat(ticket.getCommentCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("posts as not-edited, not-deleted, with the five-minute window open")
        void carriesSection4B5sEditWindow() {
            CommentDtos.CommentDto posted = service.create(caller, TICKET, body("<p>hi</p>"));

            assertThat(posted.isEdited()).isFalse();
            assertThat(posted.isDeleted()).isFalse();
            assertThat(posted.originalBody()).isNull();
            assertThat(posted.editableUntil())
                    .isEqualTo(Instant.parse("2026-08-16T10:20:30Z"));
        }
    }
}
