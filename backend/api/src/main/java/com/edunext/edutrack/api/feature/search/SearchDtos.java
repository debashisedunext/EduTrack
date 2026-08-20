package com.edunext.edutrack.api.feature.search;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A-072 · what {@code GET /search} answers with.
 *
 * <p>Grouped by kind rather than one ranked list, because the three groups are
 * not comparable: a ticket-code hit is a certainty, a full-text hit is a guess
 * with a score, and a person is neither. Interleaving them would need a
 * relevance number that spans all three, which would be invented rather than
 * measured — and the palette wants sections anyway.
 */
final class SearchDtos {

    private SearchDtos() {
    }

    /**
     * @param exactTicket the ticket whose code was pasted, or null. Separate
     *                    from {@code tickets} because it is a different claim:
     *                    the others are "these mention your words", this is
     *                    "this is the one you asked for" — and it is what the
     *                    client navigates straight to on Enter.
     */
    @Schema(description = "Global search results, grouped by kind.")
    record GlobalSearchResults(GlobalSearchTicketHit exactTicket, List<GlobalSearchTicketHit> tickets, List<GlobalSearchPersonHit> people) {

        static GlobalSearchResults empty() {
            return new GlobalSearchResults(null, List.of(), List.of());
        }
    }

    /**
     * @param ticketId the code, e.g. {@code CRM-26-00347} — never the numeric
     *                 id, matching every other ticket route on the wire
     */
    record GlobalSearchTicketHit(String ticketId, String title, String level, String status) {
    }

    /**
     * Identity and nothing more.
     *
     * <p>No ticket counts, no workload, no last-login: finding a colleague is
     * not the same as reading their performance, which §2 keeps for their
     * manager. A search box is the wrong place to widen that.
     */
    record GlobalSearchPersonHit(long id, String displayName, String username, String email, String role) {
    }

    record GlobalSearchResponse(GlobalSearchResults data) {
    }
}
