import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { App, POLL_INTERVAL_MS } from './app';
import { BranchSummary, DcSummary, PassesData, PassView, SyncLogEntry } from './models';

const pass = (applicationNo: string, over: Partial<PassView> = {}): PassView => ({
  applicationNo, passportNo: 'X1', fullName: `Traveller ${applicationNo}`, nationality: 'SGP', dateOfBirth: '1990-01-01',
  passType: 'TOURIST', validFrom: '2026-10-15', validUntil: '2026-10-31', status: 'APPROVED', ...over,
});
const request = (over: Partial<SyncLogEntry> = {}): SyncLogEntry => ({
  requestId: 'r-1', branch: 'A', kind: 'SYNC', eventCount: 24, firstSeq: 101, lastSeq: 124, status: 'PUBLISHED',
  publishedCount: 24, error: null, createdAt: '2026-10-16T10:05:00Z', publishedAt: '2026-10-16T10:05:00Z', ...over,
});
const DC: DcSummary = {
  site: 'DC',
  checkpoints: [
    { code: 'A', name: 'Checkpoint A - KL International Airport', kind: 'AIRPORT', passes: 24, approved: 22, revoked: 2,
      lastPublishedSeq: 124, lastRequest: request() },
    { code: 'B', name: 'Checkpoint B - Johor Bahru land crossing', kind: 'LAND', passes: 16, approved: 15, revoked: 1,
      lastPublishedSeq: 140, lastRequest: request({ requestId: 'r-2', branch: 'B', kind: 'PURGE', eventCount: 16, firstSeq: 125, lastSeq: 140 }) },
  ],
};
const summary = (site: string, passes: number, inRange: number | null): BranchSummary => ({
  site, passes, approved: passes, revoked: 0, tombstones: 0, maxSeq: 124, lastReceivedAt: '2026-10-16T10:05:01Z', inRange,
});
const page = (site: string, items: PassView[]): PassesData => ({ site, passes: { items, page: 0, size: 5, total: items.length } });

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

  /**
   * Answers the requests of one refresh. The branch summaries are asked for only after the DC summary has
   * arrived, with the seq range of the DC's latest request for that branch.
   */
  async function answerRefresh(a: BranchSummary | 'down', b: BranchSummary | 'down', log: SyncLogEntry[] = [], dc = DC) {
    http.expectOne('/api/dc/summary').flush(dc);
    http.expectOne((r) => r.url === '/api/dc/sync-log').flush(log);
    for (const code of ['a', 'b']) {
      http.expectOne((r) => r.url === '/api/dc/data' && r.params.get('branch') === code)
        .flush(page('DC', [pass(`APP-2026-0000${code === 'a' ? '24' : '40'}`)]));
      http.expectOne(`/api/branches/${code}/data?size=5`).flush(page(code.toUpperCase(), [pass(`APP-${code}-1`, { validToday: true })]));
    }
    for (const [code, data] of [['a', a], ['b', b]] as const) {
      const cp = dc.checkpoints.find((c) => c.code === code.toUpperCase())!;
      const req = http.expectOne((r) => r.url === `/api/branches/${code}/summary`);
      expect(req.request.params.get('from')).toBe(String(cp.lastRequest?.firstSeq));
      expect(req.request.params.get('to')).toBe(String(cp.lastRequest?.lastSeq));
      if (data === 'down') {
        req.flush({ status: 502, detail: 'Upstream not reachable: ECONNREFUSED' }, { status: 502, statusText: 'Bad Gateway' });
      } else {
        req.flush(data);
      }
    }
    await fixture.whenStable();
  }

  const text = (testId: string) => el.querySelector(`[data-testid="${testId}"]`)?.textContent ?? '';
  const click = (testId: string) => (el.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement).click();

  it('shows each checkpoint in the DC next to its own copy, and where each copy stands', async () => {
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    expect(text('dc-A')).toContain('Checkpoint A - KL International Airport');
    expect(text('dc-A')).toContain('24 passes · 22 approved · 2 revoked');
    expect(text('dc-A')).toContain('Latest: Sync of 24 events');
    expect(text('dc-B')).toContain('APP-2026-000040');
    expect(text('branch-A')).toContain('In sync with DC');
    expect(text('branch-A')).toContain('Traveller APP-a-1');
    expect(text('branch-A')).toContain('May enter');
    expect(text('branch-B')).toContain('Purged');
  });

  it('shows how far a branch is with the DC\'s latest request', async () => {
    await answerRefresh(summary('A', 10, 10), summary('B', 0, 16));

    expect(text('branch-A')).toContain('Receiving…');
    expect(text('progress')).toContain('Applied 10 of 24 events');
    expect((el.querySelector('[data-testid="branch-A"] progress') as HTMLProgressElement).value).toBe(41);
  });

  it('says when a branch has fewer passes than the DC although everything sent has arrived', async () => {
    const rushOnly = { ...DC, checkpoints: [{ ...DC.checkpoints[0], passes: 1024, lastRequest: request({ kind: 'RUSH', eventCount: 1000, firstSeq: 200, lastSeq: 1199 }) }, DC.checkpoints[1]] };
    await answerRefresh(summary('A', 1000, 1000), summary('B', 0, 16), [], rushOnly);

    expect(text('branch-A')).toContain('Differs from DC');
    expect(text('differences')).toContain('The DC has 1,024 passes for this checkpoint, the checkpoint has 1,000');
  });

  it("does not count an answer that was asked for with an older request's range", async () => {
    // tick 1: the DC's latest request for A is the sync (seq 101..124); A's answer is slow
    http.expectOne('/api/dc/summary').flush(DC);
    http.expectOne((r) => r.url === '/api/dc/sync-log').flush([]);
    for (const code of ['a', 'b']) {
      http.expectOne((r) => r.url === '/api/dc/data' && r.params.get('branch') === code).flush(page('DC', []));
      http.expectOne(`/api/branches/${code}/data?size=5`).flush(page(code.toUpperCase(), []));
    }
    const slowA = http.expectOne((r) => r.url === '/api/branches/a/summary');
    http.expectOne((r) => r.url === '/api/branches/b/summary').flush(summary('B', 0, 16));

    // tick 2: meanwhile a rush of 1,000 (seq 200..1199) was sent; A's summary is still in flight, so not asked again
    const rush = { ...DC, checkpoints: [{ ...DC.checkpoints[0], passes: 1024, lastRequest: request({ kind: 'RUSH', eventCount: 1000, firstSeq: 200, lastSeq: 1199 }) }, DC.checkpoints[1]] };
    fixture.componentInstance.refresh();
    http.expectOne('/api/dc/summary').flush(rush);
    http.expectOne((r) => r.url === '/api/dc/sync-log').flush([]);
    for (const code of ['a', 'b']) {
      http.expectOne((r) => r.url === '/api/dc/data' && r.params.get('branch') === code).flush(page('DC', []));
      http.expectOne(`/api/branches/${code}/data?size=5`).flush(page(code.toUpperCase(), []));
    }
    http.expectOne((r) => r.url === '/api/branches/b/summary').flush(summary('B', 0, 16));

    // the slow answer arrives: 24 rows in the SYNC's range. That says nothing about the rush.
    slowA.flush(summary('A', 24, 24));
    await fixture.whenStable();
    expect(text('progress')).toContain('Applied 0 of 1,000 events');
  });

  it('keeps showing the other panels when one branch is unreachable', async () => {
    await answerRefresh('down', summary('B', 0, 16));

    expect(text('branch-A')).toContain('Unreachable');
    expect(text('branch-A')).toContain('HTTP 502: Upstream not reachable: ECONNREFUSED');
    expect(text('branch-B')).toContain('Purged');
    expect(text('dc')).toContain('Sync A');
  });

  it('Sync asks the DC to send that branch its slice, then refreshes', async () => {
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    click('sync-B');
    await fixture.whenStable();
    expect((el.querySelector('[data-testid="rush-A"]') as HTMLButtonElement).disabled).toBe(true); // busy

    const req = http.expectOne('/api/dc/sync/b');
    expect(req.request.method).toBe('POST');
    req.flush(request({ branch: 'B', eventCount: 16 }));
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16), [request({ branch: 'B', eventCount: 16 })]);

    expect(text('notice')).toContain('Sync to branch B: 16 events sent');
    expect(text('log')).toContain('PUBLISHED');
    expect((el.querySelector('[data-testid="sync-A"]') as HTMLButtonElement).disabled).toBe(false);
  });

  it('Rush creates the chosen number of passes at that branch', async () => {
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));
    const input = el.querySelector('[data-testid="rush-count"]') as HTMLInputElement;
    input.value = '5000';
    input.dispatchEvent(new Event('change'));

    click('rush-A');
    const req = http.expectOne((r) => r.url === '/api/dc/rush/a');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('count')).toBe('5000');
    req.flush(request({ kind: 'RUSH', eventCount: 5000 }));
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    expect(text('notice')).toContain('Holiday rush at branch A: 5000 new passes created and sent');
  });

  it('Purge asks the DC to purge that branch', async () => {
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    click('purge-A');
    const req = http.expectOne('/api/dc/purge/a');
    expect(req.request.method).toBe('POST');
    req.flush(request({ kind: 'PURGE' }));
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    expect(text('notice')).toContain('Purge of branch A: 24 events sent');
  });

  it('says so when the DC could not publish the events to Kafka', async () => {
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    click('sync-A');
    http.expectOne('/api/dc/sync/a').flush(request({ status: 'FAILED', publishedCount: 0, error: 'Topic dc.branch-a not present in metadata' }));
    await answerRefresh(summary('A', 24, 24), summary('B', 0, 16));

    expect(text('notice')).toContain('the DC published only 0 of 24 events. Topic dc.branch-a not present in metadata');
  });
});
