import { describe, expect, it } from 'vitest';
import { NOTIFICATION_TYPES, notificationCategory, notificationLink, parseNotification } from './notification';

const n = (type: string, referenceId: number | null = 5, referenceType: string | null = null) => ({ type, referenceId, referenceType });

describe('FE-NOTIF-01: deep links follow the recipient fixed by the type', () => {
  it('auction notifications open the lot', () => {
    for (const t of ['OUTBID', 'AUCTION_WON', 'AUCTION_LOST', 'AUCTION_LEADER', 'NEW_BID_ON_VEHICLE', 'AUCTION_ENDING_SOON',
      'AUCTION_ENDED', 'AUCTION_COMPLETED', 'AUCTION_CANCELLED']) {
      expect(notificationLink(n(t, 42, 'AUCTION'))).toBe('/auction/42');
    }
  });

  it('diagnostic notifications go to the page of their recipient - never /diagnostic for a seller', () => {
    expect(notificationLink(n('DIAG_ACCEPTED', 7, 'DIAGNOSTIC'))).toBe('/profile/requests');
    expect(notificationLink(n('DIAG_REPORT_APPROVED', 7, 'DIAGNOSTIC'))).toBe('/profile/requests');
    expect(notificationLink(n('DIAG_REDIAGNOSTIC_ORDERED', 7, 'DIAGNOSTIC'))).toBe('/profile/requests');
    expect(notificationLink(n('DIAG_REPORT_SUBMITTED', 7, 'DIAGNOSTIC'))).toBe('/admin/diagnostic/reports');
    expect(notificationLink(n('DIAG_REPORT_REJECTED', 7, 'DIAGNOSTIC'))).toBe('/diagnostic/reports');
  });

  it('deposit notifications (no reference) open the deposit tab', () => {
    for (const t of ['DEPOSIT_FROZEN', 'DEPOSIT_UNFROZEN', 'DEPOSIT_RELEASED']) expect(notificationLink(n(t, null, null))).toBe('/profile');
  });

  it('every known type has a category other than SYSTEM (except the account-level privacy answer)', () => {
    for (const t of NOTIFICATION_TYPES) {
      if (t === 'PRIVACY_REQUEST_ANSWERED') continue;
      expect(notificationCategory({ type: t })).not.toBe('SYSTEM');
    }
  });

  it('PRIVACY-REQ-01: the answer to a data request is a system message leading to the profile data tab', () => {
    expect(notificationCategory({ type: 'PRIVACY_REQUEST_ANSWERED' })).toBe('SYSTEM');
    expect(notificationLink(n('PRIVACY_REQUEST_ANSWERED', 3, 'PRIVACY_REQUEST'))).toBe('/profile/privacy');
  });
});

describe('FE-NOTIF-01 adversarial: links are never guessed', () => {
  it('an auction type without a valid auction reference has no link', () => {
    expect(notificationLink(n('OUTBID', null, 'AUCTION'))).toBeNull();
    expect(notificationLink(n('OUTBID', 0, 'AUCTION'))).toBeNull();
    expect(notificationLink(n('OUTBID', -3, 'AUCTION'))).toBeNull();
    expect(notificationLink(n('OUTBID', 5, 'DIAGNOSTIC'))).toBeNull();
  });

  it('unknown types have no link and fall into SYSTEM, even with an AUCTION reference', () => {
    expect(notificationLink(n('SOMETHING_NEW', 5, 'AUCTION'))).toBeNull();
    expect(notificationCategory({ type: 'SOMETHING_NEW' })).toBe('SYSTEM');
    expect(notificationCategory({ type: '__proto__' })).toBe('SYSTEM');
    expect(notificationLink(n('constructor', 5, 'AUCTION'))).toBeNull();
  });

  it('the message text is not parsed: "#5" in a diagnostic message does not become /auction/5', () => {
    const viaMessage = { ...n('DIAG_REPORT_SUBMITTED', null, null), message: 'Отчёт по заявке #5 отправлен на проверку.' };
    expect(notificationLink(viaMessage)).toBe('/admin/diagnostic/reports');
    expect(notificationLink({ ...n('SOMETHING', null, null), message: 'аукцион #9' } as never)).toBeNull();
  });
});

describe('FE-NOTIF-01: parseNotification', () => {
  it('keeps a well-formed DTO', () => {
    expect(parseNotification({ id: 3, userId: 9, type: 'OUTBID', title: 'T', message: 'M', read: false,
      createdAt: '2026-10-20T10:00:00', referenceId: 42, referenceType: 'AUCTION' })).toEqual({
      id: 3, type: 'OUTBID', title: 'T', message: 'M', read: false, createdAt: '2026-10-20T10:00:00', referenceId: 42, referenceType: 'AUCTION',
    });
  });

  it.each([null, undefined, 'x', 5, [], {}, { id: '3', type: 'OUTBID' }, { id: 0, type: 'OUTBID' }, { id: 1.5, type: 'OUTBID' },
    { id: 3 }, { id: 3, type: '' }, { id: 3, type: 7 }])('drops an unusable payload %#', raw => {
    expect(parseNotification(raw)).toBeNull();
  });

  it('degrades bad fields: non-boolean read counts as unread, bad reference is dropped, texts default to empty', () => {
    expect(parseNotification({ id: 3, type: 'OUTBID', read: 'true', referenceId: '42', title: null, message: 5, createdAt: 1 }))
      .toEqual({ id: 3, type: 'OUTBID', title: '', message: '', read: false, createdAt: null, referenceId: null, referenceType: null });
  });
});
