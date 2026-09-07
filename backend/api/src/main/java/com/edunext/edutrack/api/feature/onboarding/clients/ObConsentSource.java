package com.edunext.edutrack.api.feature.onboarding.clients;

import java.util.Locale;
import java.util.Optional;

/**
 * B-103 · the basis on which a SPOC gave messaging consent — the
 * {@code ObConsentSource} enum in {@code contracts/openapi.yaml}.
 *
 * <h2>Why this is a closed vocabulary and not a string</h2>
 *
 * <p>This is the field a challenged consent is defended with, and free text
 * defends nothing: "ok on call I think" is not a basis, and by the time anyone
 * needs to read it the person who typed it has moved on. Six values that a
 * form can offer as radio buttons are answerable a year later; a text box is
 * not.
 *
 * <h2>It describes how the client gave consent, not which screen recorded it</h2>
 *
 * <p>The alternative was to derive it server-side from the route — {@code
 * WIZARD} for OB-04, {@code OB05_FORM} for the SPOC panel — which is tamper
 * proof and worthless: every staff-entered row in the system would carry the
 * same value, and the column would record nothing that was not already in the
 * URL. What the organisation has to be able to say is <em>where the consent
 * came from</em>. Who typed it and when they typed it are recorded separately,
 * in {@code whatsapp_opt_in_by} and {@code whatsapp_opt_in_at}, and those two
 * <b>are</b> server-derived — so a false claim here still has a name against it.
 *
 * <h2>{@link #UNRECORDED} is readable and unwritable</h2>
 *
 * <p>V20260907_1130 backfilled it onto the rows that carried
 * {@code whatsapp_opt_in = 1} before any of this existed. It is deliberately
 * ugly on screen: those SPOCs have to be re-approached before a message can be
 * sent to them, and a value that looked like an ordinary basis would let them
 * pass for settled. {@link #parse} refuses it, so nothing can put a row back
 * into that state.
 */
enum ObConsentSource {

    /** Said on a call. The commonest real case, and the weakest — hence recorded as itself. */
    VERBAL,

    /** Written in an email the organisation holds. */
    EMAIL,

    /** A signed or filled form, on paper or scanned. */
    WRITTEN,

    /** A clause in the service contract the client signed. */
    CONTRACT,

    /** The client ticked it themselves in the portal. Recorded against no staff user. */
    CLIENT_PORTAL,

    /**
     * The basis is not known. Written only by V20260907_1130's backfill; never
     * by a caller.
     */
    UNRECORDED;

    /**
     * @return the value, or empty for anything outside the vocabulary —
     *         including {@link #UNRECORDED}, which callers may read and never
     *         write
     */
    static Optional<ObConsentSource> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            ObConsentSource parsed = valueOf(value.trim().toUpperCase(Locale.ROOT));
            return parsed == UNRECORDED ? Optional.empty() : Optional.of(parsed);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The vocabulary a caller may send, for the 400's message. */
    static String settableNames() {
        StringBuilder names = new StringBuilder();
        for (ObConsentSource source : values()) {
            if (source == UNRECORDED) {
                continue;
            }
            names.append(names.isEmpty() ? "" : ", ").append(source.name());
        }
        return names.toString();
    }
}
