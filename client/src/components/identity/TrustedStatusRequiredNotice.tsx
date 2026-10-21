/**
 * KYC-01: shown when a bid / Buy Now is refused with 403 TRUSTED_PARTICIPANT_REQUIRED. The backend checks
 * identity verification first (BidController -> VerifiedIdentityGate), so reaching the trust gate means the
 * identity is already verified - the user must not be sent to verify again. The trusted status is granted
 * by an administrator separately; nothing here suggests that verification grants it.
 */
export function TrustedStatusRequiredNotice({ action }: {
  /** What was refused, e.g. "делать ставки". */
  action: string;
}) {
  return (
    <div role="alert" className="rounded-xl border border-blue-200 bg-blue-50 p-3 text-sm text-blue-900 space-y-1">
      <p className="font-medium">Подтверждение личности пройдено.</p>
      <p>Чтобы {action}, нужен статус доверенного пользователя. Его выдаёт администратор — обратитесь к администратору.</p>
    </div>
  );
}
