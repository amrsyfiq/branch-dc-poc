import { compare } from './compare';
import { ProductPrice } from './models';

const p = (sku: string, price: number, name = sku): ProductPrice => ({ sku, name, price, currency: 'MYR' });

describe('compare (branch copy vs its slice in the DC)', () => {
  it('is in sync when products and prices match, in any order', () => {
    expect(compare([p('P-1', 1), p('P-2', 2)], [p('P-2', 2), p('P-1', 1)])).toEqual({ kind: 'in-sync' });
  });

  it('is in sync when both are empty', () => {
    expect(compare([], [])).toEqual({ kind: 'in-sync' });
  });

  it('is not synced when the branch has nothing but the DC has products', () => {
    expect(compare([p('P-1', 1), p('P-2', 2)], [])).toEqual({ kind: 'not-synced', expected: 2 });
  });

  it('lists missing, extra and changed products', () => {
    const result = compare(
      [p('P-1', 1), p('P-2', 2), p('P-3', 3)],
      [p('P-1', 1), p('P-2', 2.5), p('P-9', 9)],
    );
    expect(result).toEqual({ kind: 'differs', missing: ['P-3'], extra: ['P-9'], changed: ['P-2'] });
  });

  it('notices a changed name or currency, not only price', () => {
    expect(compare([p('P-1', 1, 'Coffee')], [p('P-1', 1, 'Tea')])).toMatchObject({ kind: 'differs', changed: ['P-1'] });
    expect(compare([p('P-1', 1)], [{ ...p('P-1', 1), currency: 'SGD' }])).toMatchObject({ kind: 'differs', changed: ['P-1'] });
  });
});
