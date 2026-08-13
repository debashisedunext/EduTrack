package com.edunext.edutrack.api.security.permission;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A-036 · one definition of "which routes this application serves".
 *
 * <p>Extracted from {@code RouteAuthorizationTest}, which had it privately.
 * Two suites now assert coverage over that set — A-033's "every route declares a
 * decision" and A-036's "every route has a matrix entry" — and a second,
 * hand-copied enumeration would drift the moment one of them learned about a
 * new handler mapping and the other did not. A route that fell out of one
 * inventory would go silently uncovered by the check built on it, which is the
 * exact failure both checks exist to prevent.
 *
 * <p>{@link #describe} is the key format both suites use, so a matrix entry and
 * a routed handler are comparable as strings without either side normalising.
 */
public final class RouteInventory {

    /**
     * Ours. springdoc's and Boot's own controllers cannot be annotated and are
     * not ours to annotate.
     */
    private static final String APPLICATION_PACKAGE = "com.edunext.edutrack";

    private RouteInventory() {
    }

    /** Every request-mapped handler declared by this application. */
    public static Map<RequestMappingInfo, HandlerMethod> handlers(RequestMappingHandlerMapping mapping) {
        return mapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getName().startsWith(APPLICATION_PACKAGE))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The same set as {@code METHOD /pattern} strings, sorted for readable diffs. */
    public static Set<String> routeKeys(RequestMappingHandlerMapping mapping) {
        Set<String> keys = new TreeSet<>();
        handlers(mapping).forEach((info, handler) -> keys.add(describe(info, handler)));
        return keys;
    }

    /**
     * {@code POST /api/v1/masters/holidays} — a failure message has to be
     * actionable, and this is also the join key between a matrix entry and a
     * routed handler.
     */
    public static String describe(RequestMappingInfo info, HandlerMethod handler) {
        String methods = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name).sorted().reduce((a, b) -> a + "|" + b).orElse("ANY");
        String paths = String.join(",", new TreeSet<>(info.getPatternValues()));
        return methods + " " + paths;
    }
}
