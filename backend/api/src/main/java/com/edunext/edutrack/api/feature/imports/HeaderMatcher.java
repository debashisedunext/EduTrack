package com.edunext.edutrack.api.feature.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * B-030 · the auto-match behind step 3's suggested mapping.
 *
 * <p>Produces {@code ImportUploadResponse.suggestedMapping}. It is a
 * <em>suggestion</em> in the strict sense: every column stays overridable, and
 * nothing downstream can tell a suggestion from an override. So this is allowed
 * to be wrong, and is therefore allowed to be simple.
 *
 * <p>What it must not be is confidently wrong. Fuzzy matching — edit distance,
 * substrings — would silently map "Support Email" onto "Email" and put the
 * helpdesk address in the account contact field, in a mapping the user was shown
 * and skimmed. Exact-after-normalising either matches or leaves the column for a
 * human, and leaving it is the safe failure.
 */
final class HeaderMatcher {

    private HeaderMatcher() {
    }

    /**
     * @param headers the column headings as they appear in the uploaded sheet
     * @return target field → source column, containing only confident matches
     */
    static ImportMapping suggest(List<ImportField> fields, List<String> headers) {
        Map<String, String> normalisedHeaders = new LinkedHashMap<>();
        for (String header : headers) {
            if (header != null && !header.isBlank()) {
                // First wins: a workbook with the heading twice is the user's
                // problem to resolve in the override dropdown, and quietly
                // preferring the later one would hide that there are two.
                normalisedHeaders.putIfAbsent(normalise(header), header);
            }
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        for (ImportField field : fields) {
            String match = normalisedHeaders.get(normalise(field.header()));
            if (match == null) {
                // Our own field name, so a file exported from this system —
                // or hand-built from the API docs — matches without ceremony.
                match = normalisedHeaders.get(normalise(field.name()));
            }
            if (match != null) {
                mapping.put(field.name(), match);
            }
        }
        return new ImportMapping(mapping);
    }

    /**
     * Case, spacing and punctuation carry no meaning in a column heading:
     * {@code "Client Code"}, {@code "client_code"} and {@code "CLIENT-CODE"} are
     * one heading typed by three people.
     */
    private static String normalise(String header) {
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
