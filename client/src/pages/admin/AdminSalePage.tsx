import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../../api/client';
import { SaleStatusCard } from '../../components/admin/sale/SaleStatusCard';
import { PaymentEvidenceForm } from '../../components/admin/sale/PaymentEvidenceForm';
import { HandoverConfirmPanel } from '../../components/admin/sale/HandoverConfirmPanel';
import { SaleCompletionPanel } from '../../components/admin/sale/SaleCompletionPanel';
import { SaleFailurePanel } from '../../components/admin/sale/SaleFailurePanel';
import { PaymentDeadlineExtendForm } from '../../components/admin/sale/PaymentDeadlineExtendForm';

export interface PaymentEvidence {
  id: number;
  auctionId: number;
  buyerId: number;
  amount: number;
  receivedByAdminId: number;
  receivedAt: string;
}

export interface HandoverEvidence {
  id: number;
  auctionId: number;
  vehicleId: number;
  buyerId: number;
  confirmedByAdminId: number;
  confirmedAt: string;
}

export interface SaleResolution {
  id: number;
  auctionId: number;
  result: string;
  faultParty: string | null;
  reason: string | null;
  resolvedBy: number;
  resolvedAt: string;
  paymentEvidenceId: number | null;
  handoverEvidenceId: number | null;
  financialResolutionStatus: string | null;
}

export interface SaleStatus {
  auctionId: number;
  auctionStatus: string;
  paymentDeadline: string | null;
  overdue: boolean;
  paymentEvidence: PaymentEvidence | null;
  handoverEvidence: HandoverEvidence | null;
  saleResolution: SaleResolution | null;
}

/**
 * Sale-UI: dedicated admin sale-management page (own file/route, NOT added inline to
 * AdminDashboard.tsx, which is already ~2600+ lines). This page only orchestrates - it owns the
 * single authoritative GET /admin/sale/{auctionId} fetch/refresh cycle and passes the result down
 * to narrow, single-purpose sub-components; it contains no financial logic itself. Every mutation
 * sub-component re-fetches this page's status on success (never an optimistic local update) - see
 * each sub-component's own onSuccess handling.
 */
export function AdminSalePage() {
  const { auctionId } = useParams<{ auctionId: string }>();
  const navigate = useNavigate();
  const id = Number(auctionId);

  const [status, setStatus] = useState<SaleStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [msg, setMsg] = useState<{ text: string; ok: boolean } | null>(null);

  const notify = (text: string, ok = true) => {
    setMsg({ text, ok });
    setTimeout(() => setMsg(null), 4000);
  };

  const refresh = useCallback(() => {
    setLoading(true);
    setNotFound(false);
    api.get<SaleStatus>(`/admin/sale/${id}`)
      .then(r => setStatus(r.data))
      .catch(e => { if (e.response?.status === 404) setNotFound(true); })
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => {
    if (Number.isFinite(id)) refresh();
  }, [id, refresh]);

  if (!Number.isFinite(id)) {
    return <div className="p-8 text-center text-gray-400">Некорректный ID аукциона</div>;
  }

  return (
    <div className="max-w-3xl mx-auto py-8 px-4 space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-gray-900">Продажа — аукцион #{id}</h2>
          <p className="text-sm text-gray-500 mt-0.5">
            Подтверждение оплаты, передачи и финализация сделки
          </p>
        </div>
        <button onClick={() => navigate('/admin/auctions')}
          className="text-sm px-3 py-1.5 rounded-lg border text-gray-600 hover:bg-gray-50">
          ← К списку аукционов
        </button>
      </div>

      {msg && (
        <div className={`text-sm rounded-xl px-4 py-2 ${msg.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'}`}>
          {msg.text}
        </div>
      )}

      {loading ? (
        <div className="p-8 text-center text-gray-400">Загрузка...</div>
      ) : notFound ? (
        <div className="p-8 text-center text-gray-400 bg-white border rounded-2xl">
          Аукцион не найден
        </div>
      ) : status ? (
        <div className="space-y-4">
          <SaleStatusCard status={status} />

          {!status.saleResolution && (
            <>
              <PaymentEvidenceForm auctionId={id} status={status} onDone={refresh} notify={notify} />
              <HandoverConfirmPanel auctionId={id} status={status} onDone={refresh} notify={notify} />
              <SaleCompletionPanel auctionId={id} status={status} onDone={refresh} notify={notify} />
              <SaleFailurePanel auctionId={id} status={status} onDone={refresh} notify={notify} />
            </>
          )}

          <PaymentDeadlineExtendForm auctionId={id} status={status} onDone={refresh} notify={notify} />
        </div>
      ) : null}
    </div>
  );
}
