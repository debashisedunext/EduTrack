package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import com.edunext.edutrack.domain.notifications.NotificationChannel;
import com.edunext.edutrack.domain.notifications.NotificationEvent;
import com.edunext.edutrack.domain.notifications.NotificationTemplate;
import com.edunext.edutrack.domain.notifications.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-022 · the decisions S-15 makes that the schema does not.
 *
 * <p>Against a mock repository, so each rule can be put in the one state that
 * exercises it. {@link NotificationTemplateMasterIT} proves the half a mock
 * cannot — that {@code V20260815_1100}'s seed is shaped the way this screen
 * assumes, and that the column really does hold what the enum expects.
 */
class NotificationTemplateServiceTest {

    private NotificationTemplateRepository templates;
    private NotificationTemplateService service;

    @BeforeEach
    void setUp() {
        templates = mock(NotificationTemplateRepository.class);
        service = new NotificationTemplateService(templates);

        // The real save assigns the identity column. Returning the argument
        // untouched would leave `create` mapping a null id into a primitive.
        when(templates.save(any(NotificationTemplate.class))).thenAnswer(i -> {
            NotificationTemplate saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(99L);
            }
            return saved;
        });
        when(templates.existsByEventCodeAndChannel(anyString(), anyString())).thenReturn(false);
    }

    private static NotificationTemplate stored(String eventCode, NotificationChannel channel,
                                               String recipients, boolean active) {
        NotificationTemplate template = new NotificationTemplate();
        template.setId(1L);
        template.setEventCode(eventCode);
        template.setChannel(channel.name());
        template.setRecipients(recipients);
        template.setSubjectTemplate(channel == NotificationChannel.EMAIL ? "Something happened" : null);
        template.setBodyTemplate("<p>{{ticket_id}} — {{actor}}</p>");
        template.setActive(active);
        return template;
    }

    private static NotificationTemplateDtos.TemplateWrite write(
            String eventCode, NotificationChannel channel, String subject, String body) {

        return new NotificationTemplateDtos.TemplateWrite(
                eventCode, channel.name(), List.of("ASSIGNEE"), subject, body, null);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the mandatory-mail rule")
    class MandatoryMail {

        /**
         * The rule this screen exists in order not to break.
         *
         * <p>"Per-event on/off" taken unqualified hands an Admin one click that
         * silences, org-wide, the mail D-036 spent a whole method making
         * unmutable per-user.
         */
        @Test
        @DisplayName("an assignment mail cannot be switched off")
        void assignmentMailCannotBeSwitchedOff() {
            NotificationTemplate template =
                    stored("TICKET_ASSIGNED", NotificationChannel.EMAIL, "ASSIGNEE", true);
            when(templates.findById(1L)).thenReturn(Optional.of(template));

            assertThatThrownBy(() -> service.update(1L, patch(null, null, null, null, false)))
                    .isInstanceOf(NotificationTemplateService.MandatoryTemplateException.class)
                    .hasMessageContaining("cannot be switched off");

            assertThat(template.isActive()).isTrue();
        }

        @Test
        @DisplayName("an escalation mail cannot be switched off either")
        void escalationMailCannotBeSwitchedOff() {
            when(templates.findById(1L)).thenReturn(Optional.of(
                    stored("SLA_BREACHED", NotificationChannel.EMAIL, "ASSIGNEE", true)));

            assertThatThrownBy(() -> service.update(1L, patch(null, null, null, null, false)))
                    .isInstanceOf(NotificationTemplateService.MandatoryTemplateException.class);
        }

        /**
         * D-055 found this category missing from the prose sentence §4B.6 leads
         * with, and present in the table beneath it. The rule is stated over the
         * category precisely so the two cannot drift apart again.
         */
        @Test
        @DisplayName("a status-request mail cannot be switched off — D-055's category")
        void statusRequestMailCannotBeSwitchedOff() {
            when(templates.findById(1L)).thenReturn(Optional.of(
                    stored("STATUS_REQUESTED", NotificationChannel.EMAIL, "ASSIGNEE", true)));

            assertThatThrownBy(() -> service.update(1L, patch(null, null, null, null, false)))
                    .isInstanceOf(NotificationTemplateService.MandatoryTemplateException.class);
        }

        /**
         * §7.7 gives the guarantee to mail, not to a toast that only reaches
         * somebody already logged in. Locking the in-app channel too would take
         * away a real preference to protect a channel that was never the
         * promise.
         */
        @Test
        @DisplayName("the in-app template for the same event CAN be switched off")
        void inAppTemplateForAMandatoryEventCanBeSwitchedOff() {
            NotificationTemplate template =
                    stored("TICKET_ASSIGNED", NotificationChannel.IN_APP, "ASSIGNEE", true);
            when(templates.findById(1L)).thenReturn(Optional.of(template));

            assertThatCode(() -> service.update(1L, patch(null, null, null, null, false)))
                    .doesNotThrowAnyException();
            assertThat(template.isActive()).isFalse();
        }

        @Test
        @DisplayName("an optional mail can be switched off")
        void optionalMailCanBeSwitchedOff() {
            NotificationTemplate template =
                    stored("COMMENT_ADDED", NotificationChannel.EMAIL, "ASSIGNEE", true);
            when(templates.findById(1L)).thenReturn(Optional.of(template));

            assertThatCode(() -> service.update(1L, patch(null, null, null, null, false)))
                    .doesNotThrowAnyException();
            assertThat(template.isActive()).isFalse();
        }

        /**
         * Refused before the insert rather than after it. Creating one already
         * switched off would otherwise be a way in through the side door, and it
         * would depend on a rollback rather than on never having written.
         */
        @Test
        @DisplayName("a mandatory mail cannot be created already switched off")
        void mandatoryMailCannotBeCreatedInactive() {
            assertThatThrownBy(() -> service.create(new NotificationTemplateDtos.TemplateWrite(
                    "HANDOFF_RECEIVED", "EMAIL", List.of("STAGE_OWNER"),
                    "Handed to you", "<p>body</p>", false)))
                    .isInstanceOf(NotificationTemplateService.MandatoryTemplateException.class);

            verify(templates, never()).save(any());
        }

        /**
         * The deliberate hole, asserted so that removing it later is a decision
         * rather than an accident. §11's "To" column is a default, not a law,
         * and an org routing assignment mail through a shared desk address is
         * doing something legitimate.
         */
        @Test
        @DisplayName("the recipient list of a mandatory mail is editable — deliberately")
        void mandatoryMailRecipientsCanBeChanged() {
            NotificationTemplate template =
                    stored("TICKET_ASSIGNED", NotificationChannel.EMAIL, "ASSIGNEE", true);
            when(templates.findById(1L)).thenReturn(Optional.of(template));

            assertThatCode(() -> service.update(
                    1L, patch(null, null, List.of("SUPPORT_DESK"), null, null)))
                    .doesNotThrowAnyException();
            assertThat(template.getRecipients()).isEqualTo("SUPPORT_DESK");
        }

        @Test
        @DisplayName("isMandatory is derived onto the view, never read from a column")
        void isMandatoryIsDerived() {
            when(templates.findById(1L)).thenReturn(Optional.of(
                    stored("TICKET_ASSIGNED", NotificationChannel.EMAIL, "ASSIGNEE", true)));
            when(templates.findById(2L)).thenReturn(Optional.of(
                    stored("TICKET_ASSIGNED", NotificationChannel.IN_APP, "ASSIGNEE", true)));

            assertThat(service.find(1L)).get()
                    .extracting(NotificationTemplateDtos.TemplateView::isMandatory).isEqualTo(true);
            assertThat(service.find(2L)).get()
                    .extracting(NotificationTemplateDtos.TemplateView::isMandatory).isEqualTo(false);
        }
    }

    @Nested
    @DisplayName("merge tags")
    class MergeTags {

        @Test
        @DisplayName("a misspelled tag is refused, and the message names it")
        void unknownTagIsRefused() {
            assertThatThrownBy(() -> service.create(write("COMMENT_ADDED", NotificationChannel.EMAIL,
                    "A comment", "<p>{{ticketId}} was updated</p>")))
                    .isInstanceOf(NotificationTemplateService.UnknownMergeTagException.class)
                    .hasMessageContaining("{{ticketId}}");
        }

        @Test
        @DisplayName("a tag in the subject is caught too")
        void unknownTagInSubjectIsRefused() {
            assertThatThrownBy(() -> service.create(write("COMMENT_ADDED", NotificationChannel.EMAIL,
                    "Update on {{ticket}}", "<p>fine</p>")))
                    .isInstanceOf(NotificationTemplateService.UnknownMergeTagException.class)
                    .hasMessageContaining("{{ticket}}");
        }

        /** One mistake reported once, so a second one is not buried under it. */
        @Test
        @DisplayName("the same misspelling four times is reported once")
        void repeatedMisspellingIsReportedOnce() {
            assertThatThrownBy(() -> service.create(write("COMMENT_ADDED", NotificationChannel.EMAIL,
                    "s", "{{nope}} {{nope}} {{nope}} {{nope}}")))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.UnknownMergeTagException.class,
                            e -> assertThat(e.unknownTags()).containsExactly("nope"));
        }

        /** `{{ ticket_id }}` is what a paste from a document produces. */
        @Test
        @DisplayName("whitespace inside the braces is tolerated")
        void whitespaceInsideBracesIsFine() {
            assertThatCode(() -> service.create(write("COMMENT_ADDED", NotificationChannel.EMAIL,
                    "s", "<p>{{ ticket_id }}</p>"))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a template with no tags at all is fine")
        void staticBodyIsFine() {
            assertThatCode(() -> service.create(write("CHAIN_VERIFICATION_FAILED",
                    NotificationChannel.EMAIL, "Chain failed", "<p>Investigate.</p>")))
                    .doesNotThrowAnyException();
        }

        /** The refusal carries the catalogue, so the screen can offer it. */
        @Test
        @DisplayName("the refusal carries the known tags")
        void refusalCarriesTheCatalogue() {
            assertThatThrownBy(() -> service.create(write("COMMENT_ADDED", NotificationChannel.EMAIL,
                    "s", "{{nope}}")))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.UnknownMergeTagException.class,
                            e -> assertThat(e.knownTags()).contains("ticket_id", "planned_close"));
        }
    }

    @Nested
    @DisplayName("the (event, channel) identity")
    class Identity {

        @Test
        @DisplayName("a changed eventCode is refused")
        void eventCodeIsImmutable() {
            when(templates.findById(1L)).thenReturn(Optional.of(
                    stored("COMMENT_ADDED", NotificationChannel.EMAIL, "ASSIGNEE", true)));

            assertThatThrownBy(() -> service.update(1L, patch("TICKET_CLOSED", null, null, null, null)))
                    .isInstanceOf(NotificationTemplateService.ImmutableTemplateIdentityException.class);
        }

        @Test
        @DisplayName("a changed channel is refused")
        void channelIsImmutable() {
            when(templates.findById(1L)).thenReturn(Optional.of(
                    stored("COMMENT_ADDED", NotificationChannel.EMAIL, "ASSIGNEE", true)));

            assertThatThrownBy(() -> service.update(1L, patch(null, "IN_APP", null, null, null)))
                    .isInstanceOf(NotificationTemplateService.ImmutableTemplateIdentityException.class);
        }

        /**
         * S-15 submits the whole form on every save. Any other reading of a
         * resent pair makes every edit a 409 — B-016, B-020 and B-021 all hit
         * the same thing.
         */
        @Test
        @DisplayName("resending the stored pair is a no-op, not a conflict")
        void resendingTheStoredPairIsANoOp() {
            NotificationTemplate template =
                    stored("COMMENT_ADDED", NotificationChannel.EMAIL, "ASSIGNEE", true);
            when(templates.findById(1L)).thenReturn(Optional.of(template));

            assertThatCode(() -> service.update(
                    1L, patch("COMMENT_ADDED", "EMAIL", null, "<p>reworded</p>", null)))
                    .doesNotThrowAnyException();
            assertThat(template.getBodyTemplate()).isEqualTo("<p>reworded</p>");
        }

        @Test
        @DisplayName("a second template for the same pair is refused")
        void duplicatePairIsRefused() {
            when(templates.existsByEventCodeAndChannel("COMMENT_ADDED", "EMAIL")).thenReturn(true);

            assertThatThrownBy(() -> service.create(
                    write("COMMENT_ADDED", NotificationChannel.EMAIL, "s", "<p>b</p>")))
                    .isInstanceOf(NotificationTemplateService.DuplicateTemplateException.class);

            verify(templates, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the vocabularies")
    class Vocabularies {

        @Test
        @DisplayName("an event nothing raises is refused")
        void unknownEventIsRefused() {
            assertThatThrownBy(() -> service.create(
                    write("TICKET_SET_ON_FIRE", NotificationChannel.EMAIL, "s", "<p>b</p>")))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.TemplateValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("eventCode"));
        }

        /**
         * A-007's column comment says {@code POPUP|BELL|EMAIL} and is superseded
         * — the bell is not a channel. This asserts the vocabulary that actually
         * runs, so nobody restores the old one from the comment.
         */
        @Test
        @DisplayName("BELL is not a channel")
        void bellIsNotAChannel() {
            assertThatThrownBy(() -> service.create(new NotificationTemplateDtos.TemplateWrite(
                    "COMMENT_ADDED", "BELL", List.of("ASSIGNEE"), null, "<p>b</p>", null)))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.TemplateValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("channel"));
        }

        @Test
        @DisplayName("an unresolvable recipient is refused")
        void unknownRecipientIsRefused() {
            assertThatThrownBy(() -> service.create(new NotificationTemplateDtos.TemplateWrite(
                    "COMMENT_ADDED", "EMAIL", List.of("ASSIGNEE", "EVERYONE_NEARBY"),
                    "s", "<p>b</p>", null)))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.TemplateValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("recipients"));
        }

        @Test
        @DisplayName("an empty recipient list is refused")
        void emptyRecipientListIsRefused() {
            assertThatThrownBy(() -> service.create(new NotificationTemplateDtos.TemplateWrite(
                    "COMMENT_ADDED", "EMAIL", List.of(), "s", "<p>b</p>", null)))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.TemplateValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("recipients"));
        }

        @Test
        @DisplayName("duplicate recipients collapse to one")
        void duplicateRecipientsCollapse() {
            NotificationTemplateDtos.TemplateView created =
                    service.create(new NotificationTemplateDtos.TemplateWrite(
                            "COMMENT_ADDED", "EMAIL", List.of("ASSIGNEE", "ASSIGNEE"),
                            "s", "<p>b</p>", null));

            assertThat(created.recipients()).containsExactly("ASSIGNEE");
        }

        @Test
        @DisplayName("the catalogue names every event, channel, recipient and tag")
        void vocabularyIsComplete() {
            NotificationTemplateDtos.VocabularyView vocabulary = service.vocabulary();

            assertThat(vocabulary.events()).hasSize(NotificationEvent.values().length);
            assertThat(vocabulary.channels()).containsExactly("IN_APP", "EMAIL", "PUSH");
            assertThat(vocabulary.recipients()).contains("ASSIGNEE", "CLIENT_CONTACT", "ADMIN");
            // Blueprint §4B.6's five, spelled the way it spells them.
            assertThat(vocabulary.mergeTags())
                    .contains("ticket_id", "assignee", "stage", "client", "planned_close");
        }

        /** The screen locks the toggle from this flag, so it has to be right. */
        @Test
        @DisplayName("the catalogue marks which events have unmutable mail")
        void vocabularyMarksMandatoryEvents() {
            assertThat(service.vocabulary().events())
                    .filteredOn(NotificationTemplateDtos.EventOption::mandatoryMail)
                    .extracting(NotificationTemplateDtos.EventOption::code)
                    .contains("TICKET_ASSIGNED", "HANDOFF_RECEIVED", "SLA_BREACHED",
                            "STATUS_REQUESTED")
                    .doesNotContain("COMMENT_ADDED", "DAILY_DIGEST");
        }
    }

    @Nested
    @DisplayName("subjects")
    class Subjects {

        @Test
        @DisplayName("an email template needs a subject")
        void emailNeedsASubject() {
            assertThatThrownBy(() -> service.create(
                    write("COMMENT_ADDED", NotificationChannel.EMAIL, null, "<p>b</p>")))
                    .isInstanceOfSatisfying(
                            NotificationTemplateService.TemplateValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("subjectTemplate"));
        }

        @Test
        @DisplayName("a blank subject on an email is the same as none")
        void blankSubjectIsTreatedAsAbsent() {
            assertThatThrownBy(() -> service.create(
                    write("COMMENT_ADDED", NotificationChannel.EMAIL, "   ", "<p>b</p>")))
                    .isInstanceOf(NotificationTemplateService.TemplateValidationException.class);
        }

        @Test
        @DisplayName("an in-app template does not need one")
        void inAppDoesNotNeedASubject() {
            NotificationTemplateDtos.TemplateView created = service.create(
                    write("COMMENT_ADDED", NotificationChannel.IN_APP, null, "somebody commented"));

            assertThat(created.subjectTemplate()).isNull();
        }

        /** A push has a title as well as a body — refusing the field would make
         *  the master unable to express something the channel has. */
        @Test
        @DisplayName("a push template may carry one")
        void pushMayCarryASubject() {
            NotificationTemplateDtos.TemplateView created = service.create(
                    write("COMMENT_ADDED", NotificationChannel.PUSH, "New comment", "body"));

            assertThat(created.subjectTemplate()).isEqualTo("New comment");
        }

        /**
         * "Absent" and "explicitly null" have to mean different things, which is
         * why the patch type is a POJO. A record would collapse both into
         * "clear it" and strip the subject off a mail on a patch that only
         * touched the body.
         */
        @Test
        @DisplayName("an omitted subject is kept, an explicit null clears it")
        void omittedAndNullSubjectDiffer() {
            NotificationTemplate template =
                    stored("COMMENT_ADDED", NotificationChannel.PUSH, "ASSIGNEE", true);
            template.setSubjectTemplate("A title");
            when(templates.findById(1L)).thenReturn(Optional.of(template));

            service.update(1L, patch(null, null, null, "reworded", null));
            assertThat(template.getSubjectTemplate()).isEqualTo("A title");

            NotificationTemplateDtos.TemplatePatch clearing =
                    new NotificationTemplateDtos.TemplatePatch(
                            null, null, null, Optional.empty(), null, null);
            service.update(1L, clearing);
            assertThat(template.getSubjectTemplate()).isNull();
        }
    }

    /**
     * Nothing is written when a rule refuses, even though the transaction would
     * roll the row back — the entity in the persistence context would not.
     */
    @Test
    @DisplayName("a refused patch leaves the row untouched rather than half-applied")
    void refusedPatchLeavesTheRowUntouched() {
        NotificationTemplate template =
                stored("TICKET_ASSIGNED", NotificationChannel.EMAIL, "ASSIGNEE", true);
        when(templates.findById(1L)).thenReturn(Optional.of(template));

        assertThatThrownBy(() -> service.update(
                1L, patch(null, null, List.of("ADMIN"), "<p>{{ticket_id}} new</p>", false)))
                .isInstanceOf(NotificationTemplateService.MandatoryTemplateException.class);

        assertThat(template.getRecipients()).isEqualTo("ASSIGNEE");
        assertThat(template.getBodyTemplate()).isEqualTo("<p>{{ticket_id}} — {{actor}}</p>");
        assertThat(template.isActive()).isTrue();
    }

    @Test
    @DisplayName("a template that is not there is empty, not an exception")
    void missingTemplateIsEmpty() {
        when(templates.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.update(404L, patch(null, null, null, "x", null))).isEmpty();
        assertThat(service.find(404L)).isEmpty();
    }

    /**
     * Tolerant on read. A row whose event this build no longer knows still has
     * to render in the grid, carrying its stored code, so an Admin can see what
     * it says and switch it off.
     */
    @Test
    @DisplayName("a row with an event this build does not know still reads")
    void unknownStoredEventStillReads() {
        NotificationTemplate template =
                stored("SOMETHING_A_LATER_DEPLOY_ADDED", NotificationChannel.EMAIL, "ASSIGNEE", true);
        when(templates.findById(1L)).thenReturn(Optional.of(template));

        assertThat(service.find(1L)).get().satisfies(view -> {
            assertThat(view.eventCode()).isEqualTo("SOMETHING_A_LATER_DEPLOY_ADDED");
            assertThat(view.category()).isEqualTo("OTHER");
            assertThat(view.isMandatory()).isFalse();
        });
    }

    private static NotificationTemplateDtos.TemplatePatch patch(
            String eventCode, String channel, List<String> recipients,
            String body, Boolean isActive) {

        return new NotificationTemplateDtos.TemplatePatch(
                eventCode, channel, recipients, null, body, isActive);
    }
}
