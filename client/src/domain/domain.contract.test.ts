import { describe, expect, it } from 'vitest';
import diagnosticRequestJava from '../../../src/main/java/com/example/autoauction/diagnostic/domain/DiagnosticRequest.java?raw';
import identityJava from '../../../src/main/java/com/example/autoauction/identity/domain/IdentityVerificationStatus.java?raw';
import withdrawalJava from '../../../src/main/java/com/example/autoauction/deposit/domain/WithdrawalRequestStatus.java?raw';
import auctionJava from '../../../src/main/java/com/example/autoauction/auction/domain/AuctionStatus.java?raw';
import vehicleJava from '../../../src/main/java/com/example/autoauction/vehicle/domain/VehicleStatus.java?raw';
import { DIAGNOSTIC_REQUEST_STATUSES, DIAGNOSTIC_STATUS_LABELS } from './diagnosticRequest';
import { IDENTITY_STATUSES, IDENTITY_STATUS_LABELS } from './identity';
import { WITHDRAWAL_STATUSES, WITHDRAWAL_STATUS_LABELS } from './withdrawal';
import { AUCTION_STATUSES, AUCTION_STATUS_LABELS } from './auction';
import { VEHICLE_STATUSES, VEHICLE_STATUS_LABELS } from './vehicle';

/**
 * FE-STATE-01 contract test: the frontend state lists are compared with the backend enum source
 * itself, so a state added to or removed from the backend fails here instead of becoming an
 * unlabelled or impossible UI state.
 */
function javaEnumConstants(source: string, enumName: string): string[] {
  const start = source.indexOf(`enum ${enumName}`);
  if (start < 0) throw new Error(`enum ${enumName} not found`);
  // comments first: Javadoc on the constants may itself contain ';' or '}'
  const body = source.slice(source.indexOf('{', start) + 1).replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const constantsPart = body.split(/;|}/)[0];
  return constantsPart
    .replace(/@\w+(\([^)]*\))?/g, '')
    .replace(/\([^)]*\)/g, '')
    .split(',')
    .map(part => part.trim().match(/^([A-Z][A-Z0-9_]*)/)?.[1])
    .filter((name): name is string => Boolean(name));
}

const cases = [
  { name: 'DiagnosticRequestStatus', source: diagnosticRequestJava, frontend: DIAGNOSTIC_REQUEST_STATUSES },
  { name: 'IdentityVerificationStatus', source: identityJava, frontend: IDENTITY_STATUSES },
  { name: 'WithdrawalRequestStatus', source: withdrawalJava, frontend: WITHDRAWAL_STATUSES },
  { name: 'AuctionStatus', source: auctionJava, frontend: AUCTION_STATUSES },
  { name: 'VehicleStatus', source: vehicleJava, frontend: VEHICLE_STATUSES },
];

describe('FE-STATE-01: frontend state lists match the backend enums', () => {
  for (const c of cases) {
    it(`${c.name} has exactly the backend constants`, () => {
      const backend = javaEnumConstants(c.source, c.name);
      expect(backend.length).toBeGreaterThan(0);
      expect([...c.frontend].sort()).toEqual([...backend].sort());
    });
  }

  it('every state has a label in every perspective', () => {
    const labelled: [readonly string[], Record<string, string>][] = [
      [DIAGNOSTIC_REQUEST_STATUSES, DIAGNOSTIC_STATUS_LABELS.owner],
      [DIAGNOSTIC_REQUEST_STATUSES, DIAGNOSTIC_STATUS_LABELS.diagnostician],
      [IDENTITY_STATUSES, IDENTITY_STATUS_LABELS],
      [WITHDRAWAL_STATUSES, WITHDRAWAL_STATUS_LABELS],
      [AUCTION_STATUSES, AUCTION_STATUS_LABELS],
      [VEHICLE_STATUSES, VEHICLE_STATUS_LABELS],
    ];
    for (const [states, labels] of labelled) {
      expect(Object.keys(labels).sort()).toEqual([...states].sort());
      for (const s of states) expect(labels[s].trim().length).toBeGreaterThan(0);
    }
  });

  it('the parser of the Java enum source is itself correct (guards the guard)', () => {
    const sample = 'public enum X {\n  // comment, with comma; and }\n  A, /* B, ; } */ C_D("x, y"),\n  /** doc; } */\n  E;\n  private int f;\n}';
    expect(javaEnumConstants(sample, 'X')).toEqual(['A', 'C_D', 'E']);
    expect(javaEnumConstants('enum Y { P, Q }', 'Y')).toEqual(['P', 'Q']);
    expect(javaEnumConstants('enum Z {\n  A,\n  @Deprecated\n  B,\n  @SuppressWarnings("x") C\n}', 'Z')).toEqual(['A', 'B', 'C']);
  });
});
