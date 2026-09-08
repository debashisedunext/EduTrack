package com.edunext.edutrack.api.feature.onboarding.reports;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * B-123 · what a report column holds, where "holds" is a disclosure question
 * rather than a formatting one.
 *
 * <h2>Why this is not {@link ObReportDtos.ColumnType}</h2>
 *
 * <p>{@code ColumnType} answers "how does the client draw this cell" and is on
 * the wire — A-118 declares its six values and
 * {@code ObReportWireVocabularyTest} pins them. This answers "may the caller
 * have the value at all", is decided server-side, and never leaves the server.
 * Merging them would put a disclosure rule into a vocabulary the frontend
 * switches on, and a client that can read the classification is a client that
 * can be asked to apply it — which is the arrangement CLAUDE.md's row-scoping
 * rule already refuses for exactly the same reason ("never by a frontend
 * filter").
 *
 * <h2>The word list is the enforcement, not a lint</h2>
 *
 * <p>A classification a writer must remember to apply is a classification that
 * is eventually not applied, and the failure is silent — the column exports,
 * the file looks right, and nothing says a PAN went out in it. So
 * {@link #looksSensitive} is consulted by {@link ObReportDtos.Column}'s own
 * constructor: a column whose name reads as a PAN or an amount and is declared
 * {@link #ORDINARY} cannot be constructed. The check runs wherever a column
 * list is built, which for every runner in this package is inside
 * {@code ObReportRunnersTest}, so the refusal lands in CI rather than in a
 * download.
 *
 * <p>It is deliberately a name heuristic and deliberately not clever. It cannot
 * catch a PAN column called {@code identifier}; nothing automatic can. What it
 * catches is the realistic case — somebody adding {@code pan} or
 * {@code amount} to a runner and not thinking about the export — and for that
 * case it is total.
 */
enum ObReportSensitivity {

    /** No disclosure rule. The overwhelming majority of columns. */
    ORDINARY,

    /**
     * An Indian PAN. Exported and rendered through {@code PanFormat.mask} — the
     * last four characters and nothing else — for <b>every</b> role.
     *
     * <p>Not "every role except OB_ADMIN and OB_MANAGER", which is what the
     * on-screen rule says about the client detail page, and the difference is
     * the point of A-113. The unmasked value is not a field any payload
     * carries; it comes from a reveal operation that writes one audit row per
     * disclosure. A report of five hundred clients cannot produce five hundred
     * audit rows, so an unmasked column here would be the one bulk read of PAN
     * in the product with no trail behind it — the exact hole
     * PHASE-2-BUILD-PLAN finding 10 closed when it made revealing an explicit
     * act rather than a role-based default.
     *
     * <p>So the masked form is what every role sees on this surface, which is
     * also what every role sees on the detail page, which is what B-123's
     * "stripped for every role that cannot see them on screen" resolves to
     * once the reveal is a separate operation.
     */
    PAN,

    /**
     * A currency figure. Removed, not masked — a masked amount still discloses
     * its order of magnitude.
     *
     * <p><b>No column in this module is classified {@code MONEY} today, and
     * that is not an oversight.</b> Plan §1.2 removed financial tracking from
     * onboarding entirely — {@code V20260903_1210__ob_client_capture.sql} says
     * "A PURCHASE FACT, NOT A COMMERCIAL ONE. No amount, no invoice, no payment
     * status" — so there is no amount anywhere to redact. The constant exists
     * because B-123 names payment amounts and because {@link #looksSensitive}
     * needs something to point a money-named column at: a later report that
     * grows one is refused at construction rather than exporting it, which is
     * the difference between the product decision being recorded and it being
     * enforced.
     */
    MONEY;

    /**
     * Names that read as a PAN or as an amount.
     *
     * <p>Whole words, matched against the camel-case parts of a key and the
     * words of a label. Substring matching was the obvious first version and is
     * wrong in a way that would have been discovered late: {@code "pan"} is
     * inside {@code company}, {@code expand} and {@code span}, and a guard that
     * throws on {@code "Sales person / company"} is a guard somebody turns off.
     */
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "pan",
            "amount", "amounts",
            "invoice", "invoices",
            "payment", "payments",
            "price", "pricing",
            "fee", "fees");

    /** Splits {@code clientPan} into {@code client}, {@code pan}, and a label into its words. */
    private static final Pattern WORDS = Pattern.compile("[^A-Za-z]+|(?<=[a-z])(?=[A-Z])");

    /**
     * True where this name reads as something {@link #ORDINARY} should not be
     * covering.
     *
     * @param names the column's key and label — both, because a runner may
     *              name the key {@code holderId} and the label "PAN", and the
     *              file the caller opens shows the label
     */
    static boolean looksSensitive(String... names) {
        for (String name : names) {
            if (name == null) {
                continue;
            }
            for (String word : WORDS.split(name)) {
                if (SENSITIVE_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }
}
