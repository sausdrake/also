/**
 * Seller-violation catalogue codes (V37 seed, GET /admin/policy/violation-definitions) and their Russian names for the
 * admin UI. The backend only carries English descriptions; the code stays visible as a small reference next to the name.
 * An unknown future code falls back to the code itself - never hidden, never guessed.
 */
export const VIOLATION_LABELS: Record<string, string> = {
  SELLER_REFUSED_HANDOVER: 'Продавец отказался передать автомобиль или документы',
  SELLER_ADDITIONAL_PAYMENT_DEMAND: 'Продавец требует доплату сверх выигрышной цены',
  SELLER_HANDOVER_TERMS_CHANGED: 'Продавец изменил условия передачи',
  SELLER_UNREACHABLE: 'Продавец не выходит на связь',
  VEHICLE_CONDITION_CHANGED_AFTER_DIAGNOSTIC: 'Состояние автомобиля изменилось после диагностики',
  SELLER_WITHDREW_AFTER_WIN: 'Продавец отказался от продажи после торгов',
};

export const violationLabel = (code: string | null | undefined): string | null =>
  code ? VIOLATION_LABELS[code] ?? null : null;
