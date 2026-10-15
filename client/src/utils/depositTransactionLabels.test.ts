import { describe, expect, it } from 'vitest';
import { depositTxTypeLabel } from './depositTransactionLabels';

describe('depositTxTypeLabel', () => {
  it('labels every server-side DepositTransaction.TransactionType', () => {
    const serverTypes = ['DEPOSIT', 'WITHDRAWAL', 'BLOCK', 'UNBLOCK', 'CHARGE', 'ADMIN_ADJUSTMENT', 'PAYMENT_CREDIT'];
    for (const t of serverTypes) expect(depositTxTypeLabel(t)).not.toBe(t);
    expect(depositTxTypeLabel('ADMIN_ADJUSTMENT')).toBe('Корректировка администратором');
    expect(depositTxTypeLabel('PAYMENT_CREDIT')).toBe('Зачисление платежа');
  });

  it('shows an unknown type as-is', () => {
    expect(depositTxTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
