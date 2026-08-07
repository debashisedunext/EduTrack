package com.edunext.edutrack.api.realtime;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * D-014 · the destination map from blueprint §9.3.
 *
 * <table>
 *   <caption>§9.3 rooms as STOMP destinations</caption>
 *   <tr><th>Room</th><th>Destination</th></tr>
 *   <tr><td>{@code user:{id}}</td><td>{@code /user/{id}/queue/events}</td></tr>
 *   <tr><td>{@code ticket:{id}}</td><td>{@code /topic/ticket.{id}}</td></tr>
 *   <tr><td>{@code stage:{code}:{projectId}}</td><td>{@code /topic/stage.{code}.{projectId}}</td></tr>
 *   <tr><td>{@code project:{id}}</td><td>{@code /topic/project.{id}}</td></tr>
 *   <tr><td>{@code manager:{id}}</td><td>{@code /topic/manager.{id}}</td></tr>
 * </table>
 *
 * <p><strong>Why this is a class and not a format string at each call site.</strong>
 * A mistyped destination fails silently — the broker has no notion of a room
 * that ought to exist, so the message is accepted and delivered to nobody. That
 * surfaces days later as "realtime is broken for some users" with nothing in
 * the logs. Every destination in the system is built here, and
 * {@link #parse(String)} is the inverse D-013 authorises against.
 *
 * <p>Dots separate the segments after {@code /topic/} because §9.3's rooms are
 * hierarchical and a broker relay (RabbitMQ, if the simple broker is ever
 * outgrown) treats a dotted topic as a routing key it can pattern-match on.
 * Slashes there would flatten that.
 */
public final class RealtimeDestinations {

    private static final String TICKET_PREFIX = "/topic/ticket.";
    private static final String STAGE_PREFIX = "/topic/stage.";
    private static final String PROJECT_PREFIX = "/topic/project.";
    private static final String MANAGER_PREFIX = "/topic/manager.";
    private static final String USER_PREFIX = "/user/";
    private static final String USER_SUFFIX = "/queue/events";

    /**
     * Stage codes are structural, not free text. A code containing a dot would
     * silently re-shape {@code /topic/stage.{code}.{projectId}} — "QA.1" on
     * project 7 and "QA" on project 1 both parse back to plausible-looking
     * rooms — so a subscriber could land in a queue that is not theirs. Reject
     * at the boundary rather than sanitising and hoping.
     */
    private static final Pattern STAGE_CODE = Pattern.compile("[A-Z0-9_-]+");

    private RealtimeDestinations() {
    }

    // ------------------------------------------------------------ building

    /** {@code user:{id}} — resolved to that user's session queue by Spring. */
    public static String user(long userId) {
        return USER_PREFIX + positive(userId, "userId") + USER_SUFFIX;
    }

    /** {@code ticket:{id}} — ribbon advances, comments, typing. */
    public static String ticket(long ticketId) {
        return TICKET_PREFIX + positive(ticketId, "ticketId");
    }

    /** {@code stage:{code}:{projectId}} — a team inbox that updates without refresh. */
    public static String stage(String stageCode, long projectId) {
        if (stageCode == null || !STAGE_CODE.matcher(stageCode).matches()) {
            throw new IllegalArgumentException(
                    "stage code must match " + STAGE_CODE.pattern() + " but was: " + stageCode);
        }
        return STAGE_PREFIX + stageCode + "." + positive(projectId, "projectId");
    }

    /** {@code project:{id}} — ticket creation, dashboard refresh. */
    public static String project(long projectId) {
        return PROJECT_PREFIX + positive(projectId, "projectId");
    }

    /** {@code manager:{id}} — SLA breaches and reportee updates. */
    public static String manager(long managerId) {
        return MANAGER_PREFIX + positive(managerId, "managerId");
    }

    // ------------------------------------------------------------- parsing

    /**
     * The inverse of the builders, for D-013's subscription authorisation.
     *
     * @return empty for anything not in §9.3 — an unknown destination is not an
     *         error here, but it must never be treated as authorised
     */
    public static Optional<RealtimeDestination> parse(String destination) {
        if (destination == null || destination.isBlank()) {
            return Optional.empty();
        }
        if (destination.startsWith(TICKET_PREFIX)) {
            return id(destination.substring(TICKET_PREFIX.length()))
                    .map(RealtimeDestination.TicketTopic::new);
        }
        if (destination.startsWith(PROJECT_PREFIX)) {
            return id(destination.substring(PROJECT_PREFIX.length()))
                    .map(RealtimeDestination.ProjectTopic::new);
        }
        if (destination.startsWith(MANAGER_PREFIX)) {
            return id(destination.substring(MANAGER_PREFIX.length()))
                    .map(RealtimeDestination.ManagerTopic::new);
        }
        if (destination.startsWith(STAGE_PREFIX)) {
            return parseStage(destination.substring(STAGE_PREFIX.length()));
        }
        if (destination.startsWith(USER_PREFIX) && destination.endsWith(USER_SUFFIX)) {
            String raw = destination.substring(
                    USER_PREFIX.length(), destination.length() - USER_SUFFIX.length());
            return id(raw).map(RealtimeDestination.UserQueue::new);
        }
        return Optional.empty();
    }

    private static Optional<RealtimeDestination> parseStage(String remainder) {
        // Split on the last dot: the code cannot contain one, so whatever
        // precedes it is the code and what follows is the project id.
        int separator = remainder.lastIndexOf('.');
        if (separator <= 0) {
            return Optional.empty();
        }
        String stageCode = remainder.substring(0, separator);
        if (!STAGE_CODE.matcher(stageCode).matches()) {
            return Optional.empty();
        }
        return id(remainder.substring(separator + 1))
                .map(projectId -> new RealtimeDestination.StageTopic(stageCode, projectId));
    }

    /** A destination id is a positive long or it is not a destination at all. */
    private static Optional<Long> id(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static long positive(long id, String field) {
        if (id <= 0) {
            throw new IllegalArgumentException(field + " must be positive but was " + id);
        }
        return id;
    }
}
