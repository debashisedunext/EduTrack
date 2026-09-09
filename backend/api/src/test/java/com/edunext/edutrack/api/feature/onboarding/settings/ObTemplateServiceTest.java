package com.edunext.edutrack.api.feature.onboarding.settings;

import com.edunext.edutrack.domain.onboarding.outbox.ObChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-113 · the two derived fields, the rule that cannot be switched off, and the
 * tag that must never reach a client's inbox.
 */
class ObTemplateServiceTest {

    private static final long ID = 12L;
    private static final long ACTOR = 7L;

    private ObTemplateRepository repository;
    private ObTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(ObTemplateRepository.class);
        service = new ObTemplateService(repository);
    }

    private static ObTemplateRepository.Stored stored(ObTemplateDtos.Category category,
                                                      ObChannel channel) {
        return new ObTemplateRepository.Stored(ID, "TAT_BREACHED", category, channel,
                List.of("STEP_OWNER"), channel == ObChannel.EMAIL ? "Overdue" : null,
                "<p>{{step_name}} is overdue.</p>", true);
    }

    private void given(ObTemplateRepository.Stored row) {
        when(repository.findById(ID)).thenReturn(Optional.of(row));
    }

    private static ObTemplateDtos.UpdateRequest body(String bodyTemplate) {
        return new ObTemplateDtos.UpdateRequest(null, bodyTemplate, null, null);
    }

    @Nested
    @DisplayName("isMandatory is derived from the category, never stored")
    class Mandatory {

        @Test
        @DisplayName("an ESCALATION email is mandatory")
        void escalationEmailIsMandatory() {
            given(stored(ObTemplateDtos.Category.ESCALATION, ObChannel.EMAIL));

            assertThat(service.get(ID).isMandatory()).isTrue();
        }

        @Test
        @DisplayName("a SIGNOFF email is mandatory")
        void signoffEmailIsMandatory() {
            given(stored(ObTemplateDtos.Category.SIGNOFF, ObChannel.EMAIL));

            assertThat(service.get(ID).isMandatory()).isTrue();
        }

        @Test
        @DisplayName("a SERVICE email is not")
        void serviceEmailIsNot() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));

            assertThat(service.get(ID).isMandatory()).isFalse();
        }

        @Test
        @DisplayName("the rule is about email — an ESCALATION in-app entry is silenceable")
        void inAppEscalationIsNot() {
            // The rule the contract states is `channel == EMAIL` and the
            // category. A bell entry is not the notification anybody is
            // legally obliged to receive.
            given(stored(ObTemplateDtos.Category.ESCALATION, ObChannel.IN_APP));

            assertThat(service.get(ID).isMandatory()).isFalse();
        }

        @Test
        @DisplayName("switching off a mandatory one is refused, and nothing is written")
        void refusesSwitchingOffAMandatoryOne() {
            given(stored(ObTemplateDtos.Category.ESCALATION, ObChannel.EMAIL));
            ObTemplateDtos.UpdateRequest off =
                    new ObTemplateDtos.UpdateRequest(null, null, null, false);

            assertThatExceptionOfType(MandatoryTemplateException.class)
                    .isThrownBy(() -> service.update(ID, off, ACTOR));
            verify(repository, never()).update(anyLong(), any(), any());
        }

        @Test
        @DisplayName("switching a mandatory one back ON is fine — the rule is one-directional")
        void allowsSwitchingOnAMandatoryOne() {
            given(stored(ObTemplateDtos.Category.ESCALATION, ObChannel.EMAIL));
            ObTemplateDtos.UpdateRequest on =
                    new ObTemplateDtos.UpdateRequest(null, null, null, true);

            service.update(ID, on, ACTOR);

            verify(repository).update(anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("isDeliverable is about the deployment, not the row")
    class Deliverable {

        @Test
        @DisplayName("EMAIL is deliverable")
        void emailIsDeliverable() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));

            assertThat(service.get(ID).isDeliverable()).isTrue();
        }

        @Test
        @DisplayName("WHATSAPP is not — phase 2 has no adapter, and the screen has to say so")
        void whatsappIsNotDeliverable() {
            // PHASE-2-BUILD-PLAN §6.1 defers WhatsApp entirely. B-110's
            // dispatcher leaves such rows PENDING by design; this is that fact
            // made visible rather than a template that looks configured.
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.WHATSAPP));

            assertThat(service.get(ID).isDeliverable()).isFalse();
        }
    }

    @Nested
    @DisplayName("merge tags")
    class MergeTags {

        @Test
        @DisplayName("a body using a known tag saves")
        void acceptsAKnownTag() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));

            service.update(ID, body("<p>Hello {{client_name}}.</p>"), ACTOR);

            verify(repository).update(anyLong(), any(), any());
        }

        @Test
        @DisplayName("whitespace inside the braces is fine — that is formatting, not correctness")
        void toleratesWhitespace() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));

            service.update(ID, body("<p>Hello {{ client_name }}.</p>"), ACTOR);

            verify(repository).update(anyLong(), any(), any());
        }

        @Test
        @DisplayName("a typo is refused before it can render as literal braces in an inbox")
        void refusesAnUnknownTag() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));

            assertThatExceptionOfType(UnknownMergeTagException.class)
                    .isThrownBy(() -> service.update(ID, body("<p>Hello {{clietn_name}}.</p>"), ACTOR))
                    .satisfies(e -> assertThat(e.tags()).containsExactly("clietn_name"));
            verify(repository, never()).update(anyLong(), any(), any());
        }

        @Test
        @DisplayName("every unknown tag is named, not just the first")
        void namesEveryUnknownTag() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));

            // An editor fixing one and resubmitting to discover the next is the
            // failure C-106's completion gate exists to avoid, applied here.
            assertThatExceptionOfType(UnknownMergeTagException.class)
                    .isThrownBy(() -> service.update(ID,
                            body("{{nope}} and {{alsonope}}"), ACTOR))
                    .satisfies(e -> assertThat(e.tags()).containsExactlyInAnyOrder("nope", "alsonope"));
        }

        @Test
        @DisplayName("the subject is checked too — it reaches the same inbox")
        void checksTheSubject() {
            given(stored(ObTemplateDtos.Category.SERVICE, ObChannel.EMAIL));
            ObTemplateDtos.UpdateRequest request =
                    new ObTemplateDtos.UpdateRequest("Overdue: {{stp_name}}", null, null, null);

            assertThatExceptionOfType(UnknownMergeTagException.class)
                    .isThrownBy(() -> service.update(ID, request, ACTOR));
        }
    }

    @Nested
    @DisplayName("vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("offers every event the catalogue declares, not just the ones with rows")
        void servesEveryEvent() {
            when(repository.list(null, null)).thenReturn(List.of());

            ObTemplateDtos.Vocabulary vocabulary = service.vocabulary();

            // Read off ObNotificationEvent rather than off the table: a
            // vocabulary built from rows would silently stop offering an event
            // whose template somebody deleted.
            assertThat(vocabulary.events()).extracting(ObTemplateDtos.EventOption::code)
                    .contains("TAT_BREACHED", "SIGNOFF_REQUESTED", "CLIENT_LOGIN_CREATED");
        }

        @Test
        @DisplayName("offers the tags the catalogue declares, braced and ready to paste")
        void servesTheCatalogueTags() {
            when(repository.list(null, null)).thenReturn(List.of());

            // Served braced because the MSW mock has served that shape since
            // A-118, and a palette that inserts what it is given must be given
            // something insertable. Validation still works on the bare name.
            assertThat(service.vocabulary().mergeTags())
                    .contains("{{client_name}}", "{{action_url}}", "{{otp_code}}");
        }

        @Test
        @DisplayName("mandatoryMail lets OB-12 lock the toggle before the click, not after the 409")
        void reportsMandatoryMail() {
            when(repository.list(null, null)).thenReturn(List.of(
                    new ObTemplateRepository.Stored(1L, "TAT_BREACHED",
                            ObTemplateDtos.Category.ESCALATION, ObChannel.EMAIL,
                            List.of("STEP_OWNER"), "s", "b", true)));

            ObTemplateDtos.EventOption breach = service.vocabulary().events().stream()
                    .filter(e -> e.code().equals("TAT_BREACHED"))
                    .findFirst()
                    .orElseThrow();

            assertThat(breach.mandatoryMail()).isTrue();
        }

        @Test
        @DisplayName("all three channels are offered, including the one nothing sends")
        void offersEveryChannel() {
            when(repository.list(null, null)).thenReturn(List.of());

            // A template can be authored before it can be sent, which is what
            // isDeliverable exists to say. Hiding WHATSAPP here would make that
            // field unreachable.
            assertThat(service.vocabulary().channels())
                    .containsExactlyInAnyOrder(ObChannel.values());
        }
    }
}
