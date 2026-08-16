package com.edunext.edutrack.api.feature.clients;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * B-026 · the {@code clients.status} vocabulary — blueprint §4B.2's Identity
 * group: <b>Active / Inactive / Prospect</b>.
 *
 * <p>One statement of the set, for the reason {@code ProjectRoles} exists one
 * feature over (B-017) and {@code PasswordComplexity} exists in auth (B-013):
 * nothing re-checks a {@code @Pattern} against a database {@code CHECK}, so a
 * fourth value added to one copy and not the other would not fail a request, a
 * save or a build — it would mean one screen accepting a status another refuses,
 * discovered by somebody asking why. The three places that have to agree are
 * this enum, {@code ck_clients_status} and the contract's {@code ClientStatus},
 * and {@code ClientStatusTest} is the seam between the first two.
 *
 * <h2>{@link #isActive()} is the decision worth reading</h2>
 *
 * <p>B-025 derived the wire's {@code isActive} as {@code status = 'ACTIVE'},
 * which was exact while the column carried two values. Adding a third makes that
 * reading a behaviour change nobody asked for: blueprint §4B.2 puts a client
 * dropdown on the ticket create form <b>filtered on that boolean</b>, so every
 * Prospect would have vanished from the form the moment this enum shipped —
 * silently, with nothing on screen saying why, and looking exactly like a
 * dropdown that had lost its data.
 *
 * <p>So the projection is <b>"not INACTIVE"</b>, not "is ACTIVE". A prospect is
 * somebody you are talking to; raising a pre-sales ticket against them is a
 * normal thing to want, and the blueprint reserves the words "blocks new
 * tickets" for deactivation alone. This is the same call B-016 made on
 * {@code Project.isActive} — {@code status <> 'CLOSED'}, so that putting a
 * project on hold does not delete it from five pickers.
 *
 * <p><b>The consequence lands on the status setter</b>, and it is the half that
 * would have been easy to miss. {@code PATCH /clients/{id}/status} takes a
 * boolean; with this projection, {@code isActive: true} against a Prospect is
 * asking for a state it already holds. Writing {@code ACTIVE} anyway would let
 * S-32's bulk Activate promote a shortlist of prospects into contracted clients
 * — a commercial fact, changed by a checkbox, with no record that it happened.
 * {@link #activatedFrom} returns the status unchanged in that case;
 * {@code ClientServiceTest.activatingAProspectLeavesItAProspect} pins it.
 */
enum ClientStatus {

    ACTIVE,
    INACTIVE,
    PROSPECT;

    /** The wire vocabulary, for a problem document that has to name the options. */
    static final List<String> CODES =
            Arrays.stream(values()).map(Enum::name).toList();

    /**
     * Whether this status counts as active on the wire.
     *
     * <p>Not {@code this == ACTIVE}. See the class javadoc — the narrow reading
     * removes every prospect from §4B.2's ticket-form dropdown.
     */
    boolean isActive() {
        return this != INACTIVE;
    }

    /**
     * Case-insensitively, and empty for anything not in the set.
     *
     * <p>An unknown value is never coerced to a default: a client stored with a
     * status this enum does not know is a database somebody edited by hand, and
     * reading it as ACTIVE would hide that rather than surface it.
     */
    static Optional<ClientStatus> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(s -> s.name().equals(raw.trim().toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    /**
     * What a stored status becomes on the wire when the row is unreadable.
     *
     * <p>{@code ClientQueryRepository} reads whatever the column holds, and the
     * column is a {@code VARCHAR} that predates {@code ck_clients_status} — a row
     * written before it, or by a hand-run {@code UPDATE}, can carry anything.
     * Treated as inactive rather than active: the safe direction for a value
     * nobody can interpret is the one that does not put a client in front of a
     * ticket form.
     */
    static boolean isActive(String stored) {
        return parse(stored).map(ClientStatus::isActive).orElse(false);
    }

    /**
     * The status a client should hold after {@code PATCH .../status} with the
     * given boolean — the whole rule in one place, so the single and bulk
     * setters cannot drift apart.
     *
     * <p>Deactivating always writes {@code INACTIVE}. Activating writes
     * {@code ACTIVE} <b>only when the client is not already active by
     * {@link #isActive()}</b>, which is what keeps a bulk Activate from
     * converting prospects. Reactivating an {@code INACTIVE} client lands on
     * {@code ACTIVE} rather than back on whatever it was before, because nothing
     * records what it was before — and inventing a history here would be worse
     * than the one honest state.
     */
    static String activatedFrom(String stored, boolean isActive) {
        if (!isActive) {
            return INACTIVE.name();
        }
        return isActive(stored) ? parse(stored).orElseThrow().name() : ACTIVE.name();
    }
}
