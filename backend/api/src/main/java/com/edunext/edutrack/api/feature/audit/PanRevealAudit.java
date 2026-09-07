package com.edunext.edutrack.api.feature.audit;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.ClientAddress;
import com.edunext.edutrack.domain.audit.AuditEntry;
import com.edunext.edutrack.domain.audit.AuditTrail;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A-113 · records that somebody read a PAN in the clear.
 *
 * <h2>Written by name, because the interceptor cannot see this one</h2>
 *
 * <p>{@code AuditActions} derives a term from the route for every
 * <b>mutating</b> request, which is what makes the audit log complete without
 * anybody outside Stream A remembering to call anything. A PAN reveal is a
 * <b>read</b>. {@link AuditInterceptor} will never produce a row for it, and
 * that is not an oversight to fix there — auditing reads generally would file a
 * row for every list, every dashboard tile and every export in the product.
 *
 * <p>So this joins {@link LoginAudit} in the small set of events written by
 * name: the ones where a route cannot say enough. Blueprint §11 asks for
 * exactly one of them.
 *
 * <h2>The subject is the client, not the PAN</h2>
 *
 * <p>{@code entityType = onboarding}, {@code entityId = ob_clients.id}. Keyed
 * that way, {@code ix_audit_logs_entity} answers "everything that happened to
 * this client", and the reveal appears in that history beside the edits — which
 * is the question an investigation actually asks. A separate {@code pan} entity
 * type would put the one event nobody wants to miss in a bucket of its own that
 * no existing screen reads.
 *
 * <p><b>No PAN, masked or otherwise, is written to the row.</b> An audit log
 * that records what was disclosed becomes a second copy of the data it is
 * protecting — readable by every Admin with {@code audit.view}, exported by
 * {@code AuditExportService} to a spreadsheet, and outliving the client record
 * itself. The row says who, which client, and when; the value stays in
 * {@code ob_clients} behind its key.
 */
@Component
public class PanRevealAudit {

    /**
     * {@code onboarding}, matching what {@code AuditActions} derives for
     * {@code /api/v1/onboarding/**} — so the reveal sorts alongside the
     * client's other history rather than into a module of its own.
     */
    private static final String MODULE = "onboarding";

    private final AuditTrail audit;

    PanRevealAudit(AuditTrail audit) {
        this.audit = audit;
    }

    /**
     * One row per deliberate disclosure.
     *
     * @param clientId {@code ob_clients.id} whose PAN was read
     * @param caller   the actor; never null here, because revealing is behind
     *                 authentication and an anonymous reveal is not reachable
     */
    public void revealed(long clientId, CallerIdentity caller) {
        AuditEntry entry = AuditEntry.of(
                caller == null ? null : caller.userId(),
                AuditActions.PAN_REVEALED,
                MODULE,
                clientId);
        audit.record(origin(entry));
    }

    /**
     * IP and user-agent, taken from the request in flight if there is one.
     *
     * <p>Pulled from {@link RequestContextHolder} rather than threaded through
     * {@code PanService}'s signature. The alternative would put an
     * {@link HttpServletRequest} parameter on a method whose job is decryption,
     * and every future caller — a scheduled export, a support tool — would then
     * have to invent one or pass null. Absent context yields a row without
     * origin, which is the honest answer for a reveal that did not come from a
     * request.
     */
    private static AuditEntry origin(AuditEntry entry) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return entry;
        }
        HttpServletRequest request = servlet.getRequest();
        return entry.from(ClientAddress.of(request), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
