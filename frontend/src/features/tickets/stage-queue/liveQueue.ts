import { canAddressStage, stageTopic } from '@/realtime/destinations'

/**
 * D-059 · which §9.3 stage rooms this screen should be listening to.
 *
 * Pure, and separate from the page, because the interesting part is the
 * project list rather than the string building — and the project list is the
 * part with three cases and a security argument behind each.
 *
 * ## One stage, many projects
 *
 * `StageQueueBroadcaster` publishes to `/topic/stage.{code}.{projectId}`: a
 * queue is per project, and S-31 shows one stage across all of them unless the
 * URL narrows it. So the screen is *n* rooms, not one, and *n* is a property of
 * who is looking.
 *
 * - **A project is selected** — exactly that room, whoever is asking.
 *   `StageQueueSubscriptionScope` will refuse it if they are not on the
 *   project, and a refusal is a STOMP `ERROR` frame; that is the correct
 *   outcome for a link somebody pasted them, not something to pre-empt here.
 * - **An Admin, no project selected** — every active project. §10.2 gives
 *   Admin the whole organisation and the subscription scope grants them any
 *   room, so their membership rows (often none) are the wrong list to use.
 * - **Anyone else, no project selected** — their own memberships, from
 *   `GET /auth/me`. Not the loaded project list: subscribing to a project they
 *   are not on earns a refusal, and a refused SUBSCRIBE closes the session,
 *   which would take the notification stream and chat down with it.
 *
 * ## Two things it deliberately will not do
 *
 * **It will not subscribe to a stage code it cannot address.** Stage codes come
 * from a workflow template an Admin edits (B-034), so a dot or a space is
 * reachable from the product rather than only from a typo. `stageTopic` throws
 * on one — correct for a literal, a white screen here. Returning no rooms
 * leaves the queue exactly as it behaved before this task: correct, and
 * refreshed by hand.
 *
 * **It caps the number of rooms.** An Admin on an organisation with 200 active
 * projects would otherwise open 200 subscriptions on a screen that shows one
 * stage. The cap costs live updates on the projects past it, which is the same
 * degradation as no subscription at all and strictly better than a socket that
 * struggles.
 */
export const MAX_STAGE_ROOMS = 25

export interface StageRoomInput {
  /** The stage actually being shown — from the template list, not the raw URL param. */
  stageCode: string | null | undefined
  /** `?projectId=`, when the queue is narrowed to one project. */
  selectedProjectId: number | null
  /** The viewer's own memberships, from `GET /auth/me`. */
  myProjectIds: readonly number[] | undefined
  /** Every active project, used only for an Admin with no project selected. */
  allProjectIds: readonly number[] | undefined
  isAdmin: boolean
}

export function stageRooms({
  stageCode,
  selectedProjectId,
  myProjectIds,
  allProjectIds,
  isAdmin,
}: StageRoomInput): string[] {
  if (!stageCode || !canAddressStage(stageCode)) return []

  const projectIds =
    selectedProjectId != null
      ? [selectedProjectId]
      : isAdmin
        ? (allProjectIds ?? [])
        : (myProjectIds ?? [])

  return Array.from(new Set(projectIds))
    .filter((id) => Number.isInteger(id) && id > 0)
    .slice(0, MAX_STAGE_ROOMS)
    .map((id) => stageTopic(stageCode, id))
}
