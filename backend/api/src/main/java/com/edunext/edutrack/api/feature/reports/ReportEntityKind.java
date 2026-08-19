package com.edunext.edutrack.api.feature.reports;

/**
 * B-060 · what a report cell names, when the cell is a link.
 *
 * <h2>An entity, never a path</h2>
 *
 * <p>§7.8 ends the Client Report's line with "drills into the client 360 view",
 * and the generic column/row shape D-001 chose has no notion of a destination.
 * Something has to supply one, and there were two places it could come from.
 *
 * <p>Emitting {@code "/clients/42"} from the server is the shorter version and
 * the wrong one: the frontend's {@code entityLinks.ts} already owns every route
 * pattern in the product, precisely so that the day a screen moves its owner
 * changes one line rather than hunting {@code to={...}} expressions. A server
 * that also spelled routes would be a second copy of the router, in another
 * language, that nothing keeps in step — and the first symptom would be a
 * report whose links 404 after a rename that every other screen survived.
 *
 * <p>So the server says <em>what</em> the cell is and the client decides where
 * that lives. Four kinds, matching the four builders {@code entityLinks.ts}
 * exports, so the mapping is total and a new kind cannot be added here without
 * a route to receive it.
 *
 * <h2>Why not reuse the report's own filter enum</h2>
 *
 * <p>{@link ReportFilterKind} already has {@code CLIENT} and {@code PROJECT}
 * and looks close enough to share. It answers a different question — which
 * control the filter bar draws — and the two vocabularies diverge immediately:
 * {@code DATE_RANGE} and {@code LEVEL} are not entities and nothing drills into
 * them, while {@code TICKET} is an entity no report filters by. One enum
 * serving both would be a superset neither consumer can switch over
 * exhaustively.
 */
public enum ReportEntityKind {

    /** The client 360 view, S-32. */
    CLIENT,

    /** A ticket's detail page, S-20. */
    TICKET,

    /** The project dashboard. */
    PROJECT,

    /** A resource's 360° profile, S-28. */
    RESOURCE
}
