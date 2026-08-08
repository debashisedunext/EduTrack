/**
 * The §9.3 destination map, client side.
 *
 * The mirror of `api/realtime/RealtimeDestinations.java` (D-014), and it exists
 * for the same reason: **a mistyped destination fails silently.** The broker has
 * no notion of a room that ought to exist, so a subscription to
 * `/topic/tickets.4471` — plural, one character — is accepted and simply never
 * receives anything. That surfaces days later as "realtime doesn't work for some
 * users", with nothing in any log to point at.
 *
 * Building every destination here turns that into a compile error.
 *
 * Owned by Stream D (D-015). If you need a new room, add it here **and** in the
 * Java map in the same PR — a destination that exists on only one side is a
 * subscription nobody publishes to, or a publish nobody hears.
 */

/** Stage codes are structural, not free text — see the Java map for why. */
const STAGE_CODE = /^[A-Z0-9_-]+$/;

function positive(id: number, field: string): number {
  if (!Number.isInteger(id) || id <= 0) {
    throw new Error(`${field} must be a positive integer but was ${id}`);
  }
  return id;
}

/** `user:{id}` — resolved to that user's own session queue by Spring. */
export function userQueue(userId: number): string {
  return `/user/${positive(userId, 'userId')}/queue/events`;
}

/** `ticket:{id}` — ribbon advances, comments, typing. */
export function ticketTopic(ticketId: number): string {
  return `/topic/ticket.${positive(ticketId, 'ticketId')}`;
}

/**
 * `stage:{code}:{projectId}` — a team inbox that updates without a refresh.
 *
 * The code is validated rather than sanitised. A dot would re-shape the
 * destination: "QA.1" on project 7 and "QA" on project 1 both produce
 * plausible-looking rooms, so a subscriber could land in another team's queue.
 */
export function stageTopic(stageCode: string, projectId: number): string {
  if (!STAGE_CODE.test(stageCode)) {
    throw new Error(`stage code must match ${STAGE_CODE.source} but was: ${stageCode}`);
  }
  return `/topic/stage.${stageCode}.${positive(projectId, 'projectId')}`;
}

/** `project:{id}` — ticket creation, dashboard refresh. */
export function projectTopic(projectId: number): string {
  return `/topic/project.${positive(projectId, 'projectId')}`;
}

/** `manager:{id}` — SLA breaches and reportee updates. */
export function managerTopic(managerId: number): string {
  return `/topic/manager.${positive(managerId, 'managerId')}`;
}
