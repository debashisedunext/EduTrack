package com.edunext.edutrack.api.feature.masters.projects;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-019 · what the controller decides before delegating — which here is almost
 * entirely the precondition. The {@code PUT} is a wholesale replace, so a stale
 * tab's save does not merge with another administrator's, it erases it.
 *
 * <p>Plain construction, as every other controller test in this feature does.
 * {@code MasterRoutesTest} covers the one thing plain construction cannot see —
 * where the class is mounted.
 */
class ProjectSettingsControllerTest {

    private static final long PROJECT = 7L;

    private ProjectSettingsService service;
    private ProjectSettingsController controller;

    @BeforeEach
    void setUp() {
        service = mock(ProjectSettingsService.class);
        controller = new ProjectSettingsController(service);
        when(service.settings(PROJECT)).thenReturn(settings(false));
    }

    // ------------------------------------------------------------------
    // the read
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the settings read")
    class Read {

        @Test
        @DisplayName("is wrapped in { data } with no meta — data is an object, not a collection")
        void isWrapped() throws Exception {
            String json = Jackson2ObjectMapperBuilder.json().build()
                    .writeValueAsString(controller.settings(PROJECT).getBody());

            assertThat(json).contains("\"data\"").doesNotContain("\"meta\"");
        }

        @Test
        @DisplayName("carries an ETag, because the PUT requires one and nothing else emits it")
        void carriesAnEtag() {
            assertThat(controller.settings(PROJECT).getHeaders().getETag()).isNotBlank();
        }

        @Test
        @DisplayName("the tag is content-derived, so re-saving identical values does not invalidate it")
        void theTagIsContentDerived() {
            String first = controller.settings(PROJECT).getHeaders().getETag();

            // A different instance carrying the same values. A tag taken from
            // projects.updated_at would have moved here and failed an edit that
            // conflicts with nothing.
            when(service.settings(PROJECT)).thenReturn(settings(false));

            assertThat(controller.settings(PROJECT).getHeaders().getETag()).isEqualTo(first);
        }

        @Test
        @DisplayName("the tag moves when the settings do")
        void theTagMoves() {
            String first = controller.settings(PROJECT).getHeaders().getETag();
            when(service.settings(PROJECT)).thenReturn(settings(true));

            assertThat(controller.settings(PROJECT).getHeaders().getETag()).isNotEqualTo(first);
        }
    }

    // ------------------------------------------------------------------
    // the precondition
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the If-Match precondition")
    class Precondition {

        @Test
        @DisplayName("a missing header is 428, not a write that goes through unguarded")
        void missingIsRefused() {
            // Treating a missing precondition as "no conflict" means the guard
            // protects only the clients that already opted in — the set that
            // needed it least.
            assertThatThrownBy(() -> controller.replace(PROJECT, null, write()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

            verify(service, never()).replace(anyLong(), any());
        }

        @Test
        @DisplayName("a blank header is 428 too — an empty string is not a tag")
        void blankIsRefused() {
            assertThatThrownBy(() -> controller.replace(PROJECT, "  ", write()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        }

        @Test
        @DisplayName("a stale tag is 412 and nothing is written")
        void staleIsRefused() {
            assertThatThrownBy(() -> controller.replace(PROJECT, "\"deadbeef\"", write()))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.PRECONDITION_FAILED);

            verify(service, never()).replace(anyLong(), any());
        }

        @Test
        @DisplayName("the current tag is accepted, quoted or bare, weak or strong")
        void currentIsAccepted() {
            String etag = controller.settings(PROJECT).getHeaders().getETag();
            String bare = etag.replace("\"", "");
            when(service.replace(anyLong(), any())).thenReturn(settings(false));

            controller.replace(PROJECT, etag, write());
            controller.replace(PROJECT, bare, write());
            controller.replace(PROJECT, "W/" + etag, write());

            verify(service, org.mockito.Mockito.times(3)).replace(anyLong(), any());
        }

        @Test
        @DisplayName("* is accepted, per RFC 9110")
        void wildcardIsAccepted() {
            when(service.replace(anyLong(), any())).thenReturn(settings(false));

            controller.replace(PROJECT, "*", write());

            verify(service).replace(PROJECT, write());
        }

        @Test
        @DisplayName("the 404 comes before the 428 — a tag from a URL that 404s is not a next step")
        void notFoundBeatsPreconditionRequired() {
            when(service.settings(404L)).thenThrow(new ProjectSettingsService.NoSuchProjectException());

            assertThatThrownBy(() -> controller.replace(404L, null, write()))
                    .isInstanceOf(ProjectSettingsService.NoSuchProjectException.class);
        }
    }

    // ------------------------------------------------------------------
    // the write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the write answers the stored settings, with a tag matching them")
    void theWriteAnswersTheStoredState() {
        when(service.replace(anyLong(), any())).thenReturn(settings(true));
        String etag = controller.settings(PROJECT).getHeaders().getETag();

        var response = controller.replace(PROJECT, etag, write());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().restrictsTaskTypes()).isTrue();
        assertThat(response.getHeaders().getETag()).isNotEqualTo(etag);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * Two states of one project's settings.
     *
     * <p><b>They deliberately differ in more than the one flag, because the
     * obvious fixture collides.</b> The first draft varied only
     * {@code restrictsTaskTypes} and the {@code isAllowed} it implies — and
     * those two booleans move in opposite directions at the same multiplier in
     * a record's {@code hashCode}, so they cancel exactly and both states
     * produce the same tag. Not a fluke of these values: it is what any pair of
     * settings differing only in "restricted, with this one type excluded"
     * versus "unrestricted" will do.
     *
     * <p>Which is a real, if narrow, property of a {@code hashCode}-derived
     * {@code ETag} — two states of one resource can share a tag, and a stale
     * write against the second is then let through. {@link SlaPolicyController}
     * and {@link ProjectController} tag the same way. Left alone rather than
     * replaced with a digest, since diverging here would leave one feature with
     * two schemes for one guarantee, but written down in both places so nobody
     * has to rediscover that the tag is a hash.
     */
    private static ProjectSettingsDtos.ProjectSettings settings(boolean restricts) {
        return new ProjectSettingsDtos.ProjectSettings(
                PROJECT,
                restricts
                        ? ProjectSettingsDtos.AutoAssignRule.ROUND_ROBIN
                        : ProjectSettingsDtos.AutoAssignRule.MANUAL,
                restricts
                        ? List.of(ProjectSettingsDtos.TicketField.MODULE,
                                  ProjectSettingsDtos.TicketField.ASSIGNEE)
                        : List.of(ProjectSettingsDtos.TicketField.MODULE),
                restricts,
                List.of(
                        new ProjectSettingsDtos.SettingsTaskType(
                                2, "PROD_BUG", "Production Bug", true, true),
                        new ProjectSettingsDtos.SettingsTaskType(
                                1, "CHANGE_REQUEST", "Change Request", !restricts, true)));
    }

    private static ProjectSettingsDtos.ProjectSettingsWrite write() {
        return new ProjectSettingsDtos.ProjectSettingsWrite("MANUAL", List.of(), List.of(2));
    }
}
