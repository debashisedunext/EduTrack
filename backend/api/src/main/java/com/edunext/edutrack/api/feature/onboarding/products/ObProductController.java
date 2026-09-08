package com.edunext.edutrack.api.feature.onboarding.products;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

/**
 * A-124 · OB-07's product catalogue.
 *
 * <h2>The contract has declared these four operations since A-118</h2>
 *
 * <p>{@code listObProducts}, {@code createObProduct}, {@code getObProduct} and
 * {@code updateObProduct} were written into {@code openapi.yaml} ahead of the
 * screens, with the MSW mock behind them. Nothing served them: a route dump of
 * the running application listed twenty-six onboarding routes and none of these.
 * This is the implementation of a shape already agreed, not a new design.
 *
 * <h2>Not paginated, and that is an exemption on the record</h2>
 *
 * <p>{@code check-conventions.py} carries it, on the {@code /masters/task-types}
 * argument: this is a catalogue of the things the organisation sells, and an
 * organisation with enough products to page has a problem a page control does
 * not solve.
 *
 * <h2>Authorisation is {@code isAuthenticated()}, deliberately</h2>
 *
 * <p>The contract says OB Admin for the writes, and that is a <em>module</em>
 * role — {@code user_module_access.module_role} — which {@code @PreAuthorize}
 * does not speak; it speaks blueprint §2's six platform roles. A-111's gate
 * already answers 404 to anybody without the ONBOARDING module, and A-114's
 * matrix declares OB Admin for these very routes with the honest note that the
 * application enforces one of its twenty-six rules today.
 *
 * <p>So: no invented platform-role restriction here, because encoding one would
 * assert a rule nobody has decided. These routes are entered in
 * {@code ObPermissionMatrix} as {@code ADMIN_ONLY} against the module
 * vocabulary, and <b>A-122 is the task that makes that bite</b>. Writing a
 * bespoke check here instead would be a second enforcement point beside the one
 * A-122 is going to build, which is how two rules over one invariant start
 * disagreeing.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding-masters")
@PreAuthorize("isAuthenticated()")
class ObProductController {

    private final ObProductService service;

    ObProductController(ObProductService service) {
        this.service = service;
    }

    @GetMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listObProducts", summary = "The product catalogue (OB-07)")
    ObProductDtos.ObProductListResponse list(
            @RequestParam(name = "isActive", required = false) Boolean isActive) {

        List<ObProductDtos.Product> products = service.list(isActive);
        return new ObProductDtos.ObProductListResponse(products);
    }

    /**
     * <p>Carries the {@code ETag} the {@code PATCH} requires as {@code If-Match}
     * — the contract says this operation exists partly for that.
     */
    @GetMapping(value = "/products/{obProductId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getObProduct", summary = "One product (OB-07)")
    ResponseEntity<ObProductDtos.ObProductResponse> get(@PathVariable long obProductId) {
        return service.find(obProductId)
                .map(ObProductController::ok)
                .orElseThrow(ObProductController::notFound);
    }

    @PostMapping(value = "/products",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createObProduct", summary = "Add a product to the catalogue (OB-07)")
    ResponseEntity<ObProductDtos.ObProductResponse> create(
            @Valid @RequestBody ObProductDtos.WriteRequest request) {

        // createdBy is left null rather than guessed. The column is nullable and
        // A-112's CallerIdentity is the only honest source; wiring it here would
        // duplicate what the audit interceptor already records for this route.
        ObProductDtos.Product created = service.create(request, null);
        return ResponseEntity
                .created(URI.create("/api/v1/onboarding/products/" + created.id()))
                .eTag(etagOf(created))
                .body(new ObProductDtos.ObProductResponse(created));
    }

    @PatchMapping(value = "/products/{obProductId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateObProduct", summary = "Edit a product (OB-07)")
    ResponseEntity<ObProductDtos.ObProductResponse> update(
            @PathVariable long obProductId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ObProductDtos.WriteRequest request) {

        requirePrecondition(obProductId, ifMatch);
        return service.update(obProductId, request)
                .map(ObProductController::ok)
                .orElseThrow(ObProductController::notFound);
    }

    /**
     * <p><b>The 404 comes first.</b> Answering 428 for a product that does not
     * exist would send the caller to fetch a tag from a URL that will 404 too —
     * the ordering {@code ClientController} settled on.
     */
    private void requirePrecondition(long id, String ifMatch) {
        ObProductDtos.Product current = service.find(id)
                .orElseThrow(ObProductController::notFound);

        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "If-Match is required. GET the product first and send back its ETag.");
        }
        if (!matches(ifMatch, etagOf(current))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "This product changed since you read it. Reload and reapply your edit.");
        }
    }

    private static ResponseEntity<ObProductDtos.ObProductResponse> ok(ObProductDtos.Product product) {
        return ResponseEntity.ok()
                .eTag(etagOf(product))
                .body(new ObProductDtos.ObProductResponse(product));
    }

    /**
     * Derived from the content, not from {@code updated_at} — a timestamp tag
     * moves when a save rewrites identical values, failing an edit that
     * conflicts with nothing. {@code hasActiveTemplate} is part of the record
     * and therefore part of the tag, so a template published in another tab
     * costs this form a reload: it is the field that decides whether the
     * product can be bought, and a save made against a stale answer to that is
     * exactly the state worth refusing.
     */
    private static String etagOf(ObProductDtos.Product product) {
        return Integer.toHexString(product.hashCode());
    }

    private static boolean matches(String ifMatch, String current) {
        String candidate = ifMatch.trim();
        if ("*".equals(candidate)) {
            return true;
        }
        return candidate.replace("W/", "").replace("\"", "").equals(current);
    }

    /**
     * 404, never 403 — CONVENTIONS.md §7, and the same no-existence-leak rule
     * A-111's gate applies one level up.
     */
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such product.");
    }
}
