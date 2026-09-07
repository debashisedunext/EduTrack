package com.edunext.edutrack.api.feature.onboarding.reports;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * B-122 · which controls OB-10's viewer draws for a given report. Mirrors
 * {@code ObReportFilterKind} in the contract (A-118).
 *
 * <p>{@code ReportFilterKind}'s argument, on the module's own five: each
 * descriptor names the filters its runner actually honours, and the viewer
 * renders exactly those. A control the runner would ignore is never drawn,
 * because the user sets it, nothing changes, and the only conclusion available
 * to them is that the screen is broken.
 *
 * <h2>The token names the control, not the query parameter</h2>
 *
 * <p>{@code product} rather than {@code productId}, {@code client} rather than
 * {@code obClientId}, {@code owner} rather than {@code ownerUserId} — A-118's
 * spelling, and it is deliberate rather than shorthand. {@code dateRange} is
 * one control that sets <em>two</em> parameters, so a vocabulary named after
 * parameters could not have expressed it at all; naming all five after controls
 * keeps one rule. The mapping from a control to the parameters it sends is the
 * viewer's, which is the only place that knows what a control looks like.
 *
 * <p><b>The wire spelling is camelCase and the Java spelling is not.</b> This
 * is one of the few lower-camel enums in the contract, because it is a
 * client-side rendering token rather than a domain value. {@link #wire()} is
 * therefore explicit per constant rather than a {@code toLowerCase()} of the
 * name, which would emit {@code date_range} or {@code daterange} and quietly
 * stop matching the generated client's union.
 */
enum ObReportFilterKind {

    /** {@code ?from=} and {@code ?to=} — one control, two parameters. */
    DATE_RANGE("dateRange"),

    /** {@code ?productId=} — one of §4's purchased products. */
    PRODUCT("product"),

    /** {@code ?obClientId=} — one boarding client. */
    CLIENT("client"),

    /**
     * {@code ?ownerUserId=} — the implementor a report is about.
     *
     * <p>Present on a descriptor does not mean freely settable. For an
     * OB_STEP_OWNER the value is <b>ignored rather than refused</b>, which is
     * {@code runObReport}'s own ruling: answering it would let one implementor
     * read a colleague's scorecard by guessing a user id, and a 403 would
     * wrongly imply a grant exists that could be given. See
     * {@link ObReportScope#ownerSubject}.
     */
    OWNER("owner"),

    /** {@code ?rag=} — GREEN / AMBER / RED, per {@code ObRag}. */
    RAG("rag");

    private final String wire;

    ObReportFilterKind(String wire) {
        this.wire = wire;
    }

    /** The contract's spelling, and what the generated client's union contains. */
    @JsonValue
    String wire() {
        return wire;
    }
}
