package com.edunext.edutrack.api.feature.masters.notificationtemplates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-022 · what the controller decides before delegating — the {@code If-Match}
 * precondition, the {@code ETag} it is checked against, and the 404 that comes
 * before both.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code RoleControllerTest}, {@code TaskTypeControllerTest} and
 * {@code PriorityControllerTest} do: everything asserted here is method-level,
 * and {@code MasterRoutesTest} covers the one thing plain construction cannot
 * see, which is where the class is mounted.
 */
class NotificationTemplateControllerTest {

    private NotificationTemplateService service;
    private NotificationTemplateController controller;

    @BeforeEach
    void setUp() {
        service = mock(NotificationTemplateService.class);
        controller = new NotificationTemplateController(service);
    }

    private static NotificationTemplateDtos.TemplateView view(String body, boolean active) {
        return new NotificationTemplateDtos.TemplateView(
                7L, "HANDOFF_RECEIVED", "ASSIGNMENT", "EMAIL", List.of("STAGE_OWNER"),
                "Handed to you at {{stage}}", body, active, true);
    }

    @Nested
    @DisplayName("If-Match")
    class Precondition {

        /**
         * Required, not optional. Treating a missing precondition as "no
         * conflict" would mean the guard protects only the clients that already
         * opted in, which is the set that needed it least.
         */
        @Test
        @DisplayName("a PATCH without one is 428, and nothing is written")
        void missingIfMatchIs428() {
            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", true)));

            assertThatThrownBy(() -> controller.update(7L, null, patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                            assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));

            verify(service, never()).update(anyLong(), any());
        }

        @Test
        @DisplayName("a blank one counts as absent")
        void blankIfMatchIs428() {
            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", true)));

            assertThatThrownBy(() -> controller.update(7L, "  ", patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                            assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED));
        }

        @Test
        @DisplayName("a stale one is 412")
        void staleIfMatchIs412() {
            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", true)));

            assertThatThrownBy(() -> controller.update(7L, "\"deadbeef\"", patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                            assertThat(e.getStatusCode())
                                    .isEqualTo(HttpStatus.PRECONDITION_FAILED));

            verify(service, never()).update(anyLong(), any());
        }

        /**
         * The 404 comes first. Answering 428 for a template that does not exist
         * would send the caller to fetch a tag from a URL that will 404 as well.
         */
        @Test
        @DisplayName("a missing template is 404 even with no If-Match")
        void missingTemplateIs404BeforeThePrecondition() {
            when(service.find(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.update(404L, null, patch()))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("the tag from the read is accepted, quoted or weak")
        void currentTagIsAccepted() {
            NotificationTemplateDtos.TemplateView current = view("<p>a</p>", true);
            when(service.find(7L)).thenReturn(Optional.of(current));
            when(service.update(anyLong(), any())).thenReturn(Optional.of(current));

            String tag = controller.template(7L).getHeaders().getETag();
            assertThat(tag).isNotNull();

            assertThat(controller.update(7L, tag, patch()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(controller.update(7L, "W/" + tag, patch()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        /** RFC 9110: {@code *} matches anything. */
        @Test
        @DisplayName("* is accepted")
        void wildcardIsAccepted() {
            NotificationTemplateDtos.TemplateView current = view("<p>a</p>", true);
            when(service.find(7L)).thenReturn(Optional.of(current));
            when(service.update(anyLong(), any())).thenReturn(Optional.of(current));

            assertThat(controller.update(7L, "*", patch()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("the ETag")
    class Etag {

        /**
         * Derived from the content, not from {@code updated_at} — a timestamp tag
         * moves when a save rewrites identical values and fails an edit that
         * conflicts with nothing.
         */
        @Test
        @DisplayName("changes when the body changes")
        void tagFollowsTheContent() {
            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", true)));
            String before = controller.template(7L).getHeaders().getETag();

            when(service.find(7L)).thenReturn(Optional.of(view("<p>reworded</p>", true)));
            String after = controller.template(7L).getHeaders().getETag();

            assertThat(after).isNotEqualTo(before);
        }

        @Test
        @DisplayName("changes when the template is switched off")
        void tagFollowsTheToggle() {
            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", true)));
            String on = controller.template(7L).getHeaders().getETag();

            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", false)));
            assertThat(controller.template(7L).getHeaders().getETag()).isNotEqualTo(on);
        }

        @Test
        @DisplayName("is stable across two reads of the same row")
        void tagIsStable() {
            when(service.find(7L)).thenReturn(Optional.of(view("<p>a</p>", true)));

            assertThat(controller.template(7L).getHeaders().getETag())
                    .isEqualTo(controller.template(7L).getHeaders().getETag());
        }
    }

    @Test
    @DisplayName("a create answers 201 with a tag the PATCH can use")
    void createAnswers201WithATag() {
        NotificationTemplateDtos.TemplateView created = view("<p>a</p>", true);
        when(service.create(any())).thenReturn(created);

        ResponseEntity<NotificationTemplateDtos.TemplateResponse> response =
                controller.create(new NotificationTemplateDtos.TemplateWrite(
                        "HANDOFF_RECEIVED", "PUSH", List.of("STAGE_OWNER"),
                        null, "body", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getETag()).isNotNull();
    }

    @Test
    @DisplayName("a read of a template that is not there is 404, never 403")
    void missingTemplateReadIs404() {
        when(service.find(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.template(404L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private static NotificationTemplateDtos.TemplatePatch patch() {
        return new NotificationTemplateDtos.TemplatePatch(
                null, null, null, null, "<p>reworded</p>", null);
    }
}
