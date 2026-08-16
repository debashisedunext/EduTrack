package com.edunext.edutrack.api.feature.imports.schemas;

import com.edunext.edutrack.api.feature.imports.ImportField;
import com.edunext.edutrack.domain.clients.ClientCodeFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-028 · <b>the importer and the S-33 form agree about a client code.</b>
 *
 * <p>They did not. The form's {@code @Pattern} allowed
 * {@code ^[A-Za-z0-9][A-Za-z0-9_-]*$} and this registration applied
 * {@code FieldValidators.alphanumeric()} — letters and digits only. Since
 * {@code client_code} is B-035's upsert key, {@code ACME-IN} created through
 * S-33 was a client the import could never update: the row was rejected, so an
 * import that reported success left the record stale with the reason buried in
 * B-036's error report.
 *
 * <p>Both now read {@link ClientCodeFormat}. This file is the seam, and it is
 * <em>here</em> rather than on {@code FieldValidatorsTest} for the reason
 * {@code ImportEngineIsolationTest} enforces: a client-shaped rule on the shared
 * engine is the first half of a second import flow, so the rule and its test
 * both belong to the registration.
 */
class ClientImportSchemaValidationTest {

    private static final ClientImportSchema SCHEMA = new ClientImportSchema(null);

    private static Optional<String> validateCode(String value) {
        ImportField code = SCHEMA.fields().stream()
                .filter(f -> f.name().equals("clientCode"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the client registration no longer declares a clientCode column"));

        return code.validators().stream()
                .map(v -> v.validate(value))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACME", "acme", "A1", "ACME-IN", "NORTHWIND_UK"})
    @DisplayName("every code the S-33 form issues is importable")
    void theCodesTheFormIssuesAreImportable(String code) {
        assertThat(validateCode(code)).isEmpty();
    }

    /**
     * The one that used to fail. Asserted on its own rather than only in the set
     * above, because it is the whole defect: a hyphen is ordinary in a client
     * code and there is nothing it breaks — unlike {@code project_code}, this is
     * not a ticket-ID prefix, so nothing parses it positionally.
     */
    @Test
    @DisplayName("a hyphenated code is accepted — alphanumeric() refused it")
    void aHyphenatedCodeIsAccepted() {
        assertThat(validateCode("ACME-IN")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-ACME", "_ACME", "AC ME", "ACME!", "ACME.IN"})
    @DisplayName("the codes the form refuses are refused here too, with the same wording")
    void theCodesTheFormRefusesAreRefused(String code) {
        assertThat(validateCode(code)).contains(ClientCodeFormat.SENTENCE);
    }
}
