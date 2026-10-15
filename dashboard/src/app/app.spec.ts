import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { App, POLL_INTERVAL_MS } from './app';
import { BranchData, DcData, ProductPrice, SyncLogEntry } from './models';

const p = (sku: string, price: number): ProductPrice => ({ sku, name: `Product ${sku}`, price, currency: 'MYR' });

const DC: DcData = {
  site: 'DC',
  branches: [
    { code: 'A', name: 'Branch A', products: [p('P-001', 42.5), p('P-002', 27.9)] },
    { code: 'B', name: 'Branch B', products: [p('P-001', 44), p('P-007', 9.8)] },
  ],
};
const BRANCH_A_IN_SYNC: BranchData = {
  site: 'A',
  products: [p('P-001', 42.5), p('P-002', 27.9)],
  lastSync: { eventId: 'e1', type: 'SNAPSHOT', occurredAt: '2026-10-15T10:00:00Z', receivedAt: '2026-10-15T10:00:01Z' },
};
const BRANCH_B_EMPTY: BranchData = { site: 'B', products: [], lastSync: null };
const entry = (over: Partial<SyncLogEntry> = {}): SyncLogEntry => ({
  eventId: 'e-new', branch: 'A', type: 'SNAPSHOT', productCount: 2, status: 'PUBLISHED', error: null,
  createdAt: '2026-10-15T10:05:00Z', publishedAt: '2026-10-15T10:05:00Z', ...over,
});

describe('Dashboard', () => {
  let fixture: ComponentFixture<App>;
  let http: HttpTestingController;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: POLL_INTERVAL_MS, useValue: 0 }],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(App);
    el = fixture.nativeElement;
  });

  afterEach(() => http.verify());

  /** Answers the requests of one refresh: DC data, sync log, branch A, branch B. */
  async function answerRefresh(a: BranchData | 'down', b: BranchData | 'down', log: SyncLogEntry[] = []) {
    http.expectOne('/api/dc/data').flush(DC);
    http.expectOne((r) => r.url === '/api/dc/sync-log').flush(log);
    for (const [code, data] of [['a', a], ['b', b]] as const) {
      const req = http.expectOne(`/api/branches/${code}/data`);
      if (data === 'down') {
        req.flush({ status: 502, detail: 'Upstream not reachable: ECONNREFUSED' }, { status: 502, statusText: 'Bad Gateway' });
      } else {
        req.flush(data);
      }
    }
    await fixture.whenStable();
  }

  const text = (testId: string) => el.querySelector(`[data-testid="${testId}"]`)?.textContent ?? '';

  it('shows the DC next to each branch, and whether each branch is in sync', async () => {
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY);

    expect(text('dc-A')).toContain('P-002');
    expect(text('dc-B')).toContain('P-007');
    expect(text('branch-A')).toContain('In sync with DC');
    expect(text('branch-A')).toContain('42.50 MYR');
    expect(text('branch-A')).toContain('Last event: Sync');
    expect(text('branch-B')).toContain('Not synced');
    expect(text('branch-B')).toContain('No products');
    expect(text('branch-B')).toContain('No event received yet');
  });

  it('highlights a branch whose copy differs from the DC', async () => {
    await answerRefresh({ ...BRANCH_A_IN_SYNC, products: [p('P-001', 40), p('P-009', 6.5)] }, BRANCH_B_EMPTY);

    expect(text('branch-A')).toContain('Differs from DC');
    expect(text('differences')).toContain('Missing: P-002');
    expect(text('differences')).toContain('Different: P-001');
    expect(text('differences')).toContain('Not in the DC: P-009');
    const rows = el.querySelectorAll('[data-testid="branch-A"] tbody tr');
    expect(rows[0].className).toBe('changed');
    expect(rows[1].className).toBe('extra');
  });

  it('keeps showing the other branches when one is unreachable', async () => {
    await answerRefresh('down', BRANCH_B_EMPTY);

    expect(text('branch-A')).toContain('Unreachable');
    expect(text('branch-A')).toContain('HTTP 502: Upstream not reachable: ECONNREFUSED');
    expect(text('branch-B')).toContain('Not synced');
    expect(text('dc')).toContain('Sync to A');
  });

  it('Sync asks the DC to send that branch its slice, then refreshes', async () => {
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY);

    (el.querySelector('[data-testid="sync-B"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect((el.querySelector('[data-testid="sync-A"]') as HTMLButtonElement).disabled).toBe(true); // busy

    const req = http.expectOne('/api/dc/sync/b');
    expect(req.request.method).toBe('POST');
    req.flush(entry({ branch: 'B', productCount: 2 }));
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY, [entry({ branch: 'B' })]);

    expect(text('notice')).toContain('Sync to branch B (2 products): event published');
    expect(text('log')).toContain('PUBLISHED');
    expect((el.querySelector('[data-testid="sync-A"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('Purge asks the DC to purge that branch', async () => {
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY);

    (el.querySelector('[data-testid="purge-A"]') as HTMLButtonElement).click();
    const req = http.expectOne('/api/dc/purge/a');
    expect(req.request.method).toBe('POST');
    req.flush(entry({ type: 'PURGE', productCount: 0 }));
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY);

    expect(text('notice')).toContain('Purge of branch A: event published');
  });

  it('says so when the DC could not publish the event to Kafka', async () => {
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY);

    (el.querySelector('[data-testid="sync-A"]') as HTMLButtonElement).click();
    http.expectOne('/api/dc/sync/a').flush(entry({ status: 'FAILED', error: 'Expiring 1 record(s)' }));
    await answerRefresh(BRANCH_A_IN_SYNC, BRANCH_B_EMPTY);

    expect(text('notice')).toContain('the DC could not publish the event. Expiring 1 record(s)');
  });
});
