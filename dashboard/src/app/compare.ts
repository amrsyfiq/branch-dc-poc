import { BranchSummary, DcCheckpoint } from './models';

/**
 * Where a branch stands compared with what the DC has sent it:
 * - not-synced:     the DC never sent this branch anything
 * - publish-failed: the DC's latest request did not reach Kafka completely (sync again)
 * - catching-up:    the branch has applied `done` of the `total` events of the DC's latest request
 * - purged:         the latest request was a purge, and the branch has no passes left
 * - in-sync:        the latest request is fully applied and the branch has as many passes as the DC
 * - differs:        fully applied, but the counts differ (e.g. the DC added passes the branch was never sent)
 */
export type Comparison =
  | { kind: 'not-synced' }
  | { kind: 'publish-failed' }
  | { kind: 'catching-up'; done: number; total: number }
  | { kind: 'purged' }
  | { kind: 'in-sync' }
  | { kind: 'differs'; dcPasses: number; branchPasses: number };

/**
 * @param branch its summary, asked with the seq range of `dc.lastRequest` (so `inRange` counts that request's
 *               events the branch has applied: one per pass)
 */
export function compare(dc: DcCheckpoint, branch: BranchSummary): Comparison {
  const differs = { kind: 'differs', dcPasses: dc.passes, branchPasses: branch.passes } as const;
  const last = dc.lastRequest;
  if (!last) {
    return branch.passes === 0 ? { kind: 'not-synced' } : differs;
  }
  if (last.status === 'FAILED') return { kind: 'publish-failed' };
  const done = last.status === 'PENDING' ? 0 : (branch.inRange ?? 0);
  if (done < last.eventCount) return { kind: 'catching-up', done, total: last.eventCount };
  if (last.kind === 'PURGE') return branch.passes === 0 ? { kind: 'purged' } : differs;
  return branch.passes === dc.passes ? { kind: 'in-sync' } : differs;
}
