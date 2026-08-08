import { describe, expect, it } from 'vitest';
import {
  managerTopic,
  projectTopic,
  stageTopic,
  ticketTopic,
  userQueue,
} from './destinations';

/**
 * These strings must match `RealtimeDestinations.java` byte for byte. A
 * mismatch is not a test failure anywhere — it is a subscription to a room
 * nobody publishes to, which looks exactly like "no events happened".
 */
describe('destinations', () => {
  it('builds every §9.3 room', () => {
    expect(userQueue(12)).toBe('/user/12/queue/events');
    expect(ticketTopic(4471)).toBe('/topic/ticket.4471');
    expect(stageTopic('QA', 7)).toBe('/topic/stage.QA.7');
    expect(projectTopic(7)).toBe('/topic/project.7');
    expect(managerTopic(3)).toBe('/topic/manager.3');
  });

  it('rejects a stage code that would re-shape the destination', () => {
    // "QA.1" on project 7 and "QA" on project 1 both produce a plausible
    // destination, so the server could parse a subscriber into another team's
    // room. Rejected here as well as server-side, to fail at the call site.
    expect(() => stageTopic('QA.1', 7)).toThrow(/stage code/);
    expect(() => stageTopic('qa', 7)).toThrow(/stage code/);
    expect(() => stageTopic('', 7)).toThrow(/stage code/);
  });

  it('rejects ids that are not positive integers', () => {
    expect(() => ticketTopic(0)).toThrow(/positive/);
    expect(() => ticketTopic(-1)).toThrow(/positive/);
    expect(() => projectTopic(1.5)).toThrow(/positive/);
    expect(() => userQueue(Number.NaN)).toThrow(/positive/);
  });
});
