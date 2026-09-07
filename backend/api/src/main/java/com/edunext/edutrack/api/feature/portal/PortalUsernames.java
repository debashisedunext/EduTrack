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
