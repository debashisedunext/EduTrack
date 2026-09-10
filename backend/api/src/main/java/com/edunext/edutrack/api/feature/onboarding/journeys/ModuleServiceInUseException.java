package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * C-124 · <b>a Module Service a client is already on cannot be renamed,
 * re-pointed at another product, or deleted.</b>
 *
 * <p>This is the one rule the OB-07 catalogue's Edit and Delete controls turn
 * on, and it is checked against the whole version chain rather than against
 * one row. {@code ObJourney.templateId} pins the version a client was boarded
 * on and never moves, so a client sitting on v1 while the catalogue shows v3
 * is still a client on this service — asking only about the head would report
 * it as unused and let the delete through.
 *
 * <p><b>Why a rename is refused and not merely a delete.</b> A journey
 * denormalises {@code service_name} at instantiation and
 * {@code ObJourneyRepository} resolves a client's live journey for a service
 * by {@code (product, service name)} rather than by template id — deliberately,
 * so a service-level dependency survives the dependency publishing a new
 * version. Renaming the chain underneath a live journey would leave that
 * lookup finding nothing: the dependent journey would start unheld, the
 * uniqueness guard would stop recognising the client's existing journey, and a
 * second one for the "new" service could be instantiated alongside it. The
 * name is a key, not a label.
 *
 * <p>{@code 409} rather than {@code 422}: the row exists and the request is
 * well formed, but the resource is not in a state that accepts it —
 * {@code CONVENTIONS.md} §3's line for exactly this.
 */
class ModuleServiceInUseException extends RuntimeException {

    private final long journeyCount;

    ModuleServiceInUseException(String serviceName, long journeyCount) {
        super("\"" + serviceName + "\" cannot be edited or deleted — " + journeyCount
                + " client journey" + (journeyCount == 1 ? " has" : "s have")
                + " already been instantiated from it. Publish a new version instead.");
        this.journeyCount = journeyCount;
    }

    long journeyCount() {
        return journeyCount;
    }
}
