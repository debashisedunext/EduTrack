package com.edunext.edutrack.api.security.module;

/**
 * A-111 · thrown when a caller reaches a module they hold no entitlement for.
 *
 * <h2>This must render as 404, and the message must not reach the client</h2>
 *
 * <p>The whole point of {@link ModuleAccessGuard} is that a caller without the
 * module cannot tell it exists. A handler that rendered this as 403, or as a
 * 404 whose {@code detail} said "onboarding module not entitled", would give
 * back exactly the fact the status code was chosen to withhold.
 *
 * <p>So the wire form is the ordinary not-found problem — {@code type}
 * {@code https://edutrack/errors/not-found}, indistinguishable from a mistyped
 * path. The reason lives here, in the exception, for the log.
 *
 * <p>Same split {@code TicketNotFoundException} (A-034) already makes for an
 * out-of-scope row: the server knows why, the caller learns only that there is
 * nothing there.
 *
 * <p><b>Thrown by nothing today.</b> {@link ModuleAccessGuard} is the decision and
 * this is its outcome; the chain that raises it is the task that wires the
 * guard, once {@code /api/v1/onboarding/**} handlers exist to guard.
 */
public class ModuleNotEntitledException extends RuntimeException {

    private final String module;
    private final String requestPath;

    public ModuleNotEntitledException(String module, String requestPath) {
        // For the log only. Never a response body — see the javadoc above.
        super("caller holds no entitlement for module " + module + " (path " + requestPath + ")");
        this.module = module;
        this.requestPath = requestPath;
    }

    public String module() {
        return module;
    }

    public String requestPath() {
        return requestPath;
    }
}
