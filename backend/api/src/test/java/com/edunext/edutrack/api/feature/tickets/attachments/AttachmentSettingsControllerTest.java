package com.edunext.edutrack.api.feature.tickets.attachments;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-027 · the wire shape of {@code /attachments/limits}, and the one refusal it
 * has to render as a 422.
 *
 * <p>Plain construction, as the attachment feature's other tests do.
 * {@code RouteAuthorizationTest} covers where the class is mounted and who may
 * reach it, from {@code PermissionMatrix}'s two entries.
 */
class AttachmentSettingsControllerTest {

    private static final long TEN_MB = 10L * 1024 * 1024;
    private static final long FIFTY_MB = 50L * 1024 * 1024;

    private final Authentication caller = new TestingAuthenticationToken("admin", "n/a");

    private AttachmentSettingsService service;
    private AttachmentSettingsController controller;

    @BeforeEach
    void setUp() {
        service = mock(AttachmentSettingsService.class);
        controller = new AttachmentSettingsController(service);
        when(service.effective()).thenReturn(AttachmentLimits.of(TEN_MB, FIFTY_MB, 20));
        when(service.ceilingBytes()).thenReturn(TEN_MB);
    }

    /** The tag the {@code PUT} would have to send back. */
    private String currentEtag() {
        return controller.get().getHeaders().getETag();
    }

    @Nested
    @DisplayName("the read")
    class Read {

        @Test
        @DisplayName("is wrapped in { data } with no meta — the limits are one object, not a collection")
        void isWrapped() throws Exception {
            String json = Jackson2ObjectMapperBuilder.json().build()
                    .writeValueAsString(controller.get().getBody());

            assertThat(json).contains("\"data\"").doesNotContain("\"meta\"");
        }

        @Test
        void carriesAllThreeCapsAndTheServerCeiling() {
            AttachmentSettingsController.LimitsDto data = controller.get().getBody().data();

            assertThat(data.maxFileBytes()).isEqualTo(TEN_MB);
            assertThat(data.maxTicketBytes()).isEqualTo(FIFTY_MB);
            assertThat(data.maxFiles()).isEqualTo(20);
            assertThat(data.ceilingBytes()).isEqualTo(TEN_MB);
        }

        @Test
        @DisplayName("carries an ETag, because the PUT requires one and nothing else emits it")
        void carriesAnEtag() {
            assertThat(currentEtag()).isNotBlank();
        }

        /**
         * Content-derived, so re-saving identical values does not invalidate a
         * tag somebody is holding — the same property
         * {@code ProjectSettingsController} needs and for the same reason.
         */
        @Test
        void theTagIsContentDerived() {
            String first = currentEtag();
            when(service.effective()).thenReturn(AttachmentLimits.of(TEN_MB, FIFTY_MB, 20));

            assertThat(currentEtag()).isEqualTo(first);
        }

        /**
         * The ceiling is this server's multipart configuration and not part of
         * the resource. A tag that included it would differ between
         * differently-configured nodes behind one load balancer and fail a
         * precondition nobody violated.
         */
        @Test
        void andTheCeilingIsNotInTheTag() {
            String first = currentEtag();
            when(service.ceilingBytes()).thenReturn(FIFTY_MB);

            assertThat(currentEtag()).isEqualTo(first);
        }

        /**
         * The read reports what is <em>enforced</em>, which is
         * {@code effective()} — already clamped to the container's limit. A
         * controller that reported the stored value instead would publish a cap
         * the server does not honour, and the client would then accept files the
         * server refuses, which is the exact failure this task exists to remove.
         */
        @Test
        void reportsWhatIsEnforcedRatherThanWhatIsStored() {
            when(service.effective()).thenReturn(AttachmentLimits.of(TEN_MB, FIFTY_MB, 20));
            when(service.ceilingBytes()).thenReturn(TEN_MB);

            assertThat(controller.get().getBody().data().maxFileBytes()).isEqualTo(TEN_MB);
        }
    }

    @Nested
    @DisplayName("the write")
    class Write {

        @Test
        void returnsTheNewStateSoAFormNeedNotRefetch() {
            when(service.replace(any(), anyLong(), anyLong(), anyInt()))
                    .thenReturn(AttachmentLimits.of(TEN_MB, 30L * 1024 * 1024, 5));

            AttachmentSettingsController.LimitsDto data = controller
                    .replace(caller, currentEtag(),
                            new AttachmentSettingsController.LimitsWrite(TEN_MB, 30L * 1024 * 1024, 5))
                    .getBody().data();

            assertThat(data.maxTicketBytes()).isEqualTo(30L * 1024 * 1024);
            assertThat(data.maxFiles()).isEqualTo(5);
        }

        @Nested
        @DisplayName("If-Match is required, not optional")
        class Precondition {

            /**
             * 428 rather than letting it through. Treating a missing
             * precondition as "no conflict" means the guard protects only the
             * clients that already opted in — the set that needed it least.
             */
            @Test
            void aMissingHeaderIs428() {
                assertThatThrownBy(() -> controller.replace(caller, null,
                        new AttachmentSettingsController.LimitsWrite(TEN_MB, FIFTY_MB, 20)))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("428");
            }

            @Test
            void aStaleTagIs412() {
                assertThatThrownBy(() -> controller.replace(caller, "\"deadbeef\"",
                        new AttachmentSettingsController.LimitsWrite(TEN_MB, FIFTY_MB, 20)))
                        .isInstanceOf(ResponseStatusException.class)
                        .hasMessageContaining("412");
            }

            @Test
            void aStaleTagNeverReachesTheService() {
                assertThatThrownBy(() -> controller.replace(caller, "\"deadbeef\"",
                        new AttachmentSettingsController.LimitsWrite(TEN_MB, FIFTY_MB, 20)))
                        .isInstanceOf(ResponseStatusException.class);

                verify(service, never()).replace(any(), anyLong(), anyLong(), anyInt());
            }

            /** RFC 9110: {@code *} matches whatever is current. */
            @Test
            void aWildcardIsAccepted() {
                when(service.replace(any(), anyLong(), anyLong(), anyInt()))
                        .thenReturn(AttachmentLimits.of(TEN_MB, FIFTY_MB, 20));

                assertThat(controller.replace(caller, "*",
                        new AttachmentSettingsController.LimitsWrite(TEN_MB, FIFTY_MB, 20)).getBody())
                        .isNotNull();
            }

            /** A weak tag and a bare one both name the same state. */
            @Test
            void aWeakTagIsAccepted() {
                when(service.replace(any(), anyLong(), anyLong(), anyInt()))
                        .thenReturn(AttachmentLimits.of(TEN_MB, FIFTY_MB, 20));

                assertThat(controller.replace(caller, "W/" + currentEtag(),
                        new AttachmentSettingsController.LimitsWrite(TEN_MB, FIFTY_MB, 20)).getBody())
                        .isNotNull();
            }
        }

        /**
         * All three or nothing. They are only meaningful together, and a
         * per-field write would let a caller reach the state
         * {@link AttachmentLimits#of} refuses in two individually valid steps.
         */
        @Test
        void takesAllThreeAndNothingElse() {
            assertThat(AttachmentSettingsController.LimitsWrite.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactlyInAnyOrder("maxFileBytes", "maxTicketBytes", "maxFiles");
        }

        /**
         * {@code ceilingBytes} is the server's own configuration reported back.
         * Accepting it on the write would let a client appear to raise a bound it
         * does not control.
         */
        @Test
        void andNotTheCeiling() {
            assertThat(AttachmentSettingsController.LimitsWrite.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("ceilingBytes");
        }

        @Test
        void aRefusalPropagatesRatherThanBeingSwallowed() {
            when(service.replace(any(), anyLong(), anyLong(), anyInt()))
                    .thenThrow(new InvalidAttachmentLimitsException("nope"));

            assertThatThrownBy(() -> controller.replace(
                    caller, currentEtag(), new AttachmentSettingsController.LimitsWrite(TEN_MB, FIFTY_MB, 20)))
                    .isInstanceOf(InvalidAttachmentLimitsException.class);
        }
    }

    @Nested
    @DisplayName("the 422")
    class Problem {

        /**
         * 422 and not 400: the body parsed, every field is the right type and
         * within its own declared range, and the refusal is about what the three
         * numbers mean together. {@code CONVENTIONS.md} §3 reserves 422 for
         * exactly that, and the {@code type} URI is what a client branches on —
         * the title and detail are for people and may be reworded.
         */
        @Test
        void isAProblemDocumentWithAStableTypeUri() {
            ResponseEntity<ProblemDetail> response = new AttachmentExceptionHandler()
                    .handleInvalidLimits(new InvalidAttachmentLimitsException("maxTicketBytes must be at least…"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getType())
                    .hasToString("https://edutrack/errors/invalid-attachment-limits");
            assertThat(response.getBody().getDetail()).isEqualTo("maxTicketBytes must be at least…");
        }
    }
}
