/** Shapes of the JSON the DC and the branches return (through the dashboard's proxy, server.mjs). */

export interface ProductPrice {
  sku: string;
  name: string;
  price: number;
  currency: string;
}

/** DC: GET /data */
export interface DcData {
  site: 'DC';
  branches: DcBranch[];
}

export interface DcBranch {
  code: string;
  name: string;
  products: ProductPrice[];
}

/** Branch: GET /data */
export interface BranchData {
  site: string;
  products: ProductPrice[];
  lastSync: LastSync | null;
}

export interface LastSync {
  eventId: string;
  type: 'SNAPSHOT' | 'PURGE';
  occurredAt: string;
  receivedAt: string;
}

/** DC: GET /sync-log, POST /sync/{branch}, POST /purge/{branch} */
export interface SyncLogEntry {
  eventId: string;
  branch: string;
  type: 'SNAPSHOT' | 'PURGE';
  productCount: number;
  status: 'PENDING' | 'PUBLISHED' | 'FAILED';
  error: string | null;
  createdAt: string;
  publishedAt: string | null;
}

/** One panel's data: still loading, loaded, or the last attempt failed. */
export type Load<T> =
  | { state: 'loading' }
  | { state: 'ok'; data: T }
  | { state: 'error'; message: string };
