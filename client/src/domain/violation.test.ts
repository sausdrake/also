import { describe, expect, it } from 'vitest';
import seed from '../../../src/main/resources/db/migration/V37__violation_definition.sql?raw';
import { VIOLATION_LABELS, violationLabel } from './violation';

describe('violation labels', () => {
  const seeded = [...new Set(seed.match(/'[A-Z][A-Z_]{5,}'/g)!.map((c) => c.slice(1, -1)))];

  it('every seeded catalogue code has a Russian name', () => {
    expect(seeded.length).toBe(6);
    for (const code of seeded) expect(VIOLATION_LABELS[code], code).toMatch(/[а-я]/);
  });

  it('unknown or missing codes are not guessed', () => {
    expect(violationLabel('SOMETHING_NEW')).toBeNull();
    expect(violationLabel(null)).toBeNull();
  });
});
