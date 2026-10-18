import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BranchSummary, DcSummary, PassesData, SyncLogEntry } from './models';

/**
 * Calls the dashboard's own backend (server.mjs), which forwards to the DC and, with a read-only WSO2
 * token it keeps to itself, to each branch's gateway. The browser never talks to WSO2 directly.
 */
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  dcSummary(): Observable<DcSummary> {
    return this.http.get<DcSummary>('/api/dc/summary');
  }

  /** The DC's newest passes for one branch. */
  dcPasses(code: string, size = 5): Observable<PassesData> {
    return this.http.get<PassesData>('/api/dc/data', { params: { branch: code.toLowerCase(), size } });
  }

  syncLog(limit = 10): Observable<SyncLogEntry[]> {
    return this.http.get<SyncLogEntry[]>('/api/dc/sync-log', { params: { limit } });
  }

  /** A branch's own counts, read through that branch's WSO2 gateway; with a seq range, also its progress. */
  branchSummary(code: string, range?: { from: number; to: number }): Observable<BranchSummary> {
    const params = range ? new HttpParams().set('from', range.from).set('to', range.to) : undefined;
    return this.http.get<BranchSummary>(`/api/branches/${code.toLowerCase()}/summary`, { params });
  }

  /** A branch's most recently changed passes, through its gateway. */
  branchPasses(code: string, size = 5): Observable<PassesData> {
    return this.http.get<PassesData>(`/api/branches/${code.toLowerCase()}/data`, { params: { size } });
  }

  /** Ask the DC to send the branch every pass of its slice. */
  sync(code: string): Observable<SyncLogEntry> {
    return this.http.post<SyncLogEntry>(`/api/dc/sync/${code.toLowerCase()}`, null);
  }

  /** Ask the DC to tell the branch to delete every pass of its slice. */
  purge(code: string): Observable<SyncLogEntry> {
    return this.http.post<SyncLogEntry>(`/api/dc/purge/${code.toLowerCase()}`, null);
  }

  /** The holiday rush: the DC creates `count` new passes for the branch and sends them. */
  rush(code: string, count: number): Observable<SyncLogEntry> {
    return this.http.post<SyncLogEntry>(`/api/dc/rush/${code.toLowerCase()}`, null, { params: { count } });
  }
}
