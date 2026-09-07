package com.edunext.edutrack.api.feature.onboarding.clients;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * B-102 · "is this the client we already have, spelled differently?"
 *
 * <h2>What this is for, and what it deliberately is not</h2>
 *
 * <p>The PAN guard is exact and final: one legal entity, one row, refused by
 * {@code uq_ob_clients_pan_blind}. This is the other half of plan §1.1 item 6,
 * and the contract is explicit that it works the opposite way — <em>"a similar
 * name is a different matter and is <b>a warning, not a refusal</b>: 'Acme Pvt
 * Ltd' and 'Acme Private Limited' are frequently two real clients."</em>
 *
 * <p>So this class is tuned to be <b>generous about what it flags</b> and it is
 * never given the last word. A false positive costs the boarder one checkbox;
 * a false negative costs the organisation two half-onboarded copies of one
 * client, which is the state no report survives. That asymmetry is the whole
 * design and it is why there is no similarity threshold to tune upward later
 * without re-reading this paragraph.
 *
 * <h2>Legal forms are noise, and stripping them is most of the work</h2>
 *
 * <p>An Indian company name is a distinctive core wrapped in interchangeable
 * legal furniture: <em>Pvt</em>/<em>Private</em>, <em>Ltd</em>/<em>Limited</em>,
 * <em>LLP</em>, <em>Inc</em>, <em>Corp</em>, <em>Co</em>. Two records for one
 * company almost never differ in the core and almost always differ somewhere in
 * the furniture, because whoever typed the second one abbreviated differently.
 * Comparing raw strings therefore finds nothing; comparing cores finds the case
 * the guard exists for.
 *
 * <p>{@link #core} is the normalisation: fold accents, lower-case, drop
 * punctuation, expand the abbreviations onto one spelling, and then remove the
 * legal-form words entirely. "Acme Pvt Ltd" and "Acme Private Limited" both
 * become {@code [acme]}.
 *
 * <h2>Two names match on the core, or on a typo in it</h2>
 *
 * <ol>
 *   <li><b>Equal cores</b> — the abbreviation case above, and the one that
 *       matters most.</li>
 *   <li><b>One core contained in the other</b> — "Acme" against "Acme
 *       Technologies". Frequently two real clients, which is exactly why this
 *       warns rather than refuses.</li>
 *   <li><b>An edit-distance match on the joined core</b> — "Acme Acadmey"
 *       against "Acme Academy". Bounded at {@link #maxEdits}, which is
 *       length-proportional so that a two-character word does not match every
 *       other two-character word.</li>
 * </ol>
 *
 * <p>Deliberately <b>not</b> phonetic (Soundex/Metaphone). Those are tuned for
 * English surnames and, on a corpus of Indian institution names, fire on
 * unrelated pairs often enough that the warning stops being read — which is the
 * failure mode that matters here, since the whole mechanism depends on somebody
 * still looking at it on the hundredth client.
 */
final class SimilarClientNames {

    private SimilarClientNames() {
    }

    /**
     * Legal forms and the connective words around them, removed before
     * comparison.
     *
     * <p>Both spellings of every abbreviation are listed rather than expanded
     * into one: expansion would need a direction ("pvt" → "private" or the
     * reverse) and the pair is only ever removed, so a canonical form would be
     * a decision with no consumer.
     */
    private static final Set<String> LEGAL_FORMS = Set.of(
            "pvt", "pvtltd", "private", "ltd", "limited", "llp", "llc",
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "and", "the", "of", "group", "holdings", "enterprises", "ventures",
            "india", "indian");

    /** Everything that is not a letter or a digit, in any script. */
    private static final String NON_ALPHANUMERIC = "[^\\p{IsAlphabetic}\\p{IsDigit}]+";

    /**
     * The distinctive words of a name, in order, with legal forms removed.
     *
     * <p>Never empty for a non-blank name: a company called "The Company Ltd"
     * strips to nothing, and returning an empty core would make it similar to
     * every other name that also stripped to nothing. In that case the
     * normalised tokens are kept as they are — an unusual name is still a name,
     * and matching it against another unusual one is the correct answer rather
     * than matching it against all of them.
     */
    static String[] core(String name) {
        if (name == null || name.isBlank()) {
            return new String[0];
        }
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String[] tokens = Arrays.stream(folded.split(NON_ALPHANUMERIC))
                .filter(t -> !t.isBlank())
                .toArray(String[]::new);
        String[] distinctive = Arrays.stream(tokens)
                .filter(t -> !LEGAL_FORMS.contains(t))
                .toArray(String[]::new);
        return distinctive.length == 0 ? tokens : distinctive;
    }

    /**
     * The most distinctive word of a name — the longest word of its core.
     *
     * <p>This is the SQL prefilter's probe, and its job is to be selective
     * rather than complete: the repository fetches the clients whose stored
     * name contains it and this class scores those. The longest word is chosen
     * because it is the least likely to be shared by unrelated clients — a
     * probe of "sri" or "new" would fetch half the table on an Indian client
     * master, and the query would degrade into a scan that scores everything.
     *
     * @return null for a name with no usable core, which the repository reads
     *         as "no candidates" rather than as "match everything"
     */
    static String probe(String name) {
        String longest = null;
        for (String token : core(name)) {
            if (longest == null || token.length() > longest.length()) {
                longest = token;
            }
        }
        return longest;
    }

    /** True where two names are close enough that a person should look at both. */
    static boolean similar(String left, String right) {
        String[] leftCore = core(left);
        String[] rightCore = core(right);
        if (leftCore.length == 0 || rightCore.length == 0) {
            return false;
        }

        Set<String> leftSet = new LinkedHashSet<>(Arrays.asList(leftCore));
        Set<String> rightSet = new LinkedHashSet<>(Arrays.asList(rightCore));
        if (leftSet.equals(rightSet)) {
            return true;
        }
        // Containment, in whichever direction. "Acme" is a candidate for "Acme
        // Technologies" and the reverse is the same question asked from the
        // other row, so both directions have to answer the same way or the
        // warning would depend on which client was boarded first.
        if (leftSet.containsAll(rightSet) || rightSet.containsAll(leftSet)) {
            return true;
        }

        String leftJoined = String.join("", leftCore);
        String rightJoined = String.join("", rightCore);
        int allowed = maxEdits(Math.min(leftJoined.length(), rightJoined.length()));
        return allowed > 0 && editDistanceWithin(leftJoined, rightJoined, allowed);
    }

    /**
     * How many typos are tolerated in a core of this length.
     *
     * <p>Proportional rather than constant, and zero below four characters. A
     * flat "two edits" makes every three-letter core similar to every other
     * one — "IBM" and "IBS" and "IIM" — which is the point at which a warning
     * that fires on everything is dismissed on everything.
     */
    static int maxEdits(int shorterLength) {
        if (shorterLength < 4) {
            return 0;
        }
        return shorterLength <= 8 ? 1 : 2;
    }

    /**
     * Levenshtein distance, abandoned as soon as it exceeds {@code allowed}.
     *
     * <p>Bounded rather than complete because the answer is only ever compared
     * against a small limit: the full distance between two unrelated
     * forty-character names is a number nothing reads.
     */
    static boolean editDistanceWithin(String left, String right, int allowed) {
        if (Math.abs(left.length() - right.length()) > allowed) {
            return false;
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            int rowBest = current[0];
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
                rowBest = Math.min(rowBest, current[j]);
            }
            if (rowBest > allowed) {
                // Every remaining row can only grow, so no path back under the
                // limit exists. Bailing here is what keeps the scoring cheap
                // enough to run over every candidate the probe returned.
                return false;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()] <= allowed;
    }
}
