package com.edunext.edutrack.api.feature.onboarding.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-102 · that the partial PATCH body actually binds.
 *
 * <p>{@link ObClientUpdateRequest} is the one hand-written bean in a package of
 * records, and it has a failure mode nothing else in the build would catch:
 * Jackson's default setter visibility is {@code PUBLIC_ONLY}, so a
 * package-private setter is never discovered and every field arrives null —
 * with no exception, no log line and no failing test. The PATCH would answer
 * 200 and change nothing.
 *
 * <p>So this deserialises real JSON rather than calling the setters, which is
 * the only version of the test that can fail for that reason.
 */
class ObClientUpdateRequestBindingTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a field in the body arrives, and is marked present")
    void presentFieldsBind() throws Exception {
        ObClientUpdateRequest request = json.readValue("""
                {"name":"Horizon Academy","address":"12 New Road","salesPersonId":7,
                 "licenseType":"ANNUAL","status":"ON_HOLD","statusReason":"Awaiting data"}
                """, ObClientUpdateRequest.class);

        assertThat(request.hasName()).isTrue();
        assertThat(request.getName()).isEqualTo("Horizon Academy");
        assertThat(request.hasAddress()).isTrue();
        assertThat(request.getAddress()).isEqualTo("12 New Road");
        assertThat(request.hasSalesPersonId()).isTrue();
        assertThat(request.getSalesPersonId()).isEqualTo(7L);
        assertThat(request.hasLicenseType()).isTrue();
        assertThat(request.hasStatus()).isTrue();
        assertThat(request.getStatusReason()).isEqualTo("Awaiting data");
    }

    @Test
    @DisplayName("a field the body omits is absent, not null-and-present")
    void omittedFieldsAreAbsent() throws Exception {
        ObClientUpdateRequest request =
                json.readValue("{\"name\":\"Horizon Academy\"}", ObClientUpdateRequest.class);

        assertThat(request.hasName()).isTrue();
        assertThat(request.hasAddress()).isFalse();
        assertThat(request.hasDescription()).isFalse();
        assertThat(request.hasSalesPersonId()).isFalse();
        assertThat(request.hasLicenseType()).isFalse();
        assertThat(request.hasStatus()).isFalse();
    }

    /** The distinction the whole class exists for: explicit null clears. */
    @Test
    @DisplayName("an explicit null is present and null, which is a request to clear the field")
    void explicitNullIsPresent() throws Exception {
        ObClientUpdateRequest request =
                json.readValue("{\"address\":null}", ObClientUpdateRequest.class);

        assertThat(request.hasAddress()).isTrue();
        assertThat(request.getAddress()).isNull();
    }

    /**
     * {@code overall_status} is NOT NULL with no empty member, so a null status
     * can only mean "leave it alone" — which is what omitting it already means.
     */
    @Test
    @DisplayName("an explicit null status is treated as absent, unlike every other field")
    void explicitNullStatusIsAbsent() throws Exception {
        ObClientUpdateRequest request =
                json.readValue("{\"status\":null}", ObClientUpdateRequest.class);

        assertThat(request.hasStatus()).isFalse();
    }
}
