package com.edunext.edutrack.worker.journal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * A-044 · which tickets a run covers.
 *
 * <p>Ids only, in one query. A ticket id is eight bytes, so even A-073's
 * 50,000-ticket target is a list measured in hundreds of kilobytes — cheaper to
 * hold than to page, and paging would need a cursor stable against inserts
 * happening while the run walks.
 *
 * <p>Ordered so a run is reproducible: two runs over the same data cover the
 * same tickets in the same order, which is what makes "it failed on the same
 * ticket again" a usable observation.
 */
@Repository
class ChainScanRepository {

    private final JdbcClient jdbc;

    ChainScanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every ticket, including closed ones.
     *
     * <p>Closed tickets are the <em>point</em>. A ticket still being worked is
     * read constantly and its history is in front of people; one closed last
     * March is where an altered row would sit unnoticed, and it is the only
     * place a chain can be quietly rewritten without anybody's screen
     * disagreeing.
     */
    List<Long> allTicketIds() {
        return jdbc.sql("SELECT id FROM tickets ORDER BY id")
                .query(Long.class)
                .list();
    }
}
