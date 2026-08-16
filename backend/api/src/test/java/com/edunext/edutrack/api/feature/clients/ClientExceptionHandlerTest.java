package com.edunext.edutrack.api.feature.clients;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-027 · the wire shape of this feature's problem documents.
 *
 * <p>Written for the reason {@code ResourceExceptionHandlerTest} was: this
 * advice can now emit <b>two different 409s</b> — a duplicate client code and a
 * duplicate contact email — under the <em>same</em> {@code type} URI, and the
 * only thing that distinguishes them is which key is in {@code errors}. Nothing
 * about reading the handler establishes that a client branching on the status and
 * the {@code type}, which CONVENTIONS.md §3 says is the supported thing to do,
 * can still tell them apart.
 */
class ClientExceptionHandlerTest {

    private final ClientExceptionHandler handler = new ClientExceptionHandler();

    // ------------------------------------------------------------------
    // B-026 · the client form
    // ------------------------------------------------------------------

    /**
     * 409 only when a duplicate code is the <b>only</b> failure.
     *
     * <p>A 409 that also carried a bad timezone would be handled by a
     * status-branching client as a uniqueness conflict, and the other message
     * would never be shown — which on a four-tab form means an admin fixing the
     * code, saving, and meeting a second refusal they were never told about.
     */
    @Test
    @DisplayName("a duplicate client code alone is 409")
    void duplicateCodeAloneIs409() {
        ResponseEntity<ProblemDetail> response = handler.handleValidation(
                new ClientWriteService.ClientValidationException(
                        errors("clientCode", "Client code ACME is already in use.")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getType().toString()).endsWith("/duplicate");
    }

    @Test
    @DisplayName("a duplicate code mixed with anything else is 400")
    void aMixedFailureIs400() {
        Map<String, String> errors = errors("clientCode", "Client code ACME is already in use.");
        errors.put("timezone", "'Mars/Olympus' is not a known time zone.");

        ResponseEntity<ProblemDetail> response = handler.handleValidation(
                new ClientWriteService.ClientValidationException(errors));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------
    // B-027 · the contact editor
    // ------------------------------------------------------------------

    /**
     * A contact has one queried rule, so there is no mixture to describe with the
     * wrong status — which is why this is 409 whenever a duplicate email is
     * involved, where the client form above needs it to be the sole failure.
     */
    @Test
    @DisplayName("a duplicate contact email is 409")
    void duplicateContactEmailIs409() {
        ResponseEntity<ProblemDetail> response = handler.handleContact(
                new ClientContactService.ContactValidationException(
                        errors("email", "Sara Kapoor already uses sara@acme.example at this client.")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * <b>The two 409s share a {@code type} and are told apart by their
     * {@code errors} key, not by their prose.</b>
     *
     * <p>This is the assertion the file exists for. A client that switched on the
     * {@code detail} sentence would break the first time somebody reworded a
     * message; one that reads {@code errors} keeps working, and B-013 made the
     * same argument for the resource form's two 409s.
     */
    @Test
    @DisplayName("the two 409s are distinguishable by their errors key")
    void theTwo409sAreDistinguishable() {
        ProblemDetail code = handler.handleValidation(
                new ClientWriteService.ClientValidationException(
                        errors("clientCode", "taken"))).getBody();
        ProblemDetail email = handler.handleContact(
                new ClientContactService.ContactValidationException(
                        errors("email", "taken"))).getBody();

        assertThat(code).isNotNull();
        assertThat(email).isNotNull();
        assertThat(code.getType()).isEqualTo(email.getType());
        assertThat(fieldsOf(code)).containsOnlyKeys("clientCode");
        assertThat(fieldsOf(email)).containsOnlyKeys("email");
    }

    /**
     * String-keyed to a <b>string array</b>, which is what {@code ValidationProblem}
     * declares and what {@code ApiError.fieldErrors} on the frontend reads. A bare
     * string deserialises into a shape the form's {@code messages[0]} silently
     * indexes character by character — so the field would be marked with the
     * letter "S".
     */
    @Test
    @DisplayName("every message is an array, never a bare string")
    void messagesAreArrays() {
        ProblemDetail problem = handler.handleContact(
                new ClientContactService.ContactValidationException(
                        errors("email", "already used"))).getBody();

        assertThat(problem).isNotNull();
        assertThat(fieldsOf(problem).get("email")).isInstanceOf(String[].class);
        assertThat((String[]) fieldsOf(problem).get("email")).containsExactly("already used");
    }

    /**
     * The bulk setter names <b>every</b> missing id rather than the first, so a
     * stale selection assembled across two pages is fixed in one round.
     */
    @Test
    @DisplayName("an unknown-client 404 names every missing id")
    void unknownClientsAreAllNamed() {
        ProblemDetail problem = handler.handleUnknown(
                new ClientService.UnknownClientException(java.util.List.of(7L, 9L))).getBody();

        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).isNotNull();
        assertThat(problem.getProperties().get("clientIds"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly(7L, 9L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        assertThat(properties).isNotNull();
        return (Map<String, Object>) properties.get("errors");
    }

    private static Map<String, String> errors(String field, String message) {
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(field, message);
        return errors;
    }
}
