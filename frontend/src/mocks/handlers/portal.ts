import { http } from 'msw';
import type { Attachment, Comment, Db, Ticket } from '../db';
import { getDb } from '../db';
import { notFound, ok, paginate, url } from './util';

/**
 * A-127 · the client portal's ticketing side — CP-06 and CP-07.
 *
 * **Four reads and nothing else.** Plan §1: "No client-raised tickets. The
 * portal's ticketing side is view-only." There is no write handler here to be
 * copied into one later.
 *
 * The mock mirrors the server's three filters rather than serving everything
 * and leaving the narrowing to the screen, for the reason `scopedTickets` in
 * `util.ts` already gives: a mock that returns more than the server does makes
 * the first day against the real backend the day half the rows disappear. Here
 * the stakes are higher than an empty grid — the rows a lax mock would show are
 * another company's tickets, and this one's internal comments.
 */

/**
 * The signed-in client.
 *
 * The real principal comes off a `principal_type: CLIENT` token (A-125) and is
 * resolved by `ClientScopeResolver`. The mock has no token, so it stands in the
 * first client with an **active contact holding `portalAccess`** — client 1,
 * Acme, through Sara Kapoor.
 *
 * Derived rather than hardcoded to `1` on purpose. `portalAccess` is the second
 * dormant hook plan §2.3 activates, and deriving the principal from it means
 * clearing the flag in the fixture actually empties the portal, the way it will
 * on the server. A constant would have left the flag decorative here.
 */
export function portalClientId(db: Db = getDb()): number | null {
  const contact = db.contacts.find((c) => c.portalAccess && c.isActive);
  return contact ? contact.clientId : null;
}

/**
 * The tickets this client may see, and no others.
 *
 * `clientId` is nullable on a ticket and an internally-raised one leaves it
 * null, so the equality excludes those without needing a rule of its own — the
 * same property the contract's `listPortalTickets` note records.
 */
export function portalTickets(db: Db = getDb()): Ticket[] {
  const clientId = portalClientId(db);
  if (clientId == null) return [];
  return db.tickets.filter((t) => t.clientId === clientId);
}

function findPortalTicket(ticketId: string, db: Db = getDb()): Ticket | undefined {
  return portalTickets(db).find((t) => t.ticketId === ticketId);
}

/**
 * The portal's own ticket serializer.
 *
 * Not a projection of `ticketDto`, and this is the one place in the mocks where
 * sharing a mapper would be wrong. `ticketSummaryDto` narrows `ticketDto`
 * because both serve colleagues, and there a field meaning two things would be
 * the bug. This one exists precisely so the two can never share a field: plan §9
 * wants "separate portal DTO serializers, never staff DTOs with fields hidden
 * client-side", and a projection is exactly a staff DTO with fields hidden.
 */
export function portalTicketDto(t: Ticket, db: Db = getDb()) {
  const project = db.projects.find((p) => p.id === t.projectId)!;
  const taskType = db.taskTypes.find((x) => x.id === t.taskTypeId);
  return {
    id: db.tickets.findIndex((row) => row.ticketId === t.ticketId) + 1,
    ticketId: t.ticketId,
    title: t.title,
    description: t.description,
    status: t.status,
    // The name, never the id. An id is a staff handle, and one the caller could
    // try on the staff tree; the name is what the client already calls it.
    projectName: project.name,
    taskTypeName: taskType ? taskType.name : null,
    dateReported: t.createdAt,
    plannedCloseDate: t.plannedCloseDate,
    actualCloseDate: t.actualCloseDate,
    lastUpdatedAt: t.updatedAt,
  };
}

/** Client-visible and not tombstoned. Both conditions, or neither is worth having. */
function portalComments(ticketId: string, db: Db = getDb()): Comment[] {
  return db.comments.filter((c) => c.ticketId === ticketId && c.isClientVisible && !c.isDeleted);
}

/**
 * Client-visible, scanned CLEAN, not tombstoned.
 *
 * The scan condition is the one easy to leave out and hard to notice missing:
 * an INFECTED row carries no download URL anyway, so omitting it looks
 * harmless — until a PENDING file that later turns out infected has already
 * been listed to a customer with a retry beside it.
 */
function portalAttachments(ticketId: string, db: Db = getDb()): Attachment[] {
  return db.attachments.filter(
    (a) => a.ticketId === ticketId && a.isClientVisible && a.scanStatus === 'CLEAN' && !a.isDeleted,
  );
}

export function portalAttachmentDto(a: Attachment) {
  return {
    id: a.id,
    fileName: a.fileName,
    contentType: a.contentType,
    sizeBytes: a.sizeBytes,
    // Unconditional, unlike `attachmentDto`'s — only CLEAN rows reach here, so
    // a null branch would be unreachable rather than defensive.
    downloadUrl: `/mock-files/${a.id}/${a.fileName}?sig=mock`,
    thumbnailUrl: ['image/png', 'image/jpeg', 'image/gif'].includes(a.contentType)
      ? `/mock-files/${a.id}/thumb.png?sig=mock`
      : null,
    createdAt: a.createdAt,
  };
}

export function portalCommentDto(c: Comment, db: Db = getDb()) {
  const author = db.users.find((u) => u.id === c.authorId);
  return {
    id: c.id,
    body: c.body,
    // Every comment in the fixture is written by a staff user; a contact
    // posting through the portal is a later phase, and the enum carries CLIENT
    // already so the screen has a branch to build rather than a shape to change.
    authorType: 'STAFF' as const,
    // A display name and nothing else — no id, no email, no role. `userRef`
    // would have carried all three, which is why this does not call it.
    authorName: author ? author.displayName : null,
    createdAt: c.createdAt,
  };
}

/** Content-derived, mirroring `onboardingJourneys.ts#etagOf`. */
function etagOf(detail: unknown): string {
  const str = JSON.stringify(detail);
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

export const portalHandlers = [
  http.get(url('/portal/tickets'), ({ request }) => {
    const requestUrl = new URL(request.url);
    const status = requestUrl.searchParams.get('status');
    const excludeClosed = requestUrl.searchParams.get('excludeClosed') === 'true';
    let rows = portalTickets();
    if (status) rows = rows.filter((t) => t.status === status);
    if (excludeClosed) rows = rows.filter((t) => t.status !== 'CLOSED');
    rows = [...rows].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    const { page, meta } = paginate(rows, requestUrl);
    return ok(
      page.map((t) => portalTicketDto(t)),
      meta,
    );
  }),

  // Another client's ticket and a ticket that does not exist answer the same
  // 404, in all three handlers below. `findPortalTicket` searches the scoped
  // set, so there is no branch that could tell them apart even by accident.
  http.get(url('/portal/tickets/:ticketId'), ({ params }) => {
    const ticket = findPortalTicket(String(params.ticketId));
    if (!ticket) return notFound('Ticket');
    const dto = portalTicketDto(ticket);
    return ok(dto, undefined, { headers: { ETag: etagOf(dto) } });
  }),

  http.get(url('/portal/tickets/:ticketId/comments'), ({ params, request }) => {
    const ticket = findPortalTicket(String(params.ticketId));
    if (!ticket) return notFound('Ticket');
    const { page, meta } = paginate(portalComments(ticket.ticketId), new URL(request.url));
    return ok(
      page.map((c) => portalCommentDto(c)),
      meta,
    );
  }),

  http.get(url('/portal/tickets/:ticketId/attachments'), ({ params, request }) => {
    const ticket = findPortalTicket(String(params.ticketId));
    if (!ticket) return notFound('Ticket');
    const { page, meta } = paginate(portalAttachments(ticket.ticketId), new URL(request.url));
    return ok(page.map(portalAttachmentDto), meta);
  }),
];
