package com.edunext.edutrack.api.feature.clients;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * B-026 · the {@code clients.support_plan} vocabulary.
 *
 * <p><b>The union of two sources that disagreed.</b> Blueprint §4B.2's
 * Commercial group names <i>Standard / Premium / Enterprise</i>; A-006's column
 * comment names {@code BASIC|STANDARD|PREMIUM|…}; and
 * {@code ReferenceDataFixture} has eight seeded clients across
 * {@code BASIC}/{@code STANDARD}/{@code PREMIUM}. Taking the blueprint's three
 * literally would leave two seeded clients on a plan the form cannot render and
 * the service would refuse on the next save of an unrelated field. So the set is
 * all four, and {@code BASIC} is in it because clients are already on it.
 *
 * <p><b>Uppercase, because that is what is stored.</b> The fixture and every
 * server write use upper case; only the MSW mock had ever written
 * {@code 'Premium'}, which B-026 corrects — a {@code Select} bound to these
 * codes renders nothing selected against title-case data, and the bug appears
 * only in {@code npm run dev}. B-025's grid filter matches case-insensitively
 * and is unaffected either way.
 *
 * <h2>No {@code CHECK} constraint, deliberately</h2>
 *
 * <p>{@code ck_clients_status} exists one column over and this has no
 * counterpart, which looks inconsistent and is not. The status vocabulary is
 * closed by the product — §4B.2 names three states and there is no fourth thing
 * a client can be. A support plan is a commercial package an organisation
 * invents, B-035's Excel import is a second writer of the column, and B-011
 * established that a MySQL {@code CHECK} violation arrives as
 * {@code UncategorizedSQLException} — a 500 with no field name in it. An import
 * row rejected for a bad plan has to say which cell was wrong, which is a
 * service-layer refusal or nothing.
 */
enum ClientSupportPlan {

    BASIC,
    STANDARD,
    PREMIUM,
    ENTERPRISE;

    static final List<String> CODES =
            Arrays.stream(values()).map(Enum::name).toList();

    /** Case-insensitively, and empty for anything not in the set. */
    static Optional<ClientSupportPlan> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(p -> p.name().equals(raw.trim().toUpperCase(Locale.ROOT)))
                .findFirst();
    }
}
