/**
 * Russian labels for DepositTransaction.TransactionType - the exact set the server returns.
 * An unknown value (a type added on the server later) is shown as-is rather than hidden.
 */
export const DEPOSIT_TX_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: 'Пополнение',
  WITHDRAWAL: 'Вывод',
  BLOCK: 'Блокировка',
  UNBLOCK: 'Разблокировка',
  CHARGE: 'Удержание',
  ADMIN_ADJUSTMENT: 'Корректировка администратором',
  PAYMENT_CREDIT: 'Зачисление платежа',
  WITHDRAWAL_RETURN: 'Возврат по отклонённой заявке на вывод',
};

export function depositTxTypeLabel(type: string): string {
  return DEPOSIT_TX_TYPE_LABELS[type] ?? type;
}
