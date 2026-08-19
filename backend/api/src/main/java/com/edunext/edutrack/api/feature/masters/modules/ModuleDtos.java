package com.edunext.edutrack.api.feature.masters.modules;

import java.util.List;

/**
 * B-064 · the wire types for {@code GET /masters/modules}, matching
 * {@code contracts/openapi.yaml}'s {@code Module} and {@code ModuleListResponse}.
 *
 * <p>Reads only. There is no write shape here and no Bean Validation to state,
 * because there is no write route — see {@link ModuleController}.
 */
final class ModuleDtos {

    private ModuleDtos() {
    }

    /**
     * One module, exactly as the contract's {@code Module} schema describes it.
     *
     * <p><b>{@code id} is {@code long} where the column is {@code INT}</b>,
     * because the contract says {@code format: int64} and the generated
     * TypeScript is typed from that. Widening on the way out costs nothing and
     * keeps the served document inside what the client was generated against;
     * narrowing the contract instead would be a contract change for a schema
     * three of Stream C's shipped screens already consume.
     *
     * <p><b>{@code isActive} is on every row and is the point of the route.</b>
     * A caller offering a picker filters it out; a caller rendering a grid cell
     * does not, because the module a ticket was raised against a year ago still
     * has a name. Both behaviours need the same response, so the distinction is
     * carried in a field rather than in a query parameter.
     *
     * <p>Every property is populated on every response, and the contract marks
     * only four of the five {@code required} — B-016's call on
     * {@code Project.status}, repeated by B-020 and B-021: a required property
     * is an obligation on every consumer that <em>constructs</em> one, and
     * Stream C's ticket-form fixtures construct these.
     */
    record ModuleView(
            long id,
            String code,
            String name,
            short seq,
            boolean isActive) {
    }

    /** The {@code ModuleListResponse} envelope — {@code { "data": [...] }}. */
    record ModuleListResponse(List<ModuleView> data) {
    }
}
