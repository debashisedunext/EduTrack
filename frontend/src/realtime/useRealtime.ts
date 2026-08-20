import { useEffect, useRef } from 'react';
import { realtime, type RealtimeHandler } from './client';

/**
 * Subscribe a component to a §9.3 destination for as long as it is mounted.
 *
 *   useRealtime(ticketTopic(ticketId), (event) => {
 *     queryClient.invalidateQueries({ queryKey: ['ticket', ticketId] });
 *   });
 *
 * Pass `null` when the destination is not known yet — a ticket id that arrives
 * with the query, say. Subscribing to a placeholder destination would open a
 * room nobody publishes to and quietly receive nothing.
 *
 * The handler is held in a ref, so an inline arrow function does **not** cause a
 * resubscribe on every render. Only the destination does. This matters more than
 * it looks: without it, every parent re-render tears down and re-opens the STOMP
 * subscription, and events land in the gap.
 */
export function useRealtime(destination: string | null, handler: RealtimeHandler): void {
  const handlerRef = useRef(handler);

  // Updated during render rather than in an effect so the first event after a
  // re-render already sees the new closure.
  handlerRef.current = handler;

  useEffect(() => {
    if (!destination) return;
    return realtime.subscribe(destination, (payload) => handlerRef.current(payload));
  }, [destination]);
}

/**
 * Subscribe to **several** destinations at once, for as long as the component
 * is mounted — D-059's shape, and the reason it is not just a loop over
 * {@link useRealtime}: a hook cannot be called a variable number of times.
 *
 * A team inbox is one screen over one stage across *n* projects, and §9.3 keys
 * a stage room on `(stageCode, projectId)`. So the number of rooms is a
 * property of the viewer's memberships, which changes.
 *
 * `destinations` does **not** have to be memoised. The effect depends on the
 * joined string rather than the array's identity, so a fresh array of the same
 * rooms on every render does not tear down and re-open every subscription —
 * the mistake {@link useRealtime}'s handler ref exists to prevent, one
 * dimension over. Duplicates are collapsed; order does not matter but is kept
 * stable by the caller.
 */
export function useRealtimeAll(destinations: readonly string[], handler: RealtimeHandler): void {
  const handlerRef = useRef(handler);
  handlerRef.current = handler;

  const key = Array.from(new Set(destinations)).join('\n');

  useEffect(() => {
    if (!key) return;
    const unsubscribes = key
      .split('\n')
      .map((destination) => realtime.subscribe(destination, (payload) => handlerRef.current(payload)));
    return () => unsubscribes.forEach((unsubscribe) => unsubscribe());
  }, [key]);
}
