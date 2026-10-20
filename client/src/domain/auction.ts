import { parseStatus, type Parsed } from './parse';

/**
 * FE-STATE-01: auction state model. Source of truth: backend AuctionStatus and BidService
 * (placeBid: ACTIVE only; buyNow: CREATED only, i.e. before bidding starts).
 */
export const AUCTION_STATUSES = [
  'CREATED', 'ACTIVE', 'WAITING_FOR_PAYMENT', 'SOLD', 'SALE_FAILED', 'EXPIRED', 'CANCELLED', 'COMPLETED',
] as const;
export type AuctionStatus = (typeof AUCTION_STATUSES)[number];

export const parseAuctionStatus = (raw: unknown): Parsed<AuctionStatus> => parseStatus(AUCTION_STATUSES, raw);

export const AUCTION_STATUS_LABELS: Record<AuctionStatus, string> = {
  CREATED: 'Ожидает начала',
  ACTIVE: 'Идут торги',
  WAITING_FOR_PAYMENT: 'Ожидание оплаты покупателем',
  SOLD: 'Продан',
  SALE_FAILED: 'Сделка не состоялась',
  EXPIRED: 'Завершён без покупателя',
  CANCELLED: 'Отменён',
  COMPLETED: 'Завершён',
};

export const auctionAcceptsBids = (s: Parsed<AuctionStatus>) => s.known && s.value === 'ACTIVE';

export const auctionAcceptsBuyNow = (s: Parsed<AuctionStatus>) => s.known && s.value === 'CREATED';

/** Every state after which nothing can be bought on the lot any more. */
export const auctionIsFinished = (s: Parsed<AuctionStatus>) =>
  s.known && !['CREATED', 'ACTIVE'].includes(s.value);
