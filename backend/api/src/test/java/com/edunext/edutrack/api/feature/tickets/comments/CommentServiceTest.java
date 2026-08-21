package com.edunext.edutrack.api.feature.tickets.comments;

import com.edunext.edutrack.api.feature.notifications.events.TicketEventNotifier;
import com.edunext.edutrack.api.security.dev.DevPrincipal;
import com.edunext.edutrack.api.security.scope.ScopedTickets;
import com.edunext.edutrack.api.security.scope.TicketNotFoundException;
import com.edunext.edutrack.domain.journal.TicketJournal;
import com.edunext.edutrack.domain.tickets.Ticket;
import com.edunext.edutrack.domain.tickets.TicketComment;
import com.edunext.edutrack.domain.tickets.TicketCommentRepository;
import com.edunext.edutrack.domain.workflow.TicketStageTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
    /** The route and the service are addressed by CODE now; TICKET stays for the row id. */
    private static final String TICKET_CODE = "CRM-26-00347";
    private static final long PROJECT = 8L;
    private static final long AUTHOR = 12L;
    private static final Instant POSTED_AT = Instant.parse("2026-08-16T10:15:30Z");

    private final ScopedTickets tickets = mock(ScopedTickets.class);
    private final TicketCommentRepository comments = mock(TicketCommentRepository.class);
    private final CommentRows rows = mock(CommentRows.class);
    private final CommentUserRefs people = mock(CommentUserRefs.class);

    /** Real, not mocked — §3.9's behaviour is the thing being relied on. */
    private final CommentSanitizer sanitizer = new CommentSanitizer();

    private final CommentMentions mentions = mock(CommentMentions.class);
    private final CommentMentionNotifier mentionNotifier = mock(CommentMentionNotifier.class);

    /**
     * C-032 · the door {@code stamp} reads {@code iterationNo} through.
     * Unstubbed calls answer {@code Optional.empty()} — Mockito's own default
     * for an {@code Optional}-returning method — which is what keeps every
     * nested class outside {@link Stamp} unaware this dependency exists at
     * all.
     */
    private final TicketJournal journal = mock(TicketJournal.class);

    /**
     * D-14 · <b>no edit window, which is the shipped default.</b> The five
     * minutes are still implemented and are exercised by
     * {@link EditWindow.WhenAWindowIsConfigured}, which builds its own
     * properties — so both the behaviour we ship and the behaviour an operator
     * can restore are covered, and neither is assumed from the other.
     */
    private CommentProperties properties = new CommentProperties(null);

    /**
     * C-033 · a fixed clock, four minutes after {@link #POSTED_AT}, so the edit
     * window is open by default and a test that wants it shut moves the clock
     * rather than the fixture. Waiting five real minutes is not a test anybody
     * runs, which is the whole reason {@code CommentService} takes a
     * {@code Clock}.
     */
    private Clock clock = Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(4)), ZoneOffset.UTC);

    /**
     * Rebuilt per call rather than held in a field, because {@link #clock} is
     * reassigned by the tests that stand outside the window and a service built
     * in a field initialiser would have captured the old one.
     */
    /**
     * D-037 · the §4B.6 producer this service now calls. Mocked and unasserted
     * here: what it sends is `TicketEventNotifierTest`'s subject, and this file
     * is about the write it must never interfere with.
     */
    private final TicketEventNotifier eventNotifier = mock(TicketEventNotifier.class);

    private CommentService service() {
        return new CommentService(tickets, comments, rows, people, sanitizer, mentions,
                mentionNotifier, eventNotifier, properties, journal, clock);
    }

    /**
     * The C-029 and C-030 blocks use this; it reads {@link #properties} and
     * {@link #clock} at construction, which is fine for them because neither
     * reassigns either. C-033's blocks call {@link #service()} instead.
     */
    private final CommentService service = new CommentService(
            tickets, comments, rows, people, sanitizer, mentions, mentionNotifier, eventNotifier, properties,
            journal, Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(4)), ZoneOffset.UTC));

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
        ticket.setId(TICKET);
        ticket.setProjectId(PROJECT);
        ticket.setTicketCode("CRM-26-00347");
        ticket.setTitle("Login page throws on submit");
        ticket.setCurrentCycleNo((short) 2);
        ticket.setCurrentStage("DEVELOPMENT");
        ticket.setCommentCount(7);

        when(tickets.requireByCode(any(), anyString())).thenReturn(ticket);
        // Nobody resolves unless a test says so. The default matters: it is what
        // makes every pre-existing assertion here run through C-030's new write
        // path without a mention in sight, which is the common case.
        when(mentions.resolveProjectMembers(anyLong(), any())).thenReturn(List.of());
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
            when(tickets.requireByCode(any(), anyString())).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.create(caller, TICKET_CODE, body("<p>hello</p>")))
                    .isInstanceOf(TicketNotFoundException.class);

            verifyNoInteractions(comments);
        }

        @Test
        void theSameIsTrueOfTheThreadRead() {
            when(tickets.requireByCode(any(), anyString())).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service.list(caller, TICKET_CODE, null, null, null))
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
            service.create(caller, TICKET_CODE, body("<p>hello</p>"));
            assertThat(saved().isInternal()).isTrue();
        }

        @Test
        void anExplicitFalseMeansInternal() {
            service.create(caller, TICKET_CODE,
                    new CommentDtos.CommentWriteRequest("<p>hello</p>", false, null, null));
            assertThat(saved().isInternal()).isTrue();
        }

        @Test
        @DisplayName("only an explicit true is client-visible")
        void anExplicitTrueIsClientVisible() {
            service.create(caller, TICKET_CODE,
                    new CommentDtos.CommentWriteRequest("<p>hello</p>", true, null, null));
            assertThat(saved().isInternal()).isFalse();
        }

        @Test
        @DisplayName("and the response says so in the contract's positive form")
        void theResponseInvertsTheColumn() {
            CommentDtos.CommentDto posted = service.create(caller, TICKET_CODE, body("<p>hello</p>"));
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
            service.create(caller, TICKET_CODE, body("<p>hello</p>"));

            assertThat(saved().getCycleNo()).isEqualTo((short) 2);
            assertThat(saved().getStageCode()).isEqualTo("DEVELOPMENT");
        }

        /** C-032 · the fourth stamped field, read off the caller's identity. */
        @Test
        void stampsTheCallersRoleAtTimeOfWriting() {
            service.create(caller, TICKET_CODE, body("<p>hello</p>"));
            assertThat(saved().getAuthorRole()).isEqualTo("DEVELOPER");
        }

        /**
         * C-032 · now readable through {@code TicketJournal#openHopFor}, which
         * C-042 built. The open hop's own {@code cycleNo} matches the ticket's
         * current one, so it is trusted.
         */
        @Test
        @DisplayName("stamps iteration from the ticket's open hop, now that C-042 makes it readable")
        void stampsIterationFromTheOpenHop() {
            when(journal.openHopFor(TICKET)).thenReturn(Optional.of(openHop((short) 2, (short) 3)));

            service.create(caller, TICKET_CODE, body("<p>hello</p>"));

            assertThat(saved().getIterationNo()).isEqualTo((short) 3);
        }

        /**
         * A freshly created ticket with no first hop yet — the one case
         * {@code cycleNo}/{@code stageCode} were already nullable for.
         */
        @Test
        @DisplayName("leaves iteration null when there is no open hop yet")
        void leavesIterationNullWithNoOpenHop() {
            when(journal.openHopFor(TICKET)).thenReturn(Optional.empty());

            service.create(caller, TICKET_CODE, body("<p>hello</p>"));

            assertThat(saved().getIterationNo()).isNull();
        }

        /**
         * <b>Guard.</b> {@code ReopenService} can leave a stale open hop at the
         * previous cycle's last stage until something advances it — the same
         * staleness {@code TransitionService#advance} checks for before building
         * its next hop. Stamping that hop's iteration onto a comment written
         * against the new cycle would read as real: removing the
         * {@code hop.getCycleNo() == ticket.getCurrentCycleNo()} filter in
         * {@code CommentService.currentIterationNo} turns this red.
         */
        @Test
        @DisplayName("ignores a stale open hop left behind at an earlier cycle")
        void ignoresAStaleOpenHopFromAnEarlierCycle() {
            when(journal.openHopFor(TICKET)).thenReturn(Optional.of(openHop((short) 1, (short) 4)));

            service.create(caller, TICKET_CODE, body("<p>hello</p>"));

            assertThat(saved().getIterationNo()).isNull();
        }

        @Test
        @DisplayName("the stamp does not follow the ticket afterwards — it is a copy")
        void isACopyRatherThanAJoin() {
            service.create(caller, TICKET_CODE, body("<p>hello</p>"));
            TicketComment row = saved();

            ticket.setCurrentStage("QA");
            ticket.setCurrentCycleNo((short) 3);

            assertThat(row.getStageCode()).isEqualTo("DEVELOPMENT");
            assertThat(row.getCycleNo()).isEqualTo((short) 2);
        }

        private TicketStageTransition openHop(short cycleNo, short iterationNo) {
            TicketStageTransition hop = new TicketStageTransition();
            hop.setTicketId(TICKET);
            hop.setCycleNo(cycleNo);
            hop.setIterationNo(iterationNo);
            hop.setToStage("DEVELOPMENT");
            return hop;
        }
    }

    @Nested
    @DisplayName("§3.9 · sanitisation on the write path")
    class Sanitisation {

        @Test
        @DisplayName("what is stored is the sanitised body, not what was sent")
        void storesTheSanitisedBody() {
            service.create(caller, TICKET_CODE, body("<p>ok</p><script>alert(1)</script>"));

            assertThat(saved().getBodyHtml()).isEqualTo("<p>ok</p>").doesNotContain("script");
        }

        @Test
        void writesThePlainTextProjectionAlongside() {
            service.create(caller, TICKET_CODE, body("<ul><li>one</li><li>two</li></ul>"));

            assertThat(saved().getBodyText()).isEqualTo("one\ntwo");
        }

        /**
         * The case {@code @NotBlank} on the request cannot reach: a non-blank
         * string that means nothing once §3.9 has run.
         */
        @Test
        void aBodyThatReducesToNothingIsRefused() {
            assertThatThrownBy(() -> service.create(caller, TICKET_CODE, body("<script>alert(1)</script>")))
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

            assertThatThrownBy(() -> service.create(caller, TICKET_CODE, body(submitted)))
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
            assertThatThrownBy(() -> service.create(caller, TICKET_CODE,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, null, List.of(4L))))
                    .isInstanceOf(InvalidCommentException.class)
                    .satisfies(e -> assertThat(((InvalidCommentException) e).field())
                            .isEqualTo("attachmentIds"));

            verifyNoInteractions(comments);
        }

        @Test
        void anEmptyAttachmentListIsNotARefusal() {
            service.create(caller, TICKET_CODE,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, null, List.of()));

            assertThat(saved().getBodyHtml()).isEqualTo("<p>hi</p>");
        }

        /**
         * Null rather than {@code []}, so "nobody was mentioned" and "mentions
         * were never parsed" stay distinguishable in the data.
         */
        @Test
        void noMentionsMeansANullColumnRatherThanAnEmptyArray() {
            service.create(caller, TICKET_CODE,
                    new CommentDtos.CommentWriteRequest("<p>hi</p>", null, List.of(), null));

            assertThat(saved().getMentionedUserIds()).isNull();
        }
    }

    /**
     * C-030 · the fan-out, and the one rule that makes it safe.
     *
     * <p>These are the assertions that would let somebody quietly re-introduce
     * a caller-supplied recipient list. D-052 settled that question for chat and
     * the reasoning is identical here: a request that names its own recipients
     * is a notification-and-email fan-out with no membership check in front of
     * it.
     */
    @Nested
    @DisplayName("C-030 · @mentions")
    class Mentions {

        private final CommentMentions.MentionedUser meera =
                new CommentMentions.MentionedUser(31L, "meera.s", "Meera S", "meera@edunext.com");

        @Test
        @DisplayName("the body is what is parsed — the request's own list is not consulted")
        void theRequestsListIsIgnored() {
            service.create(caller, TICKET_CODE, new CommentDtos.CommentWriteRequest(
                    "<p>no handles here</p>", null, List.of(999L), null));

            // 999 was asked for and is not stored. Anything else would let a
            // caller aim the fan-out at a user it never named in the body.
            assertThat(saved().getMentionedUserIds()).isNull();
            verifyNoInteractions(mentionNotifier);
        }

        @Test
        void handlesAreParsedFromTheBodyAndResolvedAgainstTheTicketsProject() {
            when(mentions.resolveProjectMembers(eq(PROJECT), eq(List.of("meera.s"))))
                    .thenReturn(List.of(meera));

            service.create(caller, TICKET_CODE, new CommentDtos.CommentWriteRequest(
                    "<p>Can you look at this @meera.s ?</p>", null, null, null));

            assertThat(saved().getMentionedUserIds()).containsExactly(31L);
        }

        @Test
        @DisplayName("a handle that is not a project member stays plain text")
        void anUnresolvedHandleIsNotStoredAndNotNotified() {
            // The resolver's default answer is "nobody", which is what an
            // outsider, a leaver and a typo all look like — deliberately
            // indistinguishable, for the reason A-035 returns 404 not 403.
            service.create(caller, TICKET_CODE, new CommentDtos.CommentWriteRequest(
                    "<p>ask @someone.else</p>", null, null, null));

            assertThat(saved().getMentionedUserIds()).isNull();
            verifyNoInteractions(mentionNotifier);
        }

        /**
         * The parser's lookbehind, exercised through the service because this is
         * the case that reaches production: comments quote addresses constantly,
         * and without the rule every one of them notifies whoever owns that
         * domain as a username.
         */
        @Test
        void anEmailAddressInTheBodyIsNotAMention() {
            service.create(caller, TICKET_CODE, new CommentDtos.CommentWriteRequest(
                    "<p>chase this with ops@edunext.com</p>", null, null, null));

            // Never even asked: no candidate survived the parser.
            verify(mentions).resolveProjectMembers(PROJECT, List.of());
            verifyNoInteractions(mentionNotifier);
        }

        @Test
        @DisplayName("the notifier is handed the saved comment's id, not the ticket's")
        void notifiesAfterTheRowExists() {
            when(mentions.resolveProjectMembers(anyLong(), any())).thenReturn(List.of(meera));

            service.create(caller, TICKET_CODE, new CommentDtos.CommentWriteRequest(
                    "<p>@meera.s</p>", null, null, null));

            // 99 is what save() stamps. A notification deep-linking to a comment
            // that does not exist yet is the failure this ordering prevents.
            verify(mentionNotifier).mentioned(eq(ticket), eq(99L), eq(AUTHOR), any(), eq(List.of(meera)));
        }

        /**
         * §3.9 runs first, so a handle only inside markup was never text a reader
         * would see. {@code <a href="/x?q=@ravi">} does not survive the sanitiser
         * as text, and the plain-text projection is what the parser is given.
         */
        @Test
        void theParserSeesThePlainTextProjectionRatherThanTheMarkup() {
            service.create(caller, TICKET_CODE, new CommentDtos.CommentWriteRequest(
                    "<p><a href=\"https://edunext.test/s?q=@ravi\">search</a></p>", null, null, null));

            verify(mentions).resolveProjectMembers(PROJECT, List.of());
        }
    }

    @Nested
    @DisplayName("the row itself")
    class Row {

        @Test
        void isAttributedToTheCaller() {
            service.create(caller, TICKET_CODE, body("<p>hi</p>"));

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
            service.create(caller, TICKET_CODE, body("<p>hi</p>"));

            assertThat(ticket.getCommentCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("a refused comment does not move the counter")
        void doesNotMoveTheCounterOnARefusal() {
            assertThatThrownBy(() -> service.create(caller, TICKET_CODE, body("<script>x</script>")))
                    .isInstanceOf(InvalidCommentException.class);

            assertThat(ticket.getCommentCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("posts as not-edited, not-deleted, and with no edit deadline")
        void carriesSection4B5sEditWindow() {
            CommentDtos.CommentDto posted = service.create(caller, TICKET_CODE, body("<p>hi</p>"));

            assertThat(posted.isEdited()).isFalse();
            assertThat(posted.isDeleted()).isFalse();
            assertThat(posted.originalBody()).isNull();
            assertThat(posted.editedAt()).isNull();
            // D-14 · asserted null the same way it was asserted at
            // 10:20:30 before the window was lifted, because the field still
            // has to be *sent* — a client reading a missing key and a client
            // reading an explicit null behave differently, and this is the
            // field whose null a client must not read as "locked".
            assertThat(posted.editableUntil()).isNull();
        }
    }

    /**
     * C-033 · blueprint §4B.5's immutability row.
     *
     * <p>Written adversarially rather than as a happy path, because every rule
     * here fails <em>silently</em> when it is wrong: an edit that overwrites
     * {@code original_body} a second time still returns 200 and still shows an
     * "edited" marker, and a tombstone that keeps its text still says "removed".
     * The three marked as guards were each verified by breaking the guard and
     * watching them go red.
     */
    @Nested
    @DisplayName("C-033 · the edit window")
    class EditWindow {

        private TicketComment existing;

        @BeforeEach
        void postedFourMinutesAgo() {
            existing = new TicketComment();
            existing.setId(99L);
            existing.setTicketId(TICKET);
            existing.setAuthorId(AUTHOR);
            existing.setBodyHtml("<p>Root cause is the retry timeout.</p>");
            existing.setBodyText("Root cause is the retry timeout.");
            existing.setInternal(true);
            existing.setCycleNo((short) 2);
            existing.setStageCode("DEVELOPMENT");
            ReflectionTestUtils.setField(existing, "createdAt", POSTED_AT);
            when(rows.find(TICKET, 99L)).thenReturn(Optional.of(existing));
        }

        private CommentDtos.EditCommentRequest edit(String html) {
            return new CommentDtos.EditCommentRequest(html);
        }

        @Test
        @DisplayName("the author may rewrite inside the window")
        void theAuthorMayRewriteInsideTheWindow() {
            CommentDtos.CommentDto result = service()
                    .edit(caller, TICKET_CODE, 99L, edit("<p>Root cause is the connection pool.</p>"));

            assertThat(result.body()).isEqualTo("<p>Root cause is the connection pool.</p>");
            assertThat(result.isEdited()).isTrue();
            assertThat(existing.getEditedAt()).isEqualTo(clock.instant());
        }

        @Test
        @DisplayName("the original is preserved on the first edit")
        void preservesTheOriginal() {
            service().edit(caller, TICKET_CODE, 99L, edit("<p>second</p>"));

            assertThat(existing.getOriginalBody())
                    .isEqualTo("<p>Root cause is the retry timeout.</p>");
        }

        /**
         * <b>Guard.</b> A second edit overwriting {@code original_body} would
         * leave the FIRST REVISION standing as the "original" and lose what the
         * author actually posted — and it would look entirely fine, because the
         * field is populated and the marker is showing. Removing the null check
         * in {@code CommentService.edit} turns exactly this test red and nothing
         * else in the suite.
         */
        @Test
        @DisplayName("a second edit does not overwrite the original")
        void preservesTheFirstOriginalNotTheLast() {
            service().edit(caller, TICKET_CODE, 99L, edit("<p>second</p>"));
            service().edit(caller, TICKET_CODE, 99L, edit("<p>third</p>"));

            assertThat(existing.getBodyHtml()).isEqualTo("<p>third</p>");
            assertThat(existing.getOriginalBody())
                    .isEqualTo("<p>Root cause is the retry timeout.</p>");
        }

        /**
         * D-14 · the change itself, and the case that prompted it.
         *
         * <p>Thirty minutes is the scenario Divyansh gave: a developer posts a
         * root-cause note and remembers the missing half later. Under §4B.5 the
         * only remedy was a second card reading "correction to the above".
         */
        @Test
        @DisplayName("D-14 · half an hour later is still editable")
        void hasNoTimeLimitByDefault() {
            clock = Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(30)), ZoneOffset.UTC);

            assertThat(service().edit(caller, TICKET_CODE, 99L, edit("<p>and the retry count was 3</p>")).body())
                    .isEqualTo("<p>and the retry count was 3</p>");
        }

        /**
         * The stronger version of the same claim: "for as long as the comment
         * exists" is not "for a generously long window". A year is chosen
         * because any implementation that quietly kept a bound would almost
         * certainly fail here and might not at thirty minutes.
         */
        @Test
        @DisplayName("D-14 · and so is a year later")
        void reallyHasNoTimeLimit() {
            clock = Clock.fixed(POSTED_AT.plus(Duration.ofDays(365)), ZoneOffset.UTC);

            assertThat(service().edit(caller, TICKET_CODE, 99L, edit("<p>a year on</p>")).body())
                    .isEqualTo("<p>a year on</p>");
        }

        @Test
        @DisplayName("D-14 · no deadline goes on the wire, and that does not mean locked")
        void sendsNoDeadline() {
            CommentDtos.CommentDto edited =
                    service().edit(caller, TICKET_CODE, 99L, edit("<p>rewritten</p>"));

            assertThat(edited.editableUntil()).isNull();
            assertThat(edited.isEdited()).isTrue();
            // The field a reader needs once "edited" stops implying "moments
            // ago". Null on the wire before D-14 put it there.
            assertThat(edited.editedAt()).isEqualTo(clock.instant());
        }

        /**
         * D-14 removed the window from the <b>default configuration</b>, not
         * from the codebase. §4B.5 is restorable with one property and these
         * prove it still works — otherwise the enforcement rots untested and
         * "just set edit-window" becomes advice nobody has checked.
         */
        @Nested
        @DisplayName("when an operator restores §4B.5's window")
        class WhenAWindowIsConfigured {

            @BeforeEach
            void fiveMinutes() {
                properties = new CommentProperties(Duration.ofMinutes(5));
            }

            @Test
            @DisplayName("inside it, the author may still rewrite")
            void insideTheWindow() {
                clock = Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(4)), ZoneOffset.UTC);

                assertThat(service().edit(caller, TICKET_CODE, 99L, edit("<p>in time</p>")).body())
                        .isEqualTo("<p>in time</p>");
            }

            @Test
            @DisplayName("outside it, it is locked — for the author too")
            void locksAfterTheWindow() {
                clock = Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(5).plusSeconds(1)), ZoneOffset.UTC);

                assertThatThrownBy(() -> service().edit(caller, TICKET_CODE, 99L, edit("<p>late</p>")))
                        .isInstanceOf(CommentNotEditableException.class)
                        .hasMessageContaining("locked");
                assertThat(existing.getBodyHtml()).isEqualTo("<p>Root cause is the retry timeout.</p>");
            }

            @Test
            @DisplayName("exactly on the deadline is still editable")
            void theBoundaryIsInclusive() {
                clock = Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);

                assertThat(service().edit(caller, TICKET_CODE, 99L, edit("<p>just in time</p>")).body())
                        .isEqualTo("<p>just in time</p>");
            }

            @Test
            @DisplayName("and the deadline reaches the client, so the countdown returns with it")
            void sendsTheDeadline() {
                clock = Clock.fixed(POSTED_AT.plus(Duration.ofMinutes(1)), ZoneOffset.UTC);

                assertThat(service().edit(caller, TICKET_CODE, 99L, edit("<p>x</p>")).editableUntil())
                        .isEqualTo(POSTED_AT.plus(Duration.ofMinutes(5)));
            }
        }

        /**
         * <b>Guard</b>, and the asymmetry with {@link Deletion} that the whole
         * task turns on. §4B.5: "no role, including Admin, can silently rewrite a
         * comment". An Admin who could edit would leave the thread attributing
         * the new wording to Ravi, which is that rewrite however the card is
         * labelled — so PM and Admin are refused here and allowed on the delete.
         */
        @Test
        @DisplayName("no role, including Admin, may edit somebody else's comment")
        void noRoleMayRewriteSomebodyElses() {
            for (String role : List.of("ADMIN", "PM", "DEVELOPER")) {
                Authentication other = new TestingAuthenticationToken(
                        new DevPrincipal(999L, "priya", "Priya Nair", role, List.of(), List.of()),
                        "n/a", "ticket.update_progress");

                assertThatThrownBy(() -> service().edit(other, TICKET_CODE, 99L, edit("<p>mine now</p>")))
                        .as("%s must not be able to rewrite another user's comment", role)
                        .isInstanceOf(CommentNotEditableException.class);
            }
            assertThat(existing.getBodyHtml()).isEqualTo("<p>Root cause is the retry timeout.</p>");
        }

        @Test
        @DisplayName("§3.9 runs on the edit, not only on the post")
        void sanitisesTheEditedBody() {
            service().edit(caller, TICKET_CODE, 99L, edit("<p>ok</p><script>alert(1)</script>"));

            assertThat(existing.getBodyHtml()).doesNotContain("script");
        }

        /**
         * An edit that sanitises to nothing is a 400, not an empty comment —
         * otherwise the window is a way to blank a comment without leaving the
         * tombstone that deleting one does.
         */
        @Test
        @DisplayName("an edit that reduces to nothing is refused")
        void refusesAnEditThatSanitisesAway() {
            assertThatThrownBy(() -> service().edit(caller, TICKET_CODE, 99L, edit("<script>x</script>")))
                    .isInstanceOf(InvalidCommentException.class);

            assertThat(existing.getBodyHtml()).isEqualTo("<p>Root cause is the retry timeout.</p>");
        }

        /**
         * <b>Guard</b>, and the one an ordinary implementation gets wrong.
         * Editing re-parses the body, so re-notifying everyone it names makes
         * post-edit-edit a way to ring the same bell indefinitely. The case that
         * actually reaches production is duller: somebody fixing a typo in a
         * sentence that happens to contain a name, which D-035's rate limit does
         * not catch either.
         */
        @Test
        @DisplayName("re-notifies only somebody the previous wording did not already name")
        @SuppressWarnings("unchecked")
        void notifiesOnlyTheNewlyMentioned() {
            existing.setMentionedUserIds(List.of(42L));
            when(mentions.resolveProjectMembers(eq(PROJECT), any())).thenReturn(List.of(
                    new CommentMentions.MentionedUser(42L, "anil", "Anil Sharma", "anil@x.com"),
                    new CommentMentions.MentionedUser(77L, "meera", "Meera Rao", "meera@x.com")));

            service().edit(caller, TICKET_CODE, 99L, edit("<p>@anil @meera please look</p>"));

            ArgumentCaptor<List<CommentMentions.MentionedUser>> told =
                    ArgumentCaptor.forClass(List.class);
            verify(mentionNotifier).mentioned(any(), eq(99L), eq(AUTHOR), any(), told.capture());
            assertThat(told.getValue()).extracting(CommentMentions.MentionedUser::id)
                    .containsExactly(77L);
        }

        @Test
        @DisplayName("nobody is notified when an edit adds no new name")
        void doesNotNotifyWhenNothingIsNew() {
            existing.setMentionedUserIds(List.of(42L));
            when(mentions.resolveProjectMembers(eq(PROJECT), any())).thenReturn(List.of(
                    new CommentMentions.MentionedUser(42L, "anil", "Anil Sharma", "anil@x.com")));

            service().edit(caller, TICKET_CODE, 99L, edit("<p>@anil please look again</p>"));

            verifyNoInteractions(mentionNotifier);
        }

        @Test
        @DisplayName("a comment on another ticket is 404 — the id alone reaches nothing")
        void anotherTicketsCommentIs404() {
            when(rows.find(TICKET, 500L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().edit(caller, TICKET_CODE, 500L, edit("<p>x</p>")))
                    .isInstanceOf(CommentNotFoundException.class);
        }

        @Test
        @DisplayName("an out-of-scope ticket is 404 before the comment is even looked up")
        void outOfScopeIs404First() {
            when(tickets.requireByCode(any(), anyString())).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service().edit(caller, TICKET_CODE, 99L, edit("<p>x</p>")))
                    .isInstanceOf(TicketNotFoundException.class);
            verifyNoInteractions(rows);
        }
    }

    /**
     * C-033 · §4B.5's "deletion leaves a tombstone".
     *
     * <p>Note what is <b>not</b> tested here, because it does not exist: a
     * window. C-028's attachment delete has a silent branch inside fifteen
     * minutes; §4B.5 attaches its five minutes to editing and says of deletion
     * only that it leaves a mark.
     */
    @Nested
    @DisplayName("C-033 · deletion")
    class Deletion {

        private TicketComment existing;

        @BeforeEach
        void anExistingComment() {
            existing = new TicketComment();
            existing.setId(99L);
            existing.setTicketId(TICKET);
            existing.setAuthorId(AUTHOR);
            existing.setBodyHtml("<p>The client said the invoice is wrong.</p>");
            existing.setBodyText("The client said the invoice is wrong.");
            existing.setInternal(true);
            ReflectionTestUtils.setField(existing, "createdAt", POSTED_AT);
            when(rows.find(TICKET, 99L)).thenReturn(Optional.of(existing));
        }

        private Authentication as(long id, String role) {
            return new TestingAuthenticationToken(
                    new DevPrincipal(id, "u" + id, "User " + id, role, List.of(), List.of()),
                    "n/a", "ticket.update_progress");
        }

        @Test
        @DisplayName("the row survives, stamped with who and when")
        void tombstonesRatherThanDeletes() {
            service().delete(caller, TICKET_CODE, 99L);

            assertThat(existing.isDeleted()).isTrue();
            assertThat(existing.getDeletedBy()).isEqualTo(AUTHOR);
            assertThat(existing.getDeletedAt()).isEqualTo(clock.instant());
        }

        /**
         * <b>Guard</b>, and the trap this task set out to close. A comment edited
         * before it was deleted keeps its first wording in {@code original_body},
         * so clearing only {@code body_html} would serve exactly the text the
         * author was taking back — through the one field designed to preserve
         * text. Dropping the {@code setOriginalBody(null)} line turns this red.
         */
        @Test
        @DisplayName("both copies of the text go, the preserved original included")
        void clearsTheOriginalAsWellAsTheBody() {
            existing.setOriginalBody("<p>Acme are refusing to pay, escalate to legal.</p>");

            service().delete(caller, TICKET_CODE, 99L);

            assertThat(existing.getBodyHtml()).isEmpty();
            assertThat(existing.getBodyText()).isEmpty();
            assertThat(existing.getOriginalBody()).isNull();
        }

        @Test
        @DisplayName("a PM and an Admin may remove somebody else's")
        void supervisoryRemovalIsAllowed() {
            for (String role : List.of("PM", "ADMIN")) {
                existing.setDeleted(false);
                service().delete(as(500L, role), TICKET_CODE, 99L);
                assertThat(existing.isDeleted()).as("%s may remove a comment", role).isTrue();
            }
        }

        @Test
        @DisplayName("a Developer may not remove a colleague's, and gets 403 rather than 404")
        void othersAreRefused() {
            assertThatThrownBy(() -> service().delete(as(500L, "DEVELOPER"), TICKET_CODE, 99L))
                    .isInstanceOf(CommentDeletionNotPermittedException.class);

            assertThat(existing.isDeleted()).isFalse();
        }

        /**
         * No window, unlike an attachment: an author removing their own comment
         * ten seconds after posting still leaves a mark. A comment is a statement
         * colleagues have already read, not a mis-pasted screenshot.
         */
        @Test
        @DisplayName("even the author's own removal seconds after posting leaves a tombstone")
        void everyRemovalIsRecorded() {
            clock = Clock.fixed(POSTED_AT.plusSeconds(10), ZoneOffset.UTC);

            service().delete(caller, TICKET_CODE, 99L);

            assertThat(existing.isDeleted()).isTrue();
            assertThat(existing.getDeletedBy()).isEqualTo(AUTHOR);
        }

        @Test
        @DisplayName("decrements the materialised counter")
        void movesTheCounter() {
            service().delete(caller, TICKET_CODE, 99L);

            assertThat(ticket.getCommentCount()).isEqualTo(6);
        }

        /**
         * The counter was a hard zero for every ticket created before C-029, so
         * a comment posted then and tidied up now would otherwise render
         * "-1 comments" on the list.
         */
        @Test
        @DisplayName("the counter floors at zero rather than going negative")
        void neverGoesNegative() {
            ticket.setCommentCount(0);

            service().delete(caller, TICKET_CODE, 99L);

            assertThat(ticket.getCommentCount()).isZero();
        }

        @Test
        @DisplayName("deleting a tombstone is a no-op, not a refusal")
        void isIdempotent() {
            existing.setDeleted(true);
            existing.setDeletedBy(500L);

            service().delete(as(500L, "DEVELOPER"), TICKET_CODE, 99L);

            // Not re-stamped: a second caller must not be able to take the first
            // one's name off the record.
            assertThat(existing.getDeletedBy()).isEqualTo(500L);
            assertThat(ticket.getCommentCount()).isEqualTo(7);
        }

        @Test
        @DisplayName("an out-of-scope ticket is 404 before the comment is even looked up")
        void outOfScopeIs404First() {
            when(tickets.requireByCode(any(), anyString())).thenThrow(new TicketNotFoundException());

            assertThatThrownBy(() -> service().delete(caller, TICKET_CODE, 99L))
                    .isInstanceOf(TicketNotFoundException.class);
            verifyNoInteractions(rows);
        }

        @Test
        @DisplayName("a tombstone serves no text and no edit deadline")
        void theDtoHidesEverything() {
            existing.setOriginalBody("<p>secret</p>");
            service().delete(caller, TICKET_CODE, 99L);

            // A window is passed DELIBERATELY, though D-14 ships without one:
            // it is the only configuration in which `editableUntil` could be
            // non-null, so it is the only one where "a tombstone carries no
            // deadline" is a real assertion. Passing null here would pass on an
            // implementation that had never nulled anything.
            CommentDtos.CommentDto dto = CommentDtos.CommentDto.of(
                    existing, Map.of(), Map.of(), Duration.ofMinutes(5));

            assertThat(dto.body()).isEmpty();
            assertThat(dto.originalBody()).isNull();
            assertThat(dto.editableUntil()).isNull();
            assertThat(dto.isDeleted()).isTrue();
            assertThat(dto.deletedAt()).isEqualTo(clock.instant());
        }
    }
}
