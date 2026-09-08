import { beforeEach, describe, expect, it } from 'vitest';

import { getDb } from '../db';
import { portalClientId } from './portal';

/**
 * A-127 · the portal's ticketing reads, CP-06/07.
 *
 * These assert the three filters and nothing else. Field-by-field payload
 * checks would pass for the wrong reason — the risk on this surface is not a
 * missing `title`, it is a row that should never have been loaded: another
 * company's ticket, an internal comment, a file that has not cleared the
 * scanner.
 *
 * Each test builds the state it needs rather than trusting the fixture to
 * contain it. A test that silently finds nothing to filter is a test that
 * passes whatever the handler does, which is the failure mode
 * `mutation-check` exists to catch.
 */

let clientId: number;
let mine: string;
let theirs: string;

beforeEach(() => {
  const db = getDb();
  clientId = portalClientId()!;
  expect(clientId).not.toBeNull();

  // Two tickets that differ only in who owns them, so any difference in the
  // response is attributable to ownership alone.
  const [a, b] = db.tickets;
  a.clientId = clientId;
  b.clientId = clientId + 1000; // a client that is not this one
  mine = a.ticketId;
  theirs = b.ticketId;
});

async function get(path: string) {
  return fetch(`/api/v1/portal${path}`);
}

async function dataOf<T>(path: string): Promise<T> {
  const res = await get(path);
  expect(res.status).toBe(200);
  return ((await res.json()) as { data: T }).data;
}

describe('CP-06 · the ticket list', () => {
  it('serves this client’s tickets and not another client’s', async () => {
    const rows = await dataOf<{ ticketId: string }[]>('/tickets');
    const ids = rows.map((r) => r.ticketId);
    expect(ids).toContain(mine);
    expect(ids).not.toContain(theirs);
  });

  it('omits an internally-raised ticket, which carries no client at all', async () => {
    const db = getDb();
    const internal = db.tickets[2];
    internal.clientId = null;

    const rows = await dataOf<{ ticketId: string }[]>('/tickets');
    expect(rows.map((r) => r.ticketId)).not.toContain(internal.ticketId);
  });

  it('carries no owner, no level, no stage and no TAT internals', async () => {
    const [row] = await dataOf<Record<string, unknown>[]>('/tickets');
    // The never-visible list, asserted as absence. Named individually rather
    // than as a snapshot so that adding a field to the portal DTO is a
    // deliberate act and removing one of these is a failing test.
    for (const field of [
      'assignee',
      'reportedBy',
      'level',
      'originalLevel',
      'currentStageCode',
      'isDelayed',
      'delayedSince',
      'totalEffortHrs',
      'estimatedHrs',
      'projectId',
      'client',
    ]) {
      expect(row).not.toHaveProperty(field);
    }
  });

  it('empties when the contact’s portal access is withdrawn', async () => {
    // Proves `portalAccess` is load-bearing rather than decorative. Without
    // this, the flag could be deleted from the handler and every other test
    // here would still pass.
    getDb().contacts.forEach((c) => {
      c.portalAccess = false;
    });

    const rows = await dataOf<unknown[]>('/tickets');
    expect(rows).toHaveLength(0);
  });
});

describe('CP-07 · the read-only detail', () => {
  it('answers 404 for another client’s ticket, exactly as for one that does not exist', async () => {
    const other = await get(`/tickets/${theirs}`);
    const absent = await get('/tickets/NOPE-99-99999');

    expect(other.status).toBe(404);
    expect(absent.status).toBe(404);
    // Identical bodies, or the status code stops being the only thing that
    // does not distinguish them. CONVENTIONS §7.
    expect(await other.text()).toEqual(await absent.text());
  });

  it('carries an ETag', async () => {
    const res = await get(`/tickets/${mine}`);
    expect(res.headers.get('ETag')).toBeTruthy();
  });
});

describe('CP-07 · comments', () => {
  beforeEach(() => {
    const db = getDb();
    db.comments.forEach((c) => {
      c.ticketId = mine;
      c.isClientVisible = false;
      c.isDeleted = false;
    });
    db.comments[0].isClientVisible = true;
    db.comments[1].isClientVisible = true;
    db.comments[1].isDeleted = true;
  });

  it('serves only the client-visible ones', async () => {
    const rows = await dataOf<{ id: number }[]>(`/tickets/${mine}/comments`);
    const db = getDb();
    expect(rows.map((r) => r.id)).toContain(db.comments[0].id);
    expect(rows.map((r) => r.id)).not.toContain(db.comments[2].id);
  });

  it('omits a client-visible comment that was removed, rather than serving a tombstone', async () => {
    const rows = await dataOf<{ id: number }[]>(`/tickets/${mine}/comments`);
    expect(rows.map((r) => r.id)).not.toContain(getDb().comments[1].id);
  });

  it('names the author without handing over the staff record', async () => {
    const [row] = await dataOf<Record<string, unknown>[]>(`/tickets/${mine}/comments`);
    expect(row.authorName).toEqual(expect.any(String));
    // `author` would have been a UserRef — id, email and role.
    expect(row).not.toHaveProperty('author');
    expect(row).not.toHaveProperty('authorRole');
    expect(row).not.toHaveProperty('mentions');
  });

  it('has no parameter that turns the filter off', async () => {
    const withFlag = await dataOf<unknown[]>(`/tickets/${mine}/comments?isClientVisible=false`);
    const without = await dataOf<unknown[]>(`/tickets/${mine}/comments`);
    expect(withFlag).toHaveLength(without.length);
  });
});

describe('CP-07 · attachments', () => {
  beforeEach(() => {
    const db = getDb();
    db.attachments.forEach((a) => {
      a.ticketId = mine;
      a.isClientVisible = true;
      a.scanStatus = 'CLEAN';
      a.isDeleted = false;
    });
    db.attachments[0].isClientVisible = false;
    db.attachments[1].scanStatus = 'PENDING';
    db.attachments[2].isDeleted = true;
  });

  it('applies all three conditions, not only the visibility flag', async () => {
    const rows = await dataOf<{ id: number }[]>(`/tickets/${mine}/attachments`);
    const ids = rows.map((r) => r.id);
    const db = getDb();
    expect(ids).not.toContain(db.attachments[0].id); // internal
    expect(ids).not.toContain(db.attachments[1].id); // not yet scanned
    expect(ids).not.toContain(db.attachments[2].id); // removed
    expect(ids).toContain(db.attachments[3].id);
  });

  it('carries no uploader and no scan status', async () => {
    const [row] = await dataOf<Record<string, unknown>[]>(`/tickets/${mine}/attachments`);
    expect(row).not.toHaveProperty('uploadedBy');
    expect(row).not.toHaveProperty('scanStatus');
    expect(row).not.toHaveProperty('isClientVisible');
  });
});
