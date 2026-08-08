package com.edunext.edutrack.api.feature.chat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-053 · turns what somebody typed into a safe MySQL boolean-mode query.
 *
 * <p><strong>Raw input can never reach {@code AGAINST}.</strong> In boolean
 * mode {@code + - * " ( ) ~ < > @} are operators, so a message body pasted into
 * the search box — which is exactly what people do — is at best a query that
 * means something nobody intended and at worst a syntax error the user cannot
 * interpret. Only word characters survive here, and the operators are ones we
 * add.
 *
 * <p>Terms are combined as required prefixes ({@code +word*}), which is what a
 * search box is understood to mean: every word must appear, and a partial word
 * matches. Natural-language mode was the alternative and ranks rather than
 * filters — but the ranking is unused (see {@code ChatRepository.SEARCH} on why
 * results are ordered by recency), so it would buy nothing and lose the AND.
 */
final class ChatSearch {

    /**
     * MySQL's {@code innodb_ft_min_token_size} default. A shorter word is not
     * in the index at all, so requiring it with {@code +} would make the whole
     * query match nothing — "QA fix" would find no message containing "fix".
     * Short terms are therefore dropped rather than required.
     *
     * <p>{@code ChatEngineIT} asserts this still matches what the server
     * reports. If somebody lowers it in {@code my.cnf} and this stays at 3,
     * search silently keeps ignoring words it could now find.
     */
    static final int MIN_TERM_LENGTH = 3;

    /**
     * Unicode-aware: a word in Devanagari is as much a search term as one in
     * Latin.
     *
     * <p>{@code \p{M}} is not decoration. Indic vowel signs are combining marks
     * rather than letters, so {@code [\p{L}\p{N}_]} alone splits तैनाती into
     * three one-letter fragments — each below {@link #MIN_TERM_LENGTH}, so the
     * search silently returns nothing at all.
     */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}\\p{M}_]+");

    /** Enough for any real search box; a bound on what reaches the parser. */
    private static final int MAX_TERMS = 16;

    private ChatSearch() {
    }

    /**
     * @return a boolean-mode expression, or empty when nothing usable was typed
     *         — an all-punctuation query, or only words below
     *         {@link #MIN_TERM_LENGTH}. The caller answers an empty page rather
     *         than an error: "no results" is the truthful answer to a search
     *         this index cannot serve, and a 400 would be blaming the user for
     *         a limit they cannot see.
     */
    static String toBooleanQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(raw);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            String term = matcher.group().toLowerCase(Locale.ROOT);
            if (term.length() >= MIN_TERM_LENGTH) {
                terms.add(term);
            }
        }

        StringJoiner query = new StringJoiner(" ");
        terms.forEach(term -> query.add("+" + term + "*"));
        return query.toString();
    }
}
