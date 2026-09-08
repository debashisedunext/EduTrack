package com.edunext.edutrack.api.feature.onboarding.clients;

import jakarta.validation.constraints.Size;

/**
 * B-106 · {@code PATCH /onboarding/clients/{obClientId}/requirements/{id}}'s
 * body — OB-05's requirements list, editing one row.
 *
 * <h2>Why this is not {@code ObRequirementWriteRequest}</h2>
 *
 * <p>The purchases panel one card over uses one record for its create and its
 * edit, and says why: "a purchase is the same five fields whether it is made at
 * boarding or six months later". That argument does not carry here, and the
 * field it breaks on is {@code isMet}.
 *
 * <p>A full-representation PATCH re-asserts every field on every save. On a
 * purchase that is harmless — re-asserting a seat count of 40 while somebody
 * else also set it to 40 changes nothing. {@code isMet} is not that kind of
 * field: somebody correcting a typo in a requirement's wording would also
 * re-assert that it is met, and if a colleague had just reopened it in the
 * meantime, the correction would close it again with nobody having asked.
 * {@link ObClientUpdateRequest} refused a full representation for exactly this
 * reason about {@code status}, in this package, three tasks ago.
 *
 * <p>So presence is tracked per field, and the same way: a setter that records
 * it was called. Verbose, and the verbosity stays confined to the two classes
 * in this package that need it.
 *
 * <h2>The setters are {@code public} inside a package-private class</h2>
 *
 * <p>Not a slip — the trap {@code ObClientUpdateRequest} documents and
 * {@code ObClientUpdateRequestBindingTest} pins. Jackson's default setter
 * visibility is {@code PUBLIC_ONLY}, so package-private setters are never
 * discovered: every field would arrive null, no exception would be thrown, and
 * the PATCH would answer 200 having changed nothing. Nothing else in the build
 * notices that, which is why {@code ObRequirementUpdateRequestBindingTest}
 * deserialises real JSON against this class too rather than trusting that the
 * lesson stuck.
 *
 * <h2>What is absent, and deliberately</h2>
 *
 * <p>No {@code metAt} and no {@code metBy}. Both are stamped by the service
 * from the server clock and the authenticated caller, because a body that could
 * supply them could backdate the evidence — and the evidence is the entire
 * reason {@code is_met} is three columns rather than one. No {@code sequence}
 * either: reordering the list is a rewrite of the set rather than an edit of
 * one row, and the migration's own note says so.
 */
class ObRequirementUpdateRequest {

    private String title;
    private boolean titleSet;

    private String bodyHtml;
    private boolean bodyHtmlSet;

    private Boolean isMet;
    private boolean isMetSet;

    @Size(max = 200)
    String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.titleSet = true;
    }

    /**
     * An explicit {@code "title": null} <b>does</b> clear the label, unlike
     * {@code status} on {@link ObClientUpdateRequest}.
     *
     * <p>The difference is the column. {@code overall_status} is {@code NOT
     * NULL} with no empty value, so a null there could only have meant "leave it
     * alone" — which is what omitting it already meant. {@code title} is
     * null-able by design: a requirement typed into the wizard's textarea has no
     * label, so "no label" is a state the row can genuinely be in and a caller
     * needs a way to return it to one.
     */
    boolean hasTitle() {
        return titleSet;
    }

    @Size(max = 20_000)
    String getBodyHtml() {
        return bodyHtml;
    }

    public void setBodyHtml(String bodyHtml) {
        this.bodyHtml = bodyHtml;
        this.bodyHtmlSet = true;
    }

    /**
     * An explicit {@code "bodyHtml": null} counts as absent.
     *
     * <p>{@code body_html} is {@code NOT NULL} and a requirement with no body is
     * not a requirement — it is a row on a checklist that says nothing and that
     * somebody still has to work through. Clearing one is deleting it, and
     * {@code DELETE} is the operation for that. A null here therefore has no
     * meaning other than the one an omitted field already carries.
     */
    boolean hasBodyHtml() {
        return bodyHtmlSet && bodyHtml != null;
    }

    Boolean getIsMet() {
        return isMet;
    }

    public void setIsMet(Boolean isMet) {
        this.isMet = isMet;
        this.isMetSet = true;
    }

    /** An explicit {@code "isMet": null} counts as absent — {@code is_met} is {@code NOT NULL}. */
    boolean hasIsMet() {
        return isMetSet && isMet != null;
    }

    boolean met() {
        return Boolean.TRUE.equals(isMet);
    }
}
