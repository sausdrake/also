import { describe, expect, it } from 'vitest';
import { NOTIFICATION_TYPES } from './notification';

/**
 * FE-NOTIF-01 contract: every notification type the backend sends (NotificationService.send call sites)
 * is known to the frontend, and the frontend knows no type the backend never sends. Reads the Java
 * sources directly (vitest server.fs.allow '..'), like domain.contract.test.ts.
 */
const javaSources = import.meta.glob('../../../src/main/java/**/*.java', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

/** Types passed as the 2nd argument of `notificationService.send(` (comments stripped first). */
export function sentNotificationTypes(source: string): string[] {
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
  const out: string[] = [];
  for (const m of code.matchAll(/notificationService\s*\.\s*send\s*\(\s*[^,()]+(?:\([^()]*\))?\s*,\s*"([A-Z0-9_]+)"/g)) out.push(m[1]);
  return out;
}

describe('FE-NOTIF-01: notification types match the backend senders', () => {
  it('the parser finds types in multi-line calls with method-call recipients and ignores comments', () => {
    const sample = `
      // notificationService.send(x, "COMMENTED_OUT", ...)
      /* notificationService.send(x, "BLOCK_COMMENT", ...) */
      notificationService.send(request.getUserId(), "DIAG_X",
          "t", "m", id, "DIAGNOSTIC");
      notificationService.send(winnerId, "AUCTION_Y", "t", "m");`;
    expect(sentNotificationTypes(sample)).toEqual(['DIAG_X', 'AUCTION_Y']);
  });

  it('frontend NOTIFICATION_TYPES == the set of types the backend sends', () => {
    expect(Object.keys(javaSources).length).toBeGreaterThan(50);
    const backend = new Set(Object.values(javaSources).flatMap(sentNotificationTypes));
    expect(backend.size).toBeGreaterThan(10);
    expect([...backend].sort()).toEqual([...NOTIFICATION_TYPES].sort());
  });
});
