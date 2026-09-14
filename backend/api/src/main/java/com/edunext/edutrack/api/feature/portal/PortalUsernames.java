package com.edunext.edutrack.api.feature.portal;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * A-125 · generates the login name for a portal account.
 *
 * <h2>Generated, never chosen</h2>
 *
 * <p>The backlog says "generated username" and it is worth recording why,
 * because "let the client pick one" is the friendlier-sounding option.
 *
 * <p>A chosen username is a value an external party controls that we then look
 * up. It has to be validated for length, charset, homoglyphs and impersonation
 * — {@code acme-admin} typed by somebody at a different client is a support
 * call at best. Generating from the client's own code makes the namespace ours,
 * makes every name self-describing in a log line, and removes the entire class
 * of question.
 *
 * <p>It is also the only shape that works for the credential mail. The account
 * is created by staff in the OB-04 wizard, before the client has ever touched
 * the system, so there is nobody to ask.
 *
 * <h2>The shape</h2>
 *
 * <pre>
 *   ACME.ravi          client code, a dot, the contact's given name
 *   ACME.ravi2         …and a counter when that is taken
 * </pre>
 *
 * <p>The client code leads because that is how support reads it: the first
 * thing anybody needs to know about a login is whose it is. It is already
 * unique and already upper-case ({@code ClientCodeFormat}), so it carries the
 * uniqueness and the name only has to disambiguate within one client.
 *
 * <p><b>The counter is not a security measure and is not pretending to be
 * one.</b> Portal usernames are enumerable by design — anyone who knows a
 * client code can guess them — which is precisely why
 * {@code ClientAccountRepository} refuses to filter {@code is_active} in SQL
 * and why the lockout matters more here than on staff logins. Guessing the name
 * is expected; getting past the password is the part that has to be hard.
 */
final class PortalUsernames {

    /**
     * Long enough for a real name after a client code, short enough that the
     * column ({@code VARCHAR(64)}) can never refuse what this builds.
     */
    private static final int MAX_LOCAL_PART = 24;

    /**
     * A whole client code, kept whole.
     *
     * <p>{@code ob_clients.client_code} is {@code VARCHAR(32)} and the code
     * path appends nothing but a rare counter, so 32 is what fits without ever
     * truncating a real code — and it still leaves room inside
     * {@code client_accounts.username}'s {@code VARCHAR(50)}. Reusing
     * {@link #MAX_LOCAL_PART} here cut a 32-character code down to 24, which is
     * precisely the "the username is not the code" failure this path exists to
     * avoid.
     */
    private static final int MAX_CODE = 32;

    /**
     * Where the counter stops. Twenty-five people called Ravi at one client is
     * not a naming problem any more, and a loop with no bound is how a unique
     * constraint turns into a hang.
     */
    private static final int MAX_ATTEMPTS = 25;

    private PortalUsernames() {
    }

    /**
     * A username for this contact at this client, avoiding anything
     * {@code taken} says already exists.
     *
     * @param clientCode the client master's code — {@code ACME}. Required.
     * @param displayName the contact's name. May be anything a human typed; the
     *                    given name is taken from it and everything outside
     *                    {@code [a-z0-9]} is dropped, so accents, titles and
     *                    double-barrelled surnames all reduce to something
     *                    typable. A name that reduces to nothing at all — it
     *                    was entirely non-Latin, or was blank — falls back to
     *                    {@code user}, which is honest rather than clever: it
     *                    still disambiguates by counter and it does not
     *                    transliterate somebody's name into something they
     *                    would not recognise.
     * @param taken       true if a candidate is already in use.
     * @throws IllegalStateException if {@link #MAX_ATTEMPTS} candidates are all
     *                    taken. Deliberately not a silent random suffix: at that
     *                    point something is wrong with the caller's assumptions
     *                    and inventing {@code ACME.ravi7f3a} would hide it.
     */
    /**
     * The client's own code, and nothing after it — the onboarding module's
     * shape.
     *
     * <pre>
     *   HRZ-001           the client code, as the operator filed it
     *   HRZ-0012          …and a counter on the rare occasion that is taken
     * </pre>
     *
     * <h2>Why this is the better name where a code exists</h2>
     *
     * <p>{@link #generate} appends the contact's given name because the
     * ticketing master's login is a <em>person at</em> a client and several of
     * them may exist. An onboarding portal login is not: {@code
     * uq_client_accounts_ob_client} allows exactly one per client, so there is
     * never a second one to tell apart, and the given name was disambiguating
     * nothing. What it did do was make the login name depend on which SPOC
     * happened to be primary the day it was issued — so the client's own code,
     * the value operations already file them under and already quote on the
     * phone, is both more stable and more recognisable.
     *
     * <p><b>The counter stays anyway.</b> {@code uq_ob_clients_client_code}
     * makes the code unique among onboarding clients, but {@code
     * client_accounts} is one table and the ticketing master mints names into
     * it from its own separate code column. Two organisations filed as
     * {@code ACME} in the two masters are not a contradiction anybody has to
     * resolve — V20260905_1630 is explicit that nothing links them — and
     * without the counter the second one to ask for a login would fail on a
     * unique constraint instead of getting {@code ACME2}.
     *
     * <p>Reduced to {@code [A-Z0-9._-]} rather than passed through: the code is
     * free text up to 32 characters and a space or a slash in a login name is a
     * support call. A code that reduces to nothing falls back to
     * {@code CLIENT}, on the same reasoning as {@link #localPart}'s
     * {@code user}.
     *
     * @param clientCode the client's code. Required — callers with none use
     *                   {@link #generate}.
     * @param taken      true if a candidate is already in use.
     */
    static String fromClientCode(String clientCode, Predicate<String> taken) {
        String base = reduceCode(requireCodeAsTyped(clientCode));

        if (!taken.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_ATTEMPTS; suffix++) {
            String candidate = base + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "no free portal username for " + base + " after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * <p>Hyphens, dots and underscores survive because real codes are written
     * with them — {@code HRZ-001} is the shape operations actually use, and
     * stripping it to {@code HRZ001} would mean the login name and the code on
     * the client page did not match, which is the one property this change
     * exists to give them.
     */
    private static String reduceCode(String clientCode) {
        // `a-z` included deliberately: the case the operator typed is kept —
        // see requireCodeAsTyped — so folding it out here would delete the
        // lower-case half of every code rather than preserve it.
        String reduced = clientCode.replaceAll("[^A-Za-z0-9._-]", "");
        if (reduced.isEmpty()) {
            return "CLIENT";
        }
        return reduced.length() > MAX_CODE ? reduced.substring(0, MAX_CODE) : reduced;
    }

    static String generate(String clientCode, String displayName, Predicate<String> taken) {
        String prefix = requireCode(clientCode);
        String local = localPart(displayName);

        String first = prefix + "." + local;
        if (!taken.test(first)) {
            return first;
        }
        // Starts at 2, so the second Ravi is `ravi2`. A `ravi1` beside a bare
        // `ravi` reads as two different people to everyone except the person
        // who wrote the loop.
        for (int suffix = 2; suffix <= MAX_ATTEMPTS; suffix++) {
            String candidate = prefix + "." + local + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "no free portal username for " + prefix + "." + local
                        + " after " + MAX_ATTEMPTS + " attempts");
    }

    private static String requireCode(String clientCode) {
        if (clientCode == null || clientCode.isBlank()) {
            throw new IllegalArgumentException("a portal username needs a client code");
        }
        return clientCode.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The code as the operator typed it, trimmed and not case-folded.
     *
     * <p>{@link #requireCode} upper-cases because the ticketing master's
     * {@code ClientCodeFormat} has already done so and the call is a no-op
     * there. An onboarding {@code client_code} is free text, so the same call
     * is <em>not</em> a no-op: "IT-fb5f3d24" became "IT-FB5F3D24", and a
     * username that is a case-folded version of the code is not the code. The
     * whole point of naming the login after it is that the two read as one
     * string on a support call.
     *
     * <p>Nothing is lost by keeping the case. Uniqueness still holds —
     * {@code client_accounts} collates {@code utf8mb4_0900_ai_ci}, so
     * {@code usernameExists} and {@code uq_client_accounts_username} are both
     * case-insensitive — and {@code PortalAuthService} matches the login
     * case-insensitively too, so a client who types it in caps still gets in.
     */
    private static String requireCodeAsTyped(String clientCode) {
        if (clientCode == null || clientCode.isBlank()) {
            throw new IllegalArgumentException("a portal username needs a client code");
        }
        return clientCode.trim();
    }

    /**
     * The given name, lower-cased and reduced to {@code [a-z0-9]}.
     *
     * <p>Given name rather than surname because it is what the client will read
     * back to support on the phone, and because a surname is likelier to be the
     * part that collides within one organisation.
     */
    private static String localPart(String displayName) {
        String source = displayName == null ? "" : displayName.trim();
        String given = source.isEmpty() ? "" : source.split("\\s+")[0];
        String reduced = given.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (reduced.isEmpty()) {
            return "user";
        }
        return reduced.length() > MAX_LOCAL_PART ? reduced.substring(0, MAX_LOCAL_PART) : reduced;
    }
}
