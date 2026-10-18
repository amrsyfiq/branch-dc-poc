import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, InjectionToken, signal } from '@angular/core';
import { finalize, Observable } from 'rxjs';
import { compare, Comparison } from './compare';
import { DashboardApi } from './dashboard-api';
import { BranchSummary, DcCheckpoint, DcSummary, Load, PassesData, SyncLogEntry } from './models';

/** How often every panel is reloaded. 0 = never (tests call refresh() themselves). */
export const POLL_INTERVAL_MS = new InjectionToken<number>('POLL_INTERVAL_MS', { factory: () => 2000 });
/** The branches shown, one column each (the proxy, server.mjs, knows the same ones). */
export const BRANCH_CODES = new InjectionToken<string[]>('BRANCH_CODES', { factory: () => ['A', 'B'] });

const KIND_LABEL: Record<SyncLogEntry['kind'], string> = { SYNC: 'Sync', PURGE: 'Purge', RUSH: 'Rush' };

/**
 * One page: the DC (every checkpoint's counts, with Sync / Purge / Rush buttons) next to each branch's own
 * copy, read through that branch's WSO2 gateway, plus the DC's request log. Every panel loads on its own, so
 * one unreachable branch does not hide the others. Each branch's progress is asked for with the seq range of
 * the DC's latest request for it, so the DC summary is loaded first.
 */
@Component({
  selector: 'app-root',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly api = inject(DashboardApi);
  readonly branchCodes = inject(BRANCH_CODES);
  readonly pollSeconds = inject(POLL_INTERVAL_MS) / 1000;

  readonly dc = signal<Load<DcSummary>>({ state: 'loading' });
  readonly dcPasses = signal<Record<string, Load<PassesData>>>({});
  readonly branchSummaries = signal<Record<string, Load<BranchSummary>>>({});
  /** The seq range each branch's latest summary was asked for ("" = none). */
  private readonly summaryRanges = signal<Record<string, string>>({});
  readonly branchPasses = signal<Record<string, Load<PassesData>>>({});
  readonly log = signal<Load<SyncLogEntry[]>>({ state: 'loading' });
  readonly lastRefresh = signal<Date | null>(null);
  /** How many passes the Rush buttons create. */
  readonly rushCount = signal(1000);
  /** The button being processed, e.g. "sync-A"; all buttons wait meanwhile. */
  readonly busy = signal<string | null>(null);
  readonly notice = signal<{ text: string; tone: 'ok' | 'error' } | null>(null);

  /**
   * Branch code -> where it stands compared with the DC (null while either side is unknown). A branch answer
   * that was asked for with another request's seq range (it was still on its way when the DC moved on) says
   * nothing about the newest request, so its progress counts as 0 until a matching answer arrives.
   */
  readonly comparisons = computed(() => {
    const result: Record<string, Comparison | null> = {};
    for (const code of this.branchCodes) {
      const dc = this.checkpoint(code);
      const branch = this.branchSummaries()[code];
      if (!dc || branch?.state !== 'ok') {
        result[code] = null;
        continue;
      }
      const current = this.summaryRanges()[code] === rangeKey(rangeOf(dc));
      result[code] = compare(dc, current ? branch.data : { ...branch.data, inRange: 0 });
    }
    return result;
  });

  private readonly inFlight = new Set<string>();

  constructor() {
    const interval = inject(POLL_INTERVAL_MS);
    this.refresh();
    if (interval > 0) {
      const id = setInterval(() => this.refresh(), interval);
      inject(DestroyRef).onDestroy(() => clearInterval(id));
    }
  }

  refresh(): void {
    this.load('dc', this.api.dcSummary(), (v) => {
      this.dc.set(v);
      this.loadBranchSummaries();
    });
    this.load('log', this.api.syncLog(10), (v) => this.log.set(v));
    for (const code of this.branchCodes) {
      this.load(`dc-passes-${code}`, this.api.dcPasses(code), (v) => this.dcPasses.update((all) => ({ ...all, [code]: v })));
      this.load(`branch-passes-${code}`, this.api.branchPasses(code),
        (v) => this.branchPasses.update((all) => ({ ...all, [code]: v })));
    }
    this.lastRefresh.set(new Date());
  }

  act(kind: 'sync' | 'purge' | 'rush', code: string): void {
    const count = this.rushCount();
    this.busy.set(`${kind}-${code}`);
    const call = kind === 'sync' ? this.api.sync(code) : kind === 'purge' ? this.api.purge(code) : this.api.rush(code, count);
    call.pipe(finalize(() => this.busy.set(null))).subscribe({
      next: (entry) => {
        const what = kind === 'sync' ? `Sync to branch ${code}` : kind === 'purge' ? `Purge of branch ${code}` : `Holiday rush at branch ${code}`;
        const events = kind === 'rush' ? `${entry.eventCount} new passes created and sent` : `${entry.eventCount} events sent`;
        this.notice.set(entry.status === 'FAILED'
          ? { tone: 'error', text: `${what}: the DC published only ${entry.publishedCount} of ${entry.eventCount} events. ${entry.error ?? ''}`.trim() }
          : { tone: 'ok', text: `${what}: ${events}. The branch applies them in a moment.` });
        this.refresh();
      },
      error: (e) => this.notice.set({ tone: 'error', text: `${KIND_LABEL[kind.toUpperCase() as SyncLogEntry['kind']]} ${code} failed: ${describe(e)}` }),
    });
  }

  setRushCount(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.rushCount.set(Number.isFinite(value) ? Math.min(20000, Math.max(1, Math.round(value))) : 1000);
  }

  /** "Sync", "Purge" or "Rush" for a request kind. */
  label(kind: string): string {
    return KIND_LABEL[kind as SyncLogEntry['kind']] ?? kind;
  }

  checkpoint(code: string): DcCheckpoint | undefined {
    const dc = this.dc();
    return dc.state === 'ok' ? dc.data.checkpoints.find((c) => c.code === code) : undefined;
  }

  /** The branch's progress with the DC's latest request, from 0 to 100. */
  percent(c: Comparison | null): number {
    return c?.kind === 'catching-up' && c.total > 0 ? Math.floor((100 * c.done) / c.total) : 100;
  }

  private loadBranchSummaries(): void {
    for (const code of this.branchCodes) {
      const cp = this.checkpoint(code);
      const range = cp ? rangeOf(cp) : undefined;
      this.load(`branch-${code}`, this.api.branchSummary(code, range), (v) => {
        this.branchSummaries.update((all) => ({ ...all, [code]: v }));
        this.summaryRanges.update((all) => ({ ...all, [code]: rangeKey(range) }));
      });
    }
  }

  /** Loads one panel, skipping the tick if its previous request has not answered yet. */
  private load<T>(key: string, source: Observable<T>, set: (value: Load<T>) => void): void {
    if (this.inFlight.has(key)) return;
    this.inFlight.add(key);
    source.pipe(finalize(() => this.inFlight.delete(key))).subscribe({
      next: (data) => set({ state: 'ok', data }),
      error: (e) => set({ state: 'error', message: describe(e) }),
    });
  }
}

/** The seq range of the DC's latest request for a checkpoint (one event per pass). */
function rangeOf(cp: DcCheckpoint): { from: number; to: number } | undefined {
  const last = cp.lastRequest;
  return last?.firstSeq != null && last.lastSeq != null ? { from: last.firstSeq, to: last.lastSeq } : undefined;
}

function rangeKey(range: { from: number; to: number } | undefined): string {
  return range ? `${range.from}-${range.to}` : '';
}

/** A short, readable reason for a failed call (the proxy and the services answer with {detail}). */
export function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'the dashboard server is not reachable';
    const detail = typeof e.error === 'object' && e.error ? (e.error.detail ?? e.error.description ?? e.error.message) : null;
    return `HTTP ${e.status}${detail ? `: ${detail}` : ''}`;
  }
  return String(e);
}
