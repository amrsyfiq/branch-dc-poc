import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, InjectionToken, signal } from '@angular/core';
import { finalize, Observable } from 'rxjs';
import { compare, Comparison } from './compare';
import { DashboardApi } from './dashboard-api';
import { BranchData, DcData, Load, ProductPrice, SyncLogEntry } from './models';

/** How often every panel is reloaded. 0 = never (tests call refresh() themselves). */
export const POLL_INTERVAL_MS = new InjectionToken<number>('POLL_INTERVAL_MS', { factory: () => 2000 });
/** The branches shown, one column each (the proxy, server.mjs, knows the same ones). */
export const BRANCH_CODES = new InjectionToken<string[]>('BRANCH_CODES', { factory: () => ['A', 'B'] });

/**
 * One page: the DC (all branches, with Sync / Purge buttons) next to each branch's own copy, read
 * through that branch's WSO2 gateway, plus the DC's sync log. Every panel loads on its own, so one
 * unreachable branch does not hide the others.
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

  readonly dc = signal<Load<DcData>>({ state: 'loading' });
  readonly log = signal<Load<SyncLogEntry[]>>({ state: 'loading' });
  readonly branches = signal<Record<string, Load<BranchData>>>({});
  readonly lastRefresh = signal<Date | null>(null);
  /** The button being processed, e.g. "sync-A"; all buttons wait meanwhile. */
  readonly busy = signal<string | null>(null);
  readonly notice = signal<{ text: string; tone: 'ok' | 'error' } | null>(null);

  /** Branch code -> how its copy compares with the DC (null while either side is unknown). */
  readonly comparisons = computed(() => {
    const dc = this.dc();
    const result: Record<string, Comparison | null> = {};
    for (const code of this.branchCodes) {
      const branch = this.branches()[code];
      const slice = dc.state === 'ok' ? dc.data.branches.find((b) => b.code === code)?.products : undefined;
      result[code] = slice && branch?.state === 'ok' ? compare(slice, branch.data.products) : null;
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
    this.load('dc', this.api.dcData(), (v) => this.dc.set(v));
    this.load('log', this.api.syncLog(10), (v) => this.log.set(v));
    for (const code of this.branchCodes) {
      this.load(`branch-${code}`, this.api.branchData(code), (v) => this.branches.update((all) => ({ ...all, [code]: v })));
    }
    this.lastRefresh.set(new Date());
  }

  act(kind: 'sync' | 'purge', code: string): void {
    this.busy.set(`${kind}-${code}`);
    (kind === 'sync' ? this.api.sync(code) : this.api.purge(code))
      .pipe(finalize(() => this.busy.set(null)))
      .subscribe({
        next: (entry) => {
          const what = kind === 'sync' ? `Sync to branch ${code} (${entry.productCount} products)` : `Purge of branch ${code}`;
          this.notice.set(entry.status === 'FAILED'
            ? { tone: 'error', text: `${what}: the DC could not publish the event. ${entry.error ?? ''}`.trim() }
            : { tone: 'ok', text: `${what}: event ${entry.status.toLowerCase()}. The branch applies it in a moment.` });
          this.refresh();
        },
        error: (e) => this.notice.set({ tone: 'error', text: `${kind === 'sync' ? 'Sync' : 'Purge'} ${code} failed: ${describe(e)}` }),
      });
  }

  sliceOf(dc: DcData, code: string): ProductPrice[] {
    return dc.branches.find((b) => b.code === code)?.products ?? [];
  }

  /** Row highlight in a branch table: a product with another price than the DC, or one the DC doesn't send. */
  rowClass(code: string, sku: string): string {
    const c = this.comparisons()[code];
    if (c?.kind !== 'differs') return '';
    return c.changed.includes(sku) ? 'changed' : c.extra.includes(sku) ? 'extra' : '';
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

/** A short, readable reason for a failed call (the proxy and the services answer with {detail}). */
export function describe(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'the dashboard server is not reachable';
    const detail = typeof e.error === 'object' && e.error ? (e.error.detail ?? e.error.description ?? e.error.message) : null;
    return `HTTP ${e.status}${detail ? `: ${detail}` : ''}`;
  }
  return String(e);
}
