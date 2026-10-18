import { compare } from './compare';
import { BranchSummary, DcCheckpoint, SyncLogEntry } from './models';

const request = (over: Partial<SyncLogEntry> = {}): SyncLogEntry => ({
  requestId: 'r1', branch: 'A', kind: 'SYNC', eventCount: 24, firstSeq: 101, lastSeq: 124, status: 'PUBLISHED',
  publishedCount: 24, error: null, createdAt: '2026-10-16T10:00:00Z', publishedAt: '2026-10-16T10:00:00Z', ...over,
});
const dc = (passes: number, lastRequest: SyncLogEntry | null): DcCheckpoint => ({
  code: 'A', name: 'Checkpoint A', kind: 'AIRPORT', passes, approved: passes, revoked: 0,
  lastPublishedSeq: lastRequest?.lastSeq ?? null, lastRequest,
});
const branch = (passes: number, inRange: number | null): BranchSummary => ({
  site: 'A', passes, approved: passes, revoked: 0, tombstones: 0, maxSeq: null, lastReceivedAt: null, inRange,
});

describe('compare (a branch against what the DC sent it)', () => {
  it('is not synced when the DC never sent anything and the branch is empty', () => {
    expect(compare(dc(24, null), branch(0, null))).toEqual({ kind: 'not-synced' });
  });

  it('is catching up while the latest request is not fully applied', () => {
    expect(compare(dc(24, request()), branch(10, 10))).toEqual({ kind: 'catching-up', done: 10, total: 24 });
    expect(compare(dc(24, request({ status: 'PENDING' })), branch(24, 24))).toEqual({ kind: 'catching-up', done: 0, total: 24 });
  });

  it('is in sync when the request is fully applied and the counts match', () => {
    expect(compare(dc(24, request()), branch(24, 24))).toEqual({ kind: 'in-sync' });
  });

  it('after a rush on a branch that was synced before: in sync once the rush is applied', () => {
    expect(compare(dc(1024, request({ kind: 'RUSH', eventCount: 1000 })), branch(1024, 1000))).toEqual({ kind: 'in-sync' });
  });

  it('differs when everything sent is applied but the DC has passes the branch was never sent', () => {
    // a rush on a branch that was never synced: it has the rush, but not the 24 older passes
    expect(compare(dc(1024, request({ kind: 'RUSH', eventCount: 1000 })), branch(1000, 1000)))
      .toEqual({ kind: 'differs', dcPasses: 1024, branchPasses: 1000 });
  });

  it('is purged when a purge is fully applied and the branch is empty', () => {
    expect(compare(dc(24, request({ kind: 'PURGE' })), branch(0, 24))).toEqual({ kind: 'purged' });
  });

  it('says so when the DC could not publish its latest request', () => {
    expect(compare(dc(24, request({ status: 'FAILED', publishedCount: 3 })), branch(3, 3))).toEqual({ kind: 'publish-failed' });
  });
});
