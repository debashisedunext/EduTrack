package com.edunext.edutrack.api.feature.onboarding.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-106 · that the requirements PATCH body actually binds.
 *
 * <p>The second hand-written bean in this package, and it carries the same trap
 * {@code ObClientUpdateRequestBindingTest} was written for: Jackson's default
 * setter visibility is {@code PUBLIC_ONLY}, so a package-private setter is never
 * discovered, every field arrives null, and the PATCH answers 200 having changed
 * nothing — no exception, no log line, nothing else in the build that notices.
 *
 * <p>Writing it again rather than trusting that the lesson stuck is the point.
 * The bug is invisible in review precisely because the class <em>looks</em>
 * correct, and the only test that can fail for the right reason is one that
 * deserialises real JSON instead of calling the setters.
 */
class ObRequirementUpdateRequestBindingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a field in the body arrives, and is marked present")
    void presentFieldsBind() throws Exception {
        ObRequirementUpdateRequest request = json.readValue("""
                {"title":"Single sign-on","bodyHtml":"<p>Against their Azure AD</p>","isMet":true}
                """, ObRequirementUpdateRequest.class);

        assertThat(request.hasTitle()).isTrue();
        assertThat(request.getTitle()).isEqualTo("Single sign-on");
        assertThat(request.hasBodyHtml()).isTrue();
        assertThat(request.getBodyHtml()).isEqualTo("<p>Against their Azure AD</p>");
        assertThat(request.hasIsMet()).isTrue();
        assertThat(request.met()).isTrue();
    }

    @Test
    @DisplayName("a field the body omits is absent, not null-and-present")
    void omittedFieldsAreAbsent() throws Exception {
        ObRequirementUpdateRequest request =
                json.readValue("{\"isMet\":true}", ObRequirementUpdateRequest.class);

        assertThat(request.hasIsMet()).isTrue();
        assertThat(request.hasTitle()).isFalse();
        assertThat(request.hasBodyHtml()).isFalse();
    }

    /**
     * The distinction the class exists for, and it goes a different way here
     * than on {@code ObClientUpdateRequest}.
     */
    @Test
    @DisplayName("an explicit null title is a request to clear the label")
    void explicitNullTitleClears() throws Exception {
        ObRequirementUpdateRequest request =
                json.readValue("{\"title\":null}", ObRequirementUpdateRequest.class);

        assertThat(request.hasTitle()).isTrue();
        assertThat(request.getTitle()).isNull();
    }

    /**
     * {@code body_html} is {@code NOT NULL} and a requirement with nothing to
     * say is not a requirement — clearing one is deleting it, and there is a
     * {@code DELETE} for that.
     */
    @Test
    @DisplayName("an explicit null body counts as absent, because there is nothing it could mean")
    void explicitNullBodyIsAbsent() throws Exception {
        ObRequirementUpdateRequest request =
                json.readValue("{\"bodyHtml\":null}", ObRequirementUpdateRequest.class);

        assertThat(request.hasBodyHtml()).isFalse();
    }

    /** {@code is_met} is {@code NOT NULL} too, so the same reasoning applies. */
    @Test
    @DisplayName("an explicit null isMet counts as absent")
    void explicitNullMetIsAbsent() throws Exception {
        ObRequirementUpdateRequest request =
                json.readValue("{\"isMet\":null}", ObRequirementUpdateRequest.class);

        assertThat(request.hasIsMet()).isFalse();
    }

    @Test
    @DisplayName("an empty body changes nothing at all")
    void emptyBodyTouchesNothing() throws Exception {
        ObRequirementUpdateRequest request =
                json.readValue("{}", ObRequirementUpdateRequest.class);

        assertThat(request.hasTitle()).isFalse();
        assertThat(request.hasBodyHtml()).isFalse();
        assertThat(request.hasIsMet()).isFalse();
    }
}
