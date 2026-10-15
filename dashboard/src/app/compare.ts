import { ProductPrice } from './models';

/**
 * How a branch's copy compares with its slice in the DC:
 * - in-sync:    same products at the same prices
 * - not-synced: the DC has products for the branch, the branch has none (never synced, or purged)
 * - differs:    some products are missing, extra, or have another price/name
 */
export type Comparison =
  | { kind: 'in-sync' }
  | { kind: 'not-synced'; expected: number }
  | { kind: 'differs'; missing: string[]; extra: string[]; changed: string[] };

export function compare(dcSlice: ProductPrice[], branch: ProductPrice[]): Comparison {
  if (branch.length === 0 && dcSlice.length > 0) {
    return { kind: 'not-synced', expected: dcSlice.length };
  }
  const inDc = new Map(dcSlice.map((p) => [p.sku, p]));
  const inBranch = new Map(branch.map((p) => [p.sku, p]));
  const missing = dcSlice.filter((p) => !inBranch.has(p.sku)).map((p) => p.sku);
  const extra = branch.filter((p) => !inDc.has(p.sku)).map((p) => p.sku);
  const changed = branch
    .filter((p) => {
      const dc = inDc.get(p.sku);
      return dc !== undefined && (dc.price !== p.price || dc.currency !== p.currency || dc.name !== p.name);
    })
    .map((p) => p.sku);
  return missing.length + extra.length + changed.length === 0
    ? { kind: 'in-sync' }
    : { kind: 'differs', missing, extra, changed };
}
