package com.edunext.edutrack.api.feature.tickets;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * C-067 · resolves {@code moduleId} against the master and refuses a
 * deactivated one on write.
 *
 * <p><b>Rejected on write, still readable on old tickets</b> — the backlog's own
 * wording, and the contract's: {@code GET /masters/modules} returns deactivated
 * rows carrying {@code isActive: false} precisely so a ticket raised last year
 * against a since-retired module still renders its name. Filtering them out of
 * the read is what would leave that cell blank; refusing them on the write is
 * what stops the list growing new tickets against a module nobody maintains.
 *
 * <p>Both answers are <b>400, not 404</b>. The module master is not row-scoped —
 * every role may read all eight — so there is no existence to leak and nothing
 * for a 404 to protect. What is refused is the combination, and a caller who
 * cannot tell "no such module" from "not offered any more" cannot act on either.
 */
@Component
class ModuleGuard {

    private final JdbcClient jdbc;

    ModuleGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws UnknownModuleException when the id names nothing, or names a deactivated row. */
    void requireActive(Integer moduleId) {
        if (moduleId == null) {
            return;
        }
        Boolean active = jdbc.sql("SELECT is_active FROM product_modules WHERE id = :id")
                .param("id", moduleId)
                .query(Boolean.class)
                .optional()
                .orElseThrow(() -> UnknownModuleException.noSuchModule(moduleId));

        if (!active) {
            throw UnknownModuleException.deactivated(moduleId);
        }
    }
}
