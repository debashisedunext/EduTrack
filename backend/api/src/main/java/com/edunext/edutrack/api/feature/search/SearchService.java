package com.edunext.edutrack.api.feature.search;

import com.edunext.edutrack.api.security.CallerIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A-072 · global search — blueprint §7.2's "ticket ID / keyword / person", and
 * gap item 9's deep link.
 *
 * <h2>Three questions, answered separately and in order of certainty</h2>
 *
 * <ol>
 *   <li><b>Is it a ticket code?</b> Then it is a deep link, and the answer is
 *       one row from a unique index. PLAN.md §3.8: "the dominant search, and it
 *       must be exact and instant".</li>
 *   <li><b>Is it a word?</b> Then it is a full-text match over title and
 *       description, ranked.</li>
 *   <li><b>Is it a name?</b> Then it is a person.</li>
 * </ol>
 *
 * <p>All three run for any query, because they are not mutually exclusive:
 * somebody typing "Nair" wants the person, and somebody typing "login" wants
 * tickets — but "CRM" is both a project prefix and a word, and deciding which
 * the user meant would be guessing. The client renders the groups it gets.
 *
 * <h2>The code branch does not suppress the others</h2>
 *
 * <p>A recognised code still runs the keyword and people searches. It costs two
 * cheap queries and it removes a failure mode: a code-shaped string that
 * matches no visible ticket would otherwise return a blank screen, when the
 * same string might well appear in a ticket's description — somebody pasting a
 * code a colleague quoted at them, where the ticket itself is out of scope but
 * a comment referencing it is not.
 */
@Service
class SearchService {

    /**
     * Results per group.
     *
     * <p>Small on purpose. This feeds a palette somebody is reading while
     * typing, not a results page — the useful behaviour when a query matches
     * forty tickets is to type another word, not to scroll. The ticket list at
     * {@code /tickets?q=} is where a wide search belongs, and the palette links
     * there.
     */
    private static final int PER_GROUP = 6;

    /** Everything MySQL's boolean-mode parser treats as an operator. */
    private static final String BOOLEAN_OPERATORS = "+-><()~*\"@";

    private final SearchRepository repository;

    SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    SearchDtos.GlobalSearchResults search(CallerIdentity caller, String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return SearchDtos.GlobalSearchResults.empty();
        }

        SearchScope scope = SearchScope.of(caller);

        SearchDtos.GlobalSearchTicketHit exact = TicketCode.from(query)
                .flatMap(code -> repository.exactTicket(code, scope))
                .map(SearchService::toTicket)
                .orElse(null);

        List<SearchDtos.GlobalSearchTicketHit> tickets = booleanQuery(query)
                .map(q -> repository.ticketsMatching(q, scope, PER_GROUP))
                .orElseGet(List::of)
                .stream()
                .map(SearchService::toTicket)
                // The exact hit is already shown on its own, and repeating it in
                // the list below reads as two different tickets with one code.
                .filter(t -> exact == null || !t.ticketId().equals(exact.ticketId()))
                .toList();

        List<SearchDtos.GlobalSearchPersonHit> people = query.length() >= 2
                ? repository.peopleMatching(query, PER_GROUP).stream()
                        .map(p -> new SearchDtos.GlobalSearchPersonHit(
                                p.id(), p.fullName(), p.username(), p.email(), p.roleCode()))
                        .toList()
                : List.of();

        return new SearchDtos.GlobalSearchResults(exact, tickets, people);
    }

    /**
     * The user's words as a boolean-mode expression, or empty when none of them
     * can be indexed.
     *
     * <h2>🔴 Every operator character is stripped, not escaped</h2>
     *
     * <p>Boolean mode gives {@code + - * " ( ) ~ &lt; &gt; @} meaning, and a
     * user typing them means none of it — they are typing a hyphenated word, a
     * quoted phrase, or an email address. Left in place they change the query's
     * semantics silently: a leading {@code -} turns a word into an
     * <em>exclusion</em>, so searching "login -error" quietly returns tickets
     * that do not mention error, and "C++" is a parse error rather than a
     * search.
     *
     * <p>Stripped rather than escaped because there is nothing to escape
     * <em>to</em>: MySQL has no escape syntax inside boolean mode, and quoting
     * the whole string turns it into a phrase search, which is a different
     * query from the one that was typed.
     *
     * <p>This is not the SQL-injection boundary — {@code :q} is a bound
     * parameter and cannot be. It is the FTS-injection boundary, which is a real
     * and separate thing: an unsanitised term cannot read another table, and it
     * can make a search return confidently wrong results.
     *
     * <h2>Short words are dropped, and the whole query with them</h2>
     *
     * <p>Terms below {@link SearchRepository#MIN_TERM_LENGTH} are not in the
     * index and can never match, so they are removed. If that leaves nothing —
     * somebody searched "QA" or "UI" — the result is empty rather than a query
     * for the empty string, which in boolean mode matches every row in the
     * table. {@code ChatSearch} makes the same call for the same reason.
     */
    static Optional<String> booleanQuery(String raw) {
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            cleaned.append(BOOLEAN_OPERATORS.indexOf(c) >= 0 ? ' ' : c);
        }

        String joined = Arrays.stream(cleaned.toString().split("\\s+"))
                .filter(term -> term.length() >= SearchRepository.MIN_TERM_LENGTH)
                // A trailing * on each term, so "log" finds "login" while the
                // reader is still typing. Added here rather than left to the
                // user, who would have to know boolean mode exists.
                .map(term -> term + "*")
                .reduce((a, b) -> a + " " + b)
                .orElse("");

        return joined.isBlank() ? Optional.empty() : Optional.of(joined);
    }

    private static SearchDtos.GlobalSearchTicketHit toTicket(SearchRepository.Hit hit) {
        return new SearchDtos.GlobalSearchTicketHit(hit.ticketCode(), hit.title(), hit.level(), hit.status());
    }
}
