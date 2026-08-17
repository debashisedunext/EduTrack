package com.edunext.edutrack.api.feature.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-032 · the one refusal that has no route-level test, asserted directly.
 *
 * <p>{@code ImportUploadControllerTest} drives the other four over HTTP. The
 * staging ceiling cannot join them: the store is a singleton in that context with
 * a thirty-minute TTL, so filling it would leave every subsequent test in the
 * class without a slot, and lowering the cap for one case would break the rest.
 * A separate {@code @SpringBootTest} for one assertion is a whole extra context.
 *
 * <p>So the handler is called directly, which is proportionate for what is being
 * claimed: the status, the type and the header. That the advice is reachable at
 * all is established by the four cases that do go over HTTP.
 */
class ImportExceptionHandlerTest {

    private final ImportExceptionHandler handler = new ImportExceptionHandler();

    @Test
    @DisplayName("a full staging store is 503 with Retry-After, not a 500")
    void stagingFullIsServiceUnavailable() {
        ResponseEntity<ProblemDetail> response =
                handler.handleStagingFull(new ImportStagingFullException(20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getType())
                .hasToString("https://edutrack/errors/import-staging-full");
        // The message the store wrote is already addressed to a person; what was
        // missing was a status that says "come back" and a header saying when.
        assertThat(problem.getDetail()).contains("Try again shortly");
        assertThat(problem.getProperties()).containsEntry("ceiling", 20);
    }

    /**
     * Each refusal carries a {@code type} of its own, because the step-2 screen
     * behaves differently for each — split the file, Save As, choose another
     * file. One shared {@code import-failed} would make them indistinguishable
     * without parsing English, which CONVENTIONS.md §3 forbids relying on.
     */
    @Test
    void theThreeFileRefusalsDoNotShareAType() {
        String tooLarge = String.valueOf(handler
                .handleTooLarge(ImportLimitExceededException.rows(5_000))
                .getBody().getType());
        String unsupported = String.valueOf(handler
                .handleUnsupported(UnsupportedImportFileException.legacyXls())
                .getBody().getType());
        String unreadable = String.valueOf(handler
                .handleUnreadable(UnreadableImportFileException.noSheets())
                .getBody().getType());

        assertThat(tooLarge).isNotEqualTo(unsupported).isNotEqualTo(unreadable);
        assertThat(unsupported).isNotEqualTo(unreadable);
    }
}
