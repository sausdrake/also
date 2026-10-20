/**
 * FE-STATE-01: shared parsing rule of the domain state models. A backend value the frontend does
 * not know is reported as `unknown` (with the raw value kept), never silently coerced into a known
 * state - a new backend state must show up as a visible gap, not as a wrong label or action.
 */
export type Parsed<T extends string> = { known: true; value: T } | { known: false; raw: string };

export function parseStatus<T extends string>(values: readonly T[], raw: unknown): Parsed<T> {
  // Only a string can be a backend status; anything else (null, a number, ['ACTIVE'] whose String()
  // would be 'ACTIVE') is unknown.
  if (typeof raw !== 'string') return { known: false, raw: String(raw) };
  return (values as readonly string[]).includes(raw) ? { known: true, value: raw as T } : { known: false, raw };
}

/** Label of a parsed status; an unknown one is shown as its raw code so it is never hidden. */
export function labelOf<T extends string>(labels: Record<T, string>, parsed: Parsed<T>): string {
  return parsed.known ? labels[parsed.value] : parsed.raw;
}
