package com.edunext.edutrack.api.feature.masters.modules;

import com.edunext.edutrack.domain.masters.ProductModule;
import com.edunext.edutrack.domain.masters.ProductModuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * B-064 · blueprint §7.3's module master, served read-only.
 *
 * <h2>Why this is a master and not an enum</h2>
 *
 * <p>PLAN.md §3.9 states it without qualification — "nothing in Java may
 * hard-code the list". The eight rows are reference data seeded by C-065's
 * migration, and the ninth is a row somebody inserts rather than a migration
 * and a deployment. A Java enum here would put a release between an
 * organisation and a product area it has started supporting, which is exactly
 * what the client asked not to have.
 *
 * <h2>Every row, retired ones included</h2>
 *
 * <p>The contract says so and the backlog says so, and the reason is the one
 * behaviour a fixture of only-active rows cannot distinguish: <b>a picker
 * offers the active modules, a grid renders the name of whichever module a
 * ticket was actually raised against.</b> Filter here and the second caller
 * gets a blank cell — which reads as missing data rather than as a retirement,
 * and is the kind of bug that ships because nothing looks wrong.
 *
 * <p>So there is no {@code includeInactive} parameter, deliberately, and this
 * is the same call {@link
 * com.edunext.edutrack.api.feature.masters.tasktypes.TaskTypeService} made and
 * the opposite of the one {@code PriorityService} made. The priorities departed
 * because their two consumers could not filter — {@code CreateTicketPage} maps
 * that list straight into {@code LevelPicker}. This master's consumers already
 * do: {@code whereItHappened.ts} has separate functions for the name lookup and
 * for what the editor may offer, and the create form drops retired rows flatly.
 * The narrower question is not askable through this route because no caller
 * here is entitled to it, and {@code ProductModuleRepository} deliberately
 * carries no query that would answer it.
 *
 * <h2>There is no write path, and that is the task rather than an omission</h2>
 *
 * <p>B-064 is a read endpoint over reference data. No admin CRUD screen exists
 * because the client asked for a fixed list; the table exists so that changing
 * it later is a row rather than a release. If a Module Master screen is wanted
 * it is a new task on the S-11/S-12 pattern — a controller with an ETag, a
 * {@code POST}, a {@code PATCH} that retires rather than deletes, and a
 * {@code ticketCount} to make the retire decision informed — not a widening of
 * this one.
 *
 * <p><b>The write-side rule already exists elsewhere and is not duplicated
 * here.</b> {@code ModuleGuard} in {@code feature/tickets} (C-067) refuses a
 * deactivated module when a ticket is written, with a 400 keyed on
 * {@code moduleId}. That is the correct place for it: the rule is about the
 * ticket write, not about the master read. Worth noting for whoever picks up
 * the admin screen — it reaches {@code product_modules} through a raw
 * {@code JdbcClient} rather than through this repository, which is Stream C's
 * file and Stream C's call to make.
 */
@Service
public class ModuleService {

    private final ProductModuleRepository modules;

    ModuleService(ProductModuleRepository modules) {
        this.modules = modules;
    }

    /**
     * The whole master in {@code seq} order, {@code id} breaking the tie.
     *
     * <p>{@code readOnly = true} on a single-statement read is not ceremony
     * here: it keeps Hibernate from taking snapshots of eight entities it will
     * never dirty-check, on a route every ticket screen in the product calls.
     */
    @Transactional(readOnly = true)
    public List<ModuleDtos.ModuleView> list() {
        return modules.findAllByOrderBySeqAscIdAsc().stream().map(ModuleService::toView).toList();
    }

    private static ModuleDtos.ModuleView toView(ProductModule module) {
        return new ModuleDtos.ModuleView(
                module.getId(),
                module.getCode(),
                module.getName(),
                module.getSeq(),
                module.isActive());
    }
}
