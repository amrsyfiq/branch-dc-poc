/** Shapes of the JSON the DC and the branches return (through the dashboard's proxy, server.mjs). */

export interface PassView {
  applicationNo: string;
  passportNo: string;
  fullName: string;
  nationality: string;
  dateOfBirth: string;
  passType: 'TOURIST' | 'BUSINESS' | 'STUDENT' | 'TRANSIT';
  validFrom: string;
  validUntil: string;
  status: 'APPROVED' | 'REVOKED';
  /** Branch only: may the traveller enter today? */
  validToday?: boolean;
}

export interface PageView<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** DC: GET /summary */
export interface DcSummary {
  site: 'DC';
  checkpoints: DcCheckpoint[];
}

export interface DcCheckpoint {
  code: string;
  name: string;
  kind: 'AIRPORT' | 'LAND' | 'SEA';
  passes: number;
  approved: number;
  revoked: number;
  /** The newest sequence number the DC has published to this branch. */
  lastPublishedSeq: number | null;
  lastRequest: SyncLogEntry | null;
}

/** DC: GET /data?branch=X and branch: GET /data */
export interface PassesData {
  site: string;
  passes: PageView<PassView>;
}

/** Branch: GET /summary?from=&to= */
export interface BranchSummary {
  site: string;
  passes: number;
  approved: number;
  revoked: number;
  tombstones: number;
  maxSeq: number | null;
  lastReceivedAt: string | null;
  /** Rows holding an event of the requested seq range: how far the branch is with the DC's latest request. */
  inRange: number | null;
}

/** DC: GET /sync-log, POST /sync|/purge|/rush/{branch} */
export interface SyncLogEntry {
  requestId: string;
  branch: string;
  kind: 'SYNC' | 'PURGE' | 'RUSH';
  eventCount: number;
  firstSeq: number | null;
  lastSeq: number | null;
  status: 'PENDING' | 'PUBLISHED' | 'FAILED';
  publishedCount: number;
  error: string | null;
  createdAt: string;
  publishedAt: string | null;
}

/** One panel's data: still loading, loaded, or the last attempt failed. */
export type Load<T> =
  | { state: 'loading' }
  | { state: 'ok'; data: T }
  | { state: 'error'; message: string };
