import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { BranchData, DcData, SyncLogEntry } from './models';

/**
 * Calls the dashboard's own backend (server.mjs), which forwards to the DC and, with a read-only WSO2
 * token it keeps to itself, to each branch's gateway. The browser never talks to WSO2 directly.
 */
@Injectable({ providedIn: 'root' })
export class DashboardApi {
  private readonly http = inject(HttpClient);

  dcData(): Observable<DcData> {
    return this.http.get<DcData>('/api/dc/data');
  }

  syncLog(limit = 10): Observable<SyncLogEntry[]> {
    return this.http.get<SyncLogEntry[]>('/api/dc/sync-log', { params: { limit } });
  }

  /** A branch's own data, read through that branch's WSO2 gateway. */
  branchData(code: string): Observable<BranchData> {
    return this.http.get<BranchData>(`/api/branches/${code.toLowerCase()}/data`);
  }

  /** Ask the DC to send the branch its slice. */
  sync(code: string): Observable<SyncLogEntry> {
    return this.http.post<SyncLogEntry>(`/api/dc/sync/${code.toLowerCase()}`, null);
  }

  /** Ask the DC to tell the branch to delete its data. */
  purge(code: string): Observable<SyncLogEntry> {
    return this.http.post<SyncLogEntry>(`/api/dc/purge/${code.toLowerCase()}`, null);
  }
}
