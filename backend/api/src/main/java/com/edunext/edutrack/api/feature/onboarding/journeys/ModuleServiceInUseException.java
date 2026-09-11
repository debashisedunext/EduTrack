package com.edunext.edutrack.api.feature.onboarding.journeys;

/**
 * C-124 · <b>a Module Service a client is already on cannot be deleted.</b>
 *
 * <p>Checked against the whole version chain rather than against one row.
 * {@code ObJourney.templateId} pins the version a client was boarded on and
 * never moves, so a client sitting on v1 while the catalogue shows v3 is still
 * a client on this service — asking only about the head would report it as
 * unused and let the delete through.
 *
 * <p><b>Editing is no longer one of these, in either field.</b> Renaming and
 * re-filing under another product were both refused here once, on the grounds
 * that {@code ob_journeys} denormalises {@code service_name} and
 * {@code product_id} at instantiation and that two lookups resolve a service by
 * the first of them. All of that is still true of those columns; none of it was
 * ever an argument for freezing a catalogue entry. A rename now travels to the
 * journeys in the same transaction, and a product move deliberately does not —
 * a journey's product records the client's purchase.
 * {@code ObJourneyTemplateService#updateModuleService} has both halves.
 *
 * <p><b>Deletion is different in kind, and this is not a policy that could be
 * relaxed the same way.</b> {@code fk_ob_journeys_template} is RESTRICT and a
 * journey renders its steps from these very rows — there is no version of
 * "delete it anyway" that does not either fail at the foreign key or empty a
 * running client's ribbon. Publishing a new version over the service is how a
 * service in use stops being offered.
 *
 * <p>{@code 409} rather than {@code 422}: the row exists and the request is
 * well formed, but the resource is not in a state that accepts it —
 * {@code CONVENTIONS.md} §3's line for exactly this.
 */
class ModuleServiceInUseException extends RuntimeException {

    private final long journeyCount;

    ModuleServiceInUseException(String serviceName, long journeyCount) {
        super("\"" + serviceName + "\" cannot be deleted — " + journeyCount
                + " client journey" + (journeyCount == 1 ? " has" : "s have")
                + " already been instantiated from it. Retire it by publishing over it instead.");
        this.journeyCount = journeyCount;
    }

    long journeyCount() {
        return journeyCount;
    }
}
