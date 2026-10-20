import { describe, expect, it } from 'vitest';
import { labelOf } from './parse';
import {
  DIAGNOSTIC_STATUS_LABELS, diagnosticianCanEdit, diagnosticianCanRework, needsTemplate, ownerCanCancel,
  parseDiagnosticRequestStatus,
} from './diagnosticRequest';
import { kycCanSubmit, kycIsVerified, parseIdentityStatus } from './identity';
import { parseWithdrawalStatus, withdrawalAdminActions, withdrawalIsFinal } from './withdrawal';
import { auctionAcceptsBids, auctionAcceptsBuyNow, auctionIsFinished, parseAuctionStatus } from './auction';
import { parseVehicleStatus, vehicleCanBeAuctioned } from './vehicle';

const req = (status: string, templateId: number | null = null) => ({ status: parseDiagnosticRequestStatus(status), templateId });

describe('FE-STATE-01: allowed actions follow the backend rules', () => {
  it('diagnostic request: cancel only PENDING; edit only IN_PROGRESS with a bound template; rework only REJECTED', () => {
    expect(['PENDING', 'IN_PROGRESS', 'COMPLETED', 'APPROVED', 'REJECTED'].filter(s => ownerCanCancel(req(s)))).toEqual(['PENDING']);
    expect(needsTemplate(req('IN_PROGRESS', null))).toBe(true);
    expect(needsTemplate(req('IN_PROGRESS', 7))).toBe(false);
    expect(needsTemplate(req('PENDING', null))).toBe(false);
    expect(diagnosticianCanEdit(req('IN_PROGRESS', null))).toBe(false);
    expect(diagnosticianCanEdit(req('IN_PROGRESS', 7))).toBe(true);
    for (const s of ['PENDING', 'COMPLETED', 'APPROVED', 'REJECTED']) expect(diagnosticianCanEdit(req(s, 7))).toBe(false);
    expect(['PENDING', 'IN_PROGRESS', 'COMPLETED', 'APPROVED', 'REJECTED'].filter(s => diagnosticianCanRework(req(s, 7)))).toEqual(['REJECTED']);
  });

  it('KYC: submit when nothing submitted or REJECTED/REVOKED; only VERIFIED unlocks gated operations', () => {
    expect(kycCanSubmit(null)).toBe(true);
    expect(['PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'REVOKED'].filter(s => kycCanSubmit(parseIdentityStatus(s)))).toEqual(['REJECTED', 'REVOKED']);
    expect(['PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'REVOKED'].filter(s => kycIsVerified(parseIdentityStatus(s)))).toEqual(['VERIFIED']);
    expect(kycIsVerified(null)).toBe(false);
  });

  it('withdrawal: PENDING -> processing|reject, PROCESSING -> paid|reject, PAID/REJECTED final', () => {
    expect(withdrawalAdminActions(parseWithdrawalStatus('PENDING'))).toEqual(['processing', 'reject']);
    expect(withdrawalAdminActions(parseWithdrawalStatus('PROCESSING'))).toEqual(['paid', 'reject']);
    expect(withdrawalAdminActions(parseWithdrawalStatus('PAID'))).toEqual([]);
    expect(withdrawalAdminActions(parseWithdrawalStatus('REJECTED'))).toEqual([]);
    expect(['PENDING', 'PROCESSING', 'PAID', 'REJECTED'].filter(s => withdrawalIsFinal(parseWithdrawalStatus(s)))).toEqual(['PAID', 'REJECTED']);
  });

  it('auction: bids only ACTIVE, Buy Now only CREATED; vehicle auctionable only APPROVED', () => {
    const all = ['CREATED', 'ACTIVE', 'WAITING_FOR_PAYMENT', 'SOLD', 'SALE_FAILED', 'EXPIRED', 'CANCELLED', 'COMPLETED'];
    expect(all.filter(s => auctionAcceptsBids(parseAuctionStatus(s)))).toEqual(['ACTIVE']);
    expect(all.filter(s => auctionAcceptsBuyNow(parseAuctionStatus(s)))).toEqual(['CREATED']);
    expect(all.filter(s => !auctionIsFinished(parseAuctionStatus(s)))).toEqual(['CREATED', 'ACTIVE']);
    expect(['DRAFT', 'APPROVED', 'IN_AUCTION', 'NEEDS_REDIAGNOSTIC'].filter(s => vehicleCanBeAuctioned(parseVehicleStatus(s)))).toEqual(['APPROVED']);
  });
});

describe('FE-STATE-01 adversarial: unknown, malformed and hostile input never becomes a known state or an action', () => {
  const hostile: unknown[] = ['', 'active', ' ACTIVE', 'ACTIVE ', 'DRAFT', '__proto__', 'constructor', 'toString', null, undefined, 42, {}, ['ACTIVE']];

  it('parsers report unknown values with the raw value kept', () => {
    for (const raw of hostile) {
      for (const parse of [parseDiagnosticRequestStatus, parseIdentityStatus, parseWithdrawalStatus, parseVehicleStatus]) {
        const p = parse(raw);
        if (raw === 'DRAFT' && parse === parseVehicleStatus) continue;   // a real vehicle state
        expect(p.known).toBe(false);
      }
    }
    expect(parseAuctionStatus('__proto__')).toEqual({ known: false, raw: '__proto__' });
  });

  it('an unknown state grants no action', () => {
    for (const raw of hostile) {
      expect(ownerCanCancel({ status: parseDiagnosticRequestStatus(raw), templateId: null })).toBe(false);
      expect(diagnosticianCanEdit({ status: parseDiagnosticRequestStatus(raw), templateId: 1 })).toBe(false);
      expect(diagnosticianCanRework({ status: parseDiagnosticRequestStatus(raw), templateId: 1 })).toBe(false);
      expect(kycIsVerified(parseIdentityStatus(raw))).toBe(false);
      expect(kycCanSubmit(parseIdentityStatus(raw))).toBe(false);
      expect(withdrawalAdminActions(parseWithdrawalStatus(raw))).toEqual([]);
      expect(auctionAcceptsBids(parseAuctionStatus(raw))).toBe(false);
      expect(auctionAcceptsBuyNow(parseAuctionStatus(raw))).toBe(false);
    }
  });

  it('an unknown state is shown with its raw code, never as a wrong label', () => {
    expect(labelOf(DIAGNOSTIC_STATUS_LABELS.owner, parseDiagnosticRequestStatus('ARCHIVED'))).toBe('ARCHIVED');
    expect(labelOf(DIAGNOSTIC_STATUS_LABELS.owner, parseDiagnosticRequestStatus('COMPLETED'))).toBe('На проверке');
  });
});
