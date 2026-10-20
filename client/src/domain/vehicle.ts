import { parseStatus, type Parsed } from './parse';

/**
 * FE-STATE-01: vehicle state model. Source of truth: backend VehicleStatus. A vehicle is created when
 * its diagnostic report is approved; only an APPROVED vehicle can be put up for auction.
 */
export const VEHICLE_STATUSES = [
  'DRAFT', 'PENDING_REVIEW', 'NEEDS_FIXES', 'APPROVED', 'IN_AUCTION', 'SOLD', 'WITHDRAWN', 'NEEDS_REDIAGNOSTIC',
] as const;
export type VehicleStatus = (typeof VEHICLE_STATUSES)[number];

export const parseVehicleStatus = (raw: unknown): Parsed<VehicleStatus> => parseStatus(VEHICLE_STATUSES, raw);

export const VEHICLE_STATUS_LABELS: Record<VehicleStatus, string> = {
  DRAFT: 'Черновик',
  PENDING_REVIEW: 'На проверке',
  NEEDS_FIXES: 'Требует исправлений',
  APPROVED: 'Одобрен',
  IN_AUCTION: 'На аукционе',
  SOLD: 'Продан',
  WITHDRAWN: 'Снят',
  NEEDS_REDIAGNOSTIC: 'Требует повторной диагностики',
};

export const vehicleCanBeAuctioned = (s: Parsed<VehicleStatus>) => s.known && s.value === 'APPROVED';
