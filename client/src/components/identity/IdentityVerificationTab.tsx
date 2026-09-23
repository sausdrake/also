import { useCallback, useEffect, useState } from 'react';
import { getOwnStatus, type IdentityStatusResponse } from '../../api/identityApi';
import { IdentityStatusDisplay } from './IdentityStatusDisplay';
import { IdentityUploadForm } from './IdentityUploadForm';

/**
 * Phase 57: orchestrates the single authoritative GET /identity/me fetch/refresh cycle for the
 * USER self-service tab. GET /identity/me returns 404 (not a 200 "empty" body) when the user has
 * never submitted anything - verified directly against IdentityVerificationService.getOwnStatus's
 * orElseThrow message, which contains "не найдена" and is therefore mapped to 404 by the existing
 * GlobalExceptionHandler substring rule. That 404 is treated purely as "no submission yet," never
 * as an error state. Never infers VERIFIED from a successful upload/submission - after
 * IdentityUploadForm reports success (or an error worth re-checking against), this component
 * always re-fetches the authoritative status rather than assuming the new state.
 */
export function IdentityVerificationTab() {
  const [status, setStatus] = useState<IdentityStatusResponse | null>(null);
  const [notSubmitted, setNotSubmitted] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const refresh = useCallback(() => {
    setLoading(true);
    setLoadError(false);
    getOwnStatus()
      .then(data => { setStatus(data); setNotSubmitted(false); })
      .catch(e => {
        if (e.response?.status === 404) {
          setStatus(null);
          setNotSubmitted(true);
        } else {
          setLoadError(true);
        }
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  if (loading) {
    return <p className="text-sm text-gray-400">Загрузка статуса...</p>;
  }

  if (loadError) {
    return <p className="text-sm text-red-600">Не удалось загрузить статус подтверждения личности.</p>;
  }

  const canResubmit = notSubmitted || status?.status === 'REJECTED' || status?.status === 'REVOKED';

  return (
    <div className="space-y-4">
      {status && <IdentityStatusDisplay status={status} />}
      {canResubmit && <IdentityUploadForm onSubmitted={refresh} />}
    </div>
  );
}
