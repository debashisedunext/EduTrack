package com.edunext.edutrack.api.feature.portal.tickets;

import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalAttachment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalComment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalTicket;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketReadRepository.AttachmentRow;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentProperties;
import com.edunext.edutrack.api.feature.tickets.attachments.AttachmentStorage;
import com.edunext.edutrack.api.security.scope.ClientScopeResolver;
import com.edunext.edutrack.common.pagination.Cursor;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageLimit;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * A-127 · CP-06 and CP-07, read-only.
 *
 * <h2>Two gates, and both answer "nothing" rather than "no"</h2>
 *
 * <p>A caller reaches this service only after {@code PortalRouteFilter} has
 * established they are a {@code CLIENT} principal. What remains is which client,
 * and whether that client's portal is open:
 *
 * <ol>
 *   <li>{@link ClientScopeResolver#ticketingClientId} — empty for a client whose
 *       account is not linked to a ticketing client, which is an ordinary state
 *       (plan §2.3: the two masters are disjoint), not an error.</li>
 *   <li>{@link PortalTicketReadRepository#portalIsOpenFor} — the
 *       {@code client_contacts.portal_access} hook.</li>
 * </ol>
 *
 * <p>Either failing yields an empty list or an empty {@code Optional}, and the
 * controller turns those into an empty page and a 404. <b>Neither is a 403.</b>
 * A client outside the organisation must not be able to tell "your access was
 * withdrawn" from "there is nothing here", and must never learn that another
 * company's ticket code is real — CONVENTIONS §7's no-existence-leak rule, which
 * matters more on this surface than anywhere it is usually applied.
 *
 * <h2>What this service does not have</h2>
 *
 * <p>No write method, and no method that takes a client id from a caller. The
 * first because plan §1 makes the portal's ticketing side view-only; the second
 * because a client id that arrives as an argument from outside is a scope the
 * caller chose.
 */
@Service
class PortalTicketService {

    private final PortalTicketReadRepository repository;
    private final ClientScopeResolver scope;
    private final AttachmentStorage storage;
    private final AttachmentProperties attachmentProperties;

    PortalTicketService(PortalTicketReadRepository repository,
                        ClientScopeResolver scope,
                        AttachmentStorage storage,
                        AttachmentProperties attachmentProperties) {
        this.repository = repository;
        this.scope = scope;
        this.storage = storage;
        this.attachmentProperties = attachmentProperties;
    }

    CursorPage<PortalTicket> list(Authentication caller, String status, boolean excludeClosed,
                                  String sort, String cursor, Integer limit) {

        int size = PageLimit.clamp(limit);
        boolean descending = isDescending(sort);
        return openClientOf(caller)
                .map(clientId -> CursorPage.of(
                        repository.tickets(clientId, status, excludeClosed, descending,
                                decode(cursor), PageLimit.fetchSize(size)),
                        size,
                        t -> new Cursor(t.dateReported().toString(), t.id())))
                .orElseGet(() -> CursorPage.of(List.of(), size, t -> new Cursor("", 0)));
    }

    /**
     * {@code dateReported} is the one field the contract exposes on this list,
     * so unlike {@code TicketListSpecs.sortKey}'s named-column map, there is
     * nothing here to look up — only the direction. Same rule as that class:
     * a leading {@code -} is descending, and anything else — including a value
     * naming a column this endpoint does not serve — is the contract's own
     * default, {@code -dateReported}, rather than a 400. A saved link should
     * still open after the columns it once offered change.
     */
    private static boolean isDescending(String sort) {
        return sort == null || sort.isBlank() || !sort.trim().startsWith("+");
    }

    Optional<PortalTicket> get(Authentication caller, String ticketCode) {
        return openClientOf(caller).flatMap(clientId -> repository.ticket(clientId, ticketCode));
    }

    Optional<CursorPage<PortalComment>> comments(Authentication caller, String ticketCode,
                                                 String cursor, Integer limit) {
        int size = PageLimit.clamp(limit);
        return scopedTicketRowId(caller, ticketCode).map(rowId -> CursorPage.of(
                repository.comments(rowId, decode(cursor), PageLimit.fetchSize(size)),
                size,
                c -> new Cursor(c.createdAt().toString(), c.id())));
    }

    Optional<CursorPage<PortalAttachment>> attachments(Authentication caller, String ticketCode,
                                                       String cursor, Integer limit) {
        int size = PageLimit.clamp(limit);
        return scopedTicketRowId(caller, ticketCode).map(rowId -> {
            CursorPage<AttachmentRow> rows = CursorPage.of(
                    repository.attachments(rowId, decode(cursor), PageLimit.fetchSize(size)),
                    size,
                    a -> new Cursor(a.createdAt().toString(), a.id()));
            return new CursorPage<>(
                    rows.data().stream().map(row -> sign(row, rowId)).toList(),
                    rows.meta());
        });
    }

    /**
     * The caller's client, if they have one and their portal is open.
     *
     * <p>The two conditions are folded into one {@code Optional} deliberately.
     * Every read below starts here, so a read added later cannot pick up the
     * scope while forgetting the access flag — which is the shape of every
     * scoping bug this codebase has recorded.
     */
    private Optional<Long> openClientOf(Authentication caller) {
        return scope.ticketingClientId(caller).filter(repository::portalIsOpenFor);
    }

    /** Scoped ticket lookup for the two child listings. Empty is the caller's 404. */
    private Optional<Long> scopedTicketRowId(Authentication caller, String ticketCode) {
        return openClientOf(caller).flatMap(clientId -> repository.ticketRowId(clientId, ticketCode));
    }

    /**
     * A short-lived signature over an object under <em>this</em> ticket.
     *
     * <p>{@link PortalStorageKey#belongsTo} is checked rather than assumed, on
     * {@code AttachmentService}'s own rule for the thumbnail key: the row's
     * {@code ticket_id} and the key's embedded ticket id are two independent
     * statements about where the object lives, and a URL is minted only when they
     * agree. A row whose key is malformed, or points at another ticket, is served
     * with null URLs rather than dropped — the file is genuinely there and the
     * customer should see that it is, and dropping it would hide a data problem
     * that somebody needs to fix.
     */
    private PortalAttachment sign(AttachmentRow row, long ticketRowId) {
        return new PortalAttachment(
                row.id(),
                row.fileName(),
                row.contentType(),
                row.sizeBytes(),
                signedUrl(row.storageKey(), ticketRowId, row.fileName(), row.contentType()),
                signedUrl(row.thumbnailKey(), ticketRowId, row.fileName(), row.contentType()),
                row.createdAt());
    }

    private String signedUrl(String key, long ticketRowId, String fileName, String contentType) {
        if (key == null || !PortalStorageKey.belongsTo(key, ticketRowId)) {
            return null;
        }
        return storage.signedDownloadUrl(
                        PortalStorageKey.parse(key), fileName, contentType,
                        attachmentProperties.signedUrlTtl())
                .toString();
    }

    /**
     * A cursor that will not decode is treated as absent — the first page.
     *
     * <p>The alternative is a 400 on a value the caller never composed, and the
     * usual way one arrives malformed is a stale bookmark rather than an attack.
     * There is nothing to guess at either: the cursor names a position in a set
     * already scoped to this caller, so a forged one can only ever move them
     * around their own tickets.
     */
    private static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Cursor.decode(cursor);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
