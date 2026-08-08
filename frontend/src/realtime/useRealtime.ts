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
