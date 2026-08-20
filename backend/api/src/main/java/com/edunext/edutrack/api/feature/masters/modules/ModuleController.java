package com.edunext.edutrack.api.feature.masters.modules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B-064 · §7.3's module master, per {@code contracts/openapi.yaml}.
 *
 * <h2>The seventh "declared, mocked, never mounted", and the worst-timed one</h2>
 *
 * <p>{@code listModules} has been in the contract, in the MSW mock and in the
 * generated TypeScript client since D-060 with <b>no controller anywhere in the
 * backend</b> — after B-023's nine calendar operations, B-014's
 * {@code PATCH /users/{userId}/status}, B-018's two SLA operations, B-020's
 * {@code listTaskTypes}, B-021's {@code listPriorities} and B-025's six client
 * operations.
 *
 * <p>What makes this one different from the six before it is how much had
 * already been built on top. Three of Stream C's shipped screens call this
 * route: {@code CreateTicketPage}'s module picker (C-068), S-20's "Where it
 * happened" group and its inline editor (C-069), and S-17's module filter and
 * grid column (C-070). All three are green, and all three would have 404'd on
 * first contact with a real backend — {@code moduleName()} would return
 * {@code undefined} for every ticket in the product and every cell would render
 * an em dash, which reads as "no module was recorded" rather than as an
 * unmounted route. {@code MasterRoutesTest} pins this mount point the way it
 * pins the other six.
 *
 * <h2>Permissions</h2>
 *
 * <p><b>All six roles</b>, and this is §2 row 3's argument rather than
 * convenience — the same one task types, levels, roles and the calendar are
 * open on. Every role may raise a ticket; §7.5 puts the Module field on the
 * create form; a role that could not list modules could not fill it in. There
 * is nothing sensitive in eight product-area names, and the contract declares
 * only 200 and 401 for exactly that reason — no 403 is reachable, so there is
 * no {@code ROWLESS_403} entry to add.
 *
 * <h2>One route, no detail read and no writes</h2>
 *
 * <p>The sibling masters carry a {@code GET /{id}} that exists only to emit the
 * {@code ETag} their {@code PATCH} requires as {@code If-Match}
 * (CONVENTIONS.md §5). There is no {@code PATCH} here, so a detail read would
 * be an operation with no caller and no purpose — B-011 added one to serve a
 * write, not as a pattern to copy.
 *
 * <p>{@code /masters/modules} is exempt from §6's cursor rule in
 * {@code check-conventions.py} — eight seeded rows, and a ninth is a row
 * somebody adds.
 *
 * <p>The {@code /api/v1} prefix is spelled out. Nothing declares it globally,
 * which is the mistake B-023 shipped and {@code MasterRoutesTest} exists for.
 */
@RestController
@RequestMapping("/api/v1/masters")
@Tag(name = "masters")
class ModuleController {

    private final ModuleService service;

    ModuleController(ModuleService service) {
        this.service = service;
    }

    @GetMapping(path = "/modules", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listModules", summary = "Product modules a concern can be raised against (§7.5)")
    ModuleDtos.ModuleListResponse modules() {
        return new ModuleDtos.ModuleListResponse(service.list());
    }
}
