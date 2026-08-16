package com.edunext.edutrack.domain.validation;

import java.util.regex.Pattern;

/**
 * B-028 · <b>one answer to "is this a valid email address?"</b>
 *
 * <p>Blueprint line 948 asks S-33 for "valid emails". It was already answered
 * three different ways, on the same columns:
 *
 * <table>
 *   <caption>Before this class</caption>
 *   <tr><th>Where</th><th>Rule</th><th>{@code a@b}</th></tr>
 *   <tr><td>{@code FieldValidators.EMAIL} — B-030's importer</td>
 *       <td>a dot-separated TLD is required</td><td>rejected</td></tr>
 *   <tr><td>Jakarta {@code @Email} — S-33's form and B-027's contact editor</td>
 *       <td>RFC-permissive</td><td>accepted</td></tr>
 *   <tr><td>zod {@code .email()} — the same two screens, client-side</td>
 *       <td>RFC-permissive</td><td>accepted</td></tr>
 * </table>
 *
 * <p>That is not a cosmetic inconsistency. {@code client_code} is B-035's upsert
 * key, so a client created through S-33 is a client the import will later be
 * asked to update — and under the old arrangement the form would accept
 * {@code accounts@acme} and the importer would reject the very row it produced,
 * reporting a validation failure against data the system issued itself.
 * {@code FieldValidators}' own javadoc says it exists so that two places do not
 * answer this question differently by the second release; the S-33 form was the
 * second place, and it had been there since B-026.
 *
 * <h2>Which of the three rules won, and why the strict one</h2>
 *
 * <p>The importer's. An address with no dotted TLD — {@code bob@localhost},
 * {@code accounts@acme} — is not deliverable from a mail engine that will run
 * outside the machine that stored it, and every one of these columns exists to
 * be *sent to* or *matched against*: {@code support_email} is what D-039 resolves
 * inbound mail on, {@code client_contacts.email} is who D-036 addresses, and
 * {@code notificationOptIn} defaults to true. A mail that can never be delivered
 * is worse than a refused save, which is B-027's argument for making the contact
 * address required in the first place.
 *
 * <p>It is <b>shape only, and deliberately permissive past that</b>, which is
 * B-030's wording kept verbatim because the reasoning has not changed: RFC 5322
 * done properly rejects addresses that work and accepts ones that do not, and a
 * master-data form is not the place to adjudicate that. This catches what
 * actually reaches these fields — a missing {@code @}, a trailing comma from a
 * pasted list, a bare domain, an address with no TLD — and leaves the rest to
 * D-034's bounce handling, which is the only thing that can settle it.
 *
 * <h2>Why {@code domain} and not either feature</h2>
 *
 * <p>{@code api.feature.imports} builds {@code domain.clients.Client} entities,
 * so {@code imports} already depends on {@code domain}; putting the rule in
 * {@code feature/clients} would invert that, and putting it in
 * {@code feature/imports} would make the client master depend on the importer.
 * {@code domain} is what both already reach, which is the same placement
 * argument B-024 made for {@code domain.masters.WorkingHoursService} when
 * D's {@code worker} and C's {@code feature/tickets} both needed it.
 *
 * <p>It is not client-specific: B-038 registers the resource import against the
 * same engine and validates addresses on the same rule.
 */
public final class EmailFormat {

    private EmailFormat() {
    }

    /**
     * B-030's pattern, unchanged and now stated once.
     *
     * <p>Local part: anything but whitespace, a second {@code @}, a comma or a
     * semicolon — the last two because a pasted distribution list is the most
     * common thing that arrives in one of these boxes. Domain: at least two
     * dot-separated labels, so {@code acme} fails and {@code acme.example}
     * passes.
     */
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@,;]+@[^\\s@,;.]+(\\.[^\\s@,;.]+)+$");

    /**
     * Whether {@code candidate} is a well-formed address.
     *
     * <p><b>Trims first.</b> Bean Validation's {@code @Email} did not, so
     * {@code " bob@acme.example "} — which is what a copy out of a spreadsheet
     * cell or a mail client's address bar looks like — was refused as malformed
     * while the identical address typed by hand was accepted. Every caller here
     * stores the trimmed value, so validating the untrimmed one would be
     * answering a question about a string nothing keeps.
     *
     * @return false for null and for blank; a caller that permits an absent
     *         address checks for that itself, because "not supplied" and
     *         "supplied and wrong" are different answers and only one of them is
     *         a validation failure
     */
    public static boolean isValid(String candidate) {
        if (candidate == null) {
            return false;
        }
        String trimmed = candidate.trim();
        return !trimmed.isEmpty() && EMAIL.matcher(trimmed).matches();
    }

    /**
     * The message every caller shows, so the S-33 form, the contact editor and
     * B-034's dry-run preview cannot describe the same rejection differently.
     */
    public static String message(String field) {
        return field + " is not a well-formed email address.";
    }
}
