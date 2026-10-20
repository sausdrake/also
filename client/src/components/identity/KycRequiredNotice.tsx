import { Link } from 'react-router-dom';

/**
 * FE-KYC-01: shown when the backend refuses an operation with 403 IDENTITY_VERIFICATION_REQUIRED
 * (VerifiedIdentityGate: bids, Buy Now, diagnostic requests, withdrawals). Unlike a 5-second flash
 * message it stays until the user acts, and it leads to the identity verification tab.
 */
export function KycRequiredNotice({ action, message }: {
  /** What was refused, e.g. "делать ставки" - completes "Чтобы …, подтвердите личность". */
  action: string;
  /** The backend's own explanation, when it sent one. */
  message?: string;
}) {
  return (
    <div role="alert" className="rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 space-y-2">
      <p className="font-medium">Чтобы {action}, подтвердите личность.</p>
      {message && <p className="text-amber-800">{message}</p>}
      <Link to="/profile/identity"
        className="inline-block rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-amber-700">
        Пройти подтверждение личности
      </Link>
    </div>
  );
}
