package com.edunext.edutrack.api.feature.onboarding.prereqs;

import com.edunext.edutrack.domain.onboarding.ObGateStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * B-125 · reads the gate a client's journeys already carry.
 *
 * <p>{@code gateStatus} on the prerequisites response is <b>the same value
 * every journey on this client carries</b>, repeated so the OB-05 strip can
 * render without reading a journey — never a third place the truth is kept.
 * This class is what makes "repeated" true rather than "recomputed": it reads
 * {@code ob_journeys.gate_status} instead of deriving a second answer from
 * the task rows.
 *
 * <p><b>A one-row question through {@code JdbcClient}, not a repository over
 * another feature's aggregate</b> — {@code PurchasedProductAccess}'s own
 * precedent (C-103), which reaches {@code ob_client_applications} the same
 * way and for the same reason. A full JPA repository over {@code ob_journeys}
 * here would give this package a second mapping of Stream C's table.
 *
 * <p>A client with no journeys at all answers {@code LOCKED}. That is the
 * honest reading of "nothing has been released": plan §5.2 makes
 * {@code LOCKED} the state in which no step activates and no clock runs, and
 * a client with nothing to run is in exactly that state. It also means the
 * strip renders identically before and after the first product is boarded,
 * which is what OB-03's "Prerequisites pending" filter expects.
 */
@Repository
class ObJourneyGateReader {

    private final JdbcClient jdbc;

    ObJourneyGateReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The gate every journey on this client shares.
     *
     * <p>{@code MIN} over the enum's own text is not what decides this — the
     * query asks whether <em>any</em> journey is still {@code LOCKED},
     * because plan §5.3 opens them all at once and a client mid-flip is a
     * state that does not commit. One locked journey means the gate has not
     * opened; none means it has.
     */
    ObGateStatus gateStatusOf(long obClientId) {
        Optional<Long> lockedCount = jdbc.sql("""
                        SELECT COUNT(*)
                          FROM ob_journeys
                         WHERE ob_client_id = :clientId
                           AND gate_status = 'LOCKED'
                        """)
                .param("clientId", obClientId)
                .query(Long.class)
                .optional();

        Optional<Long> total = jdbc.sql("SELECT COUNT(*) FROM ob_journeys WHERE ob_client_id = :clientId")
                .param("clientId", obClientId)
                .query(Long.class)
                .optional();

        if (total.orElse(0L) == 0L) {
            return ObGateStatus.LOCKED;
        }
        return lockedCount.orElse(0L) > 0 ? ObGateStatus.LOCKED : ObGateStatus.OPEN;
    }
}
