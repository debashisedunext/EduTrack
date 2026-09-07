package com.edunext.edutrack.api.feature.onboarding.dashboard;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Optional;

/**
 * B-121 · the seven cards of OB-02, as a closed vocabulary.
 *
 * <p>Mirrors {@code ObDashboardCardKey} in {@code contracts/openapi.yaml}.
 * Closed where {@code reportKey} is deliberately open, and A-118 records why:
 * the card board is a <em>fixed layout</em> the design draws, so an eighth card
 * is a change to OB-02's composition and to the contract and gets read, while
 * the report catalogue is a catalogue that already knows five more are coming.
 *
 * <h2>The order of the constants is the order of the board</h2>
 *
 * <p>The contract says all seven are returned "always, in {@code
 * ObDashboardCardKey} order — a card whose count is zero is drawn as zero
 * rather than omitted", so declaration order here is load-bearing rather than
 * cosmetic: {@link #values()} is what {@code ObDashboardService} iterates, and
 * reordering these lines reorders the screen. Stated because it is the kind of
 * edit that looks free.
 *
 * <h2>Two of the seven count clients and five count journeys or steps</h2>
 *
 * <p>{@link #OVERDUE_CLIENTS}, {@link #LIVE} and {@link #CLIENT_ESCALATIONS}
 * are client-counted; the rest are journey- or step-counted. That distinction
 * is not decoration — it decides whether a figure may be summed across the
 * product rows of {@code ob_dashboard_summary}, which is
 * {@link ObDashboardSummaryRepository}'s central problem and is documented
 * there rather than here. {@link #isClientCounted()} is what carries it, so
 * the classification lives beside the names it classifies instead of as a set
 * literal in the repository that would have to be kept in step by hand.
 */
enum ObDashboardCardKey {

    /**
     * Every open journey — locked, held and running alike. Journey-counted, so
     * a client who bought three products contributes three (contract, and plan
     * §9's "roll-ups journey-counted with a product dimension").
     */
    ONGOING_PROJECTS("ongoing-projects"),

    /** Services and prerequisite tasks due Mon–Sun. Step-counted. */
    THIS_WEEKS_DEADLINES("this-weeks-deadlines"),

    /** Services and prerequisite tasks due today. Step-counted. */
    TODAYS_DELIVERY("todays-delivery"),

    /**
     * Distinct clients carrying at least one overdue item — <b>clients, not
     * items</b>. A client late on four services is one client to chase, which
     * is A-108's own note on the column and the contract's stated exception to
     * journey-counting.
     */
    OVERDUE_CLIENTS("overdue-clients", true),

    /** Distinct clients whose {@code overall_status} is {@code LIVE}. Client-counted. */
    LIVE("live", true),

    /**
     * Open journeys whose worst RAG is amber or red. Journey-counted, and
     * deliberately not "amber + red + locked": a journey whose gate has not
     * cleared has no colour at all (A-108), and calling a client who has not
     * started "at risk" would put the whole of a fresh intake on this card.
     */
    AT_RISK("at-risk"),

    /**
     * Distinct clients with an open portal escalation. Client-counted for the
     * same reason {@link #OVERDUE_CLIENTS} is: A-128 allows one open
     * escalation per service, so a client unhappy with three would otherwise
     * treble its own card.
     */
    CLIENT_ESCALATIONS("client-escalations", true);

    private final String wireName;
    private final boolean clientCounted;

    ObDashboardCardKey(String wireName) {
        this(wireName, false);
    }

    ObDashboardCardKey(String wireName, boolean clientCounted) {
        this.wireName = wireName;
        this.clientCounted = clientCounted;
    }

    /** The kebab-case token the contract declares. Serialised, never the constant name. */
    @JsonValue
    String wireName() {
        return wireName;
    }

    /**
     * Whether this card counts <em>clients</em> rather than journeys or steps.
     *
     * <p>See {@link ObDashboardSummaryRepository} for what hangs on it. The
     * short form: {@code ob_dashboard_summary} stores these three as
     * {@code COUNT(DISTINCT client)} <em>per product</em>, so adding the
     * product rows up counts a multi-product client once per product.
     */
    boolean isClientCounted() {
        return clientCounted;
    }

    /**
     * Resolve a wire token.
     *
     * <p>Present for the slide-over route B-127 adds, which the contract
     * requires to answer 400 on an unrecognised key rather than fall back to a
     * default card — "which would show a caller who mistyped a list that looked
     * correct". Nothing on this task's own route takes a key from the caller;
     * the parse lives here anyway so that when it is needed there is not a
     * second, subtly different one.
     */
    static Optional<ObDashboardCardKey> fromWire(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String trimmed = token.trim().toLowerCase(Locale.ROOT);
        for (ObDashboardCardKey key : values()) {
            if (key.wireName.equals(trimmed)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }
}
