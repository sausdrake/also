import { useParams, useNavigate } from 'react-router-dom';
import { useEffect, useState, useRef } from 'react';
import axios from 'axios';
import { useWebSocket } from '../hooks/useWebSocket';
import { AuctionTimer } from '../components/AuctionTimer';
import { useAuthStore } from '../store/authStore';
import { PhotoLightbox } from '../components/PhotoLightbox';
import { BuyerSaleStatusPanel } from '../components/sale/BuyerSaleStatusPanel';
import * as subscriptions from '../api/subscriptions';
import { apiErrorMessage, parseApiError } from '../api/errors';
import { KycRequiredNotice } from '../components/identity/KycRequiredNotice';
import { TrustedStatusRequiredNotice } from '../components/identity/TrustedStatusRequiredNotice';
import { MONEY_CAPACITY, moneyInputError, toMoney } from '../domain/money';
import { isAnswerFilled, savedExtraPhotos, savedExtraRating } from '../domain/inspectionItem';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

// ─── Types ───────────────────────────────────────────────────────────────────
interface AuctionDetail {
  id: number;
  vehicleId: number;
  vehicleInfo: string;
  currentPrice: number;
  startingPrice: number;
  reservePrice: number | null;
  buyNowPrice: number | null;
  minBidStep: number;
  /** AUCTION-01: the smallest valid next bid (to the tyiyn), derived by the server - never recomputed here. */
  minimumNextBid?: number | null;
  /** Fixed deposit the admin set for this lot; without it the backend refuses bids and Buy Now. */
  requiredDepositAmount?: number | null;
  status: string;
  startTime: string;
  endTime: string;
  totalBids: number;
  mainPhotoUrl?: string;
  additionalPhotoUrls?: string;
  videoUrl?: string;
  winnerId?: number | null;
  paymentDeadline?: string | null;
  overdue?: boolean;
}

interface VehicleDetail {
  id: number;
  brand: string;
  model: string;
  year: number;
  vin: string;
  color?: string;
  mileage?: number;
  engineCapacity?: number;
  fuelType?: string;
  transmission?: string;
  description?: string;
}

interface DiagItem   { id: number; title: string; fieldType: string; description?: string; hasRating?: boolean; hasPhoto?: boolean; }
interface DiagGroup  { id: number; name: string; items: DiagItem[]; }
interface DiagAnswer { inspectionItemId: number; rating: number | null; comment: string; photoUrls: string[]; skipped?: boolean; }
interface DiagReport {
  vin: string;
  template: { name: string; groups: DiagGroup[] };
  existingAnswers: DiagAnswer[];
}

interface LiveBid {
  price: number;
  at: Date;
  bidderId?: number;
}

const BID_HISTORY_MAX = 50;

// ─── Helpers ─────────────────────────────────────────────────────────────────
const fmt = (n: number | null | undefined) => (n ?? 0).toLocaleString('ru');

function bidStorageKey(auctionId: string) { return `auction_bids_${auctionId}`; }

function loadStoredBids(auctionId: string): LiveBid[] {
  try {
    const raw = localStorage.getItem(bidStorageKey(auctionId));
    if (!raw) return [];
    return (JSON.parse(raw) as { price: number; at: string; bidderId?: number }[])
      .map(b => ({ price: b.price, at: new Date(b.at), bidderId: b.bidderId }));
  } catch { return []; }
}

function storeBids(auctionId: string, bids: LiveBid[]) {
  try {
    localStorage.setItem(bidStorageKey(auctionId),
      JSON.stringify(bids.slice(0, BID_HISTORY_MAX).map(b => ({ price: b.price, at: b.at.toISOString(), bidderId: b.bidderId }))));
  } catch {}
}
const fuelLabel: Record<string, string> = {
  PETROL: 'Бензин', DIESEL: 'Дизель', ELECTRIC: 'Электро',
  HYBRID: 'Гибрид', GAS: 'Газ',
};
const transLabel: Record<string, string> = {
  MANUAL: 'Механика', AUTOMATIC: 'Автомат', CVT: 'Вариатор', ROBOT: 'Робот',
};

// ─── Diagnostic section view ──────────────────────────────────────────────────
function DiagSection({ group, answers }: { group: DiagGroup; answers: DiagAnswer[] }) {
  const [open, setOpen] = useState(true);
  const [lightbox, setLightbox] = useState<{ urls: string[]; index: number } | null>(null);
  const ansMap = Object.fromEntries(answers.map(a => [a.inspectionItemId, a]));

  // DIAG-02: a rating / photos saved next to the main answer (seeded TEXT items with hasRating / hasPhoto).
  const renderVal = (item: DiagItem, ans?: DiagAnswer) => {
    const rating = savedExtraRating(item, ans);
    const photos = savedExtraPhotos(item, ans);
    return (
      <div className="space-y-1">
        {renderMain(item, ans)}
        {rating != null && <p className="text-xs text-gray-600">Оценка: {rating}/5</p>}
        {photos.length > 0 && (
          <div className="flex flex-wrap gap-2 mt-1">
            {photos.map((url, i) => (
              <button key={i} type="button" onClick={() => setLightbox({ urls: photos, index: i })} className="shrink-0">
                <img src={url} alt="" className="w-24 h-24 object-cover rounded-xl border hover:opacity-80 transition-opacity cursor-pointer" />
              </button>
            ))}
          </div>
        )}
      </div>
    );
  };

  const renderMain = (item: DiagItem, ans?: DiagAnswer) => {
    if (ans?.skipped) return <span className="text-gray-400 text-sm italic">Пропущен диагностом</span>;
    if (!ans) return <span className="text-gray-300 text-sm">Не заполнено</span>;

    switch (item.fieldType) {
      case 'NUMBER': {
        if (ans.rating == null) return <span className="text-gray-300 text-sm">—</span>;
        const labels = ['', 'Плохо', 'Удовл.', 'Нормально', 'Хорошо', 'Отлично'];
        const colors = ['', 'bg-red-500', 'bg-orange-400', 'bg-yellow-400', 'bg-green-400', 'bg-green-600'];
        return (
          <div className="flex items-center gap-2">
            <div className="flex gap-1">
              {[1, 2, 3, 4, 5].map(i => (
                <div key={i} className={`w-7 h-7 rounded-lg text-xs flex items-center justify-center font-bold text-white ${
                  ans.rating! >= i ? colors[ans.rating!] : 'bg-gray-100 text-gray-300'
                }`}>{i}</div>
              ))}
            </div>
            <span className="text-sm text-gray-500">{labels[ans.rating]}</span>
          </div>
        );
      }
      case 'PHOTO':
        return ans.photoUrls?.length
          ? (
            <div className="flex flex-wrap gap-2 mt-1">
              {ans.photoUrls.map((url, i) => (
                <button key={i} type="button"
                  onClick={() => setLightbox({ urls: ans.photoUrls, index: i })}
                  className="shrink-0">
                  <img src={url} alt="" className="w-24 h-24 object-cover rounded-xl border hover:opacity-80 transition-opacity cursor-pointer" />
                </button>
              ))}
            </div>
          )
          : <span className="text-gray-300 text-sm">Нет фото</span>;
      case 'CHECKBOX':
        return (
          <span className={`text-sm font-semibold ${ans.comment === 'true' ? 'text-green-600' : 'text-red-500'}`}>
            {ans.comment === 'true' ? '✓ Да' : '✕ Нет'}
          </span>
        );
      default:
        return <span className="text-sm text-gray-800">{ans.comment || <span className="text-gray-300">—</span>}</span>;
    }
  };

  // DIAG-02: the same rule as the backend completion gate.
  const filled = group.items.filter(i => isAnswerFilled(i, ansMap[i.id])).length;

  return (
    <div className="border border-gray-100 rounded-2xl overflow-hidden">
      <button
        onClick={() => setOpen(v => !v)}
        className="w-full flex items-center justify-between px-5 py-4 bg-gray-50 hover:bg-gray-100 transition-colors text-left"
      >
        <div className="flex items-center gap-3">
          <span className="font-semibold text-gray-900">{group.name}</span>
          <span className="text-xs px-2 py-0.5 rounded-full bg-white border text-gray-500">
            {filled}/{group.items.length}
          </span>
        </div>
        <svg className={`w-4 h-4 text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`}
          fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {open && (
        <div className="divide-y divide-gray-50">
          {group.items.map(item => {
            const ans = ansMap[item.id];
            const isFilled = isAnswerFilled(item, ans);
            return (
              <div key={item.id} className="px-5 py-3">
                <div className="flex items-start gap-2 mb-1">
                  <div className={`mt-1 w-1.5 h-1.5 rounded-full shrink-0 ${isFilled ? 'bg-green-400' : 'bg-gray-200'}`} />
                  <p className="text-xs text-gray-500 flex-1">{item.title}</p>
                </div>
                <div className="pl-3.5">{renderVal(item, ans)}</div>
              </div>
            );
          })}
        </div>
      )}

      {lightbox && (
        <PhotoLightbox
          urls={lightbox.urls}
          index={lightbox.index}
          onClose={() => setLightbox(null)}
          onPrev={() => setLightbox(lb => lb && { ...lb, index: (lb.index - 1 + lb.urls.length) % lb.urls.length })}
          onNext={() => setLightbox(lb => lb && { ...lb, index: (lb.index + 1) % lb.urls.length })}
        />
      )}
    </div>
  );
}

// ─── Main page ──────────────────────────────────────────────────────────────
type Tab = 'overview' | 'report' | 'bids' | 'docs';

export const AuctionPage = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const auctionId = parseInt(id || '0');
  const { isAuthenticated, accessToken, user } = useAuthStore();
  const { isConnected, lastMessage } = useWebSocket(auctionId);
  const liveBid = lastMessage?.messageType === 'BID_PLACED' ? lastMessage : null;

  const [auction, setAuction]   = useState<AuctionDetail | null>(null);
  const [vehicle, setVehicle]   = useState<VehicleDetail | null>(null);
  const [report, setReport]     = useState<DiagReport | null>(null);
  const [loading, setLoading]   = useState(true);

  const [activeTab, setActiveTab] = useState<Tab>('overview');
  const [selectedPhoto, setSelectedPhoto] = useState(0);
  const [galleryLightbox, setGalleryLightbox] = useState<number | null>(null);

  const [bidAmount, setBidAmount] = useState('');
  const [bidError, setBidError]   = useState('');
  // FE-KYC-01: a bid / Buy Now refused with 403 IDENTITY_VERIFICATION_REQUIRED keeps a notice with
  // a link to the identity tab (a 5-second flash used to be the only trace).
  const [kycRefusal, setKycRefusal] = useState<{ action: string; message: string } | null>(null);
  // KYC-01: the trust gate's refusal (identity already verified) - its own lasting notice
  const [trustRefusal, setTrustRefusal] = useState<{ action: string } | null>(null);
  const [message, setMessage]     = useState<{ text: string; type: 'success' | 'error' | 'warning' } | null>(null);

  const [liveBids, setLiveBids] = useState<LiveBid[]>(() => id ? loadStoredBids(id) : []);
  const bidsEndRef = useRef<HTMLDivElement>(null);

  // FE-SUB-01: 'unknown' until the backend confirms the status - a failed or malformed status
  // never shows "Вы подписаны"; the state changes only after a confirmed POST/DELETE.
  const [subState, setSubState] = useState<'unknown' | 'subscribed' | 'not_subscribed'>('unknown');
  const [subStatusError, setSubStatusError] = useState(false);
  const [subLoading, setSubLoading] = useState(false);
  const subInFlight = useRef(false);
  // FE-INFLIGHT-01: one bid / Buy Now POST at a time. The backend already refuses a duplicate,
  // but a double tap used to replace the success message with that refusal.
  const moneyActionInFlight = useRef(false);
  const [pendingAction, setPendingAction] = useState<'bid' | 'buyNow' | null>(null);
  const isSubscribed = subState === 'subscribed';
  const [wasExtended, setWasExtended] = useState(false);

  // WebSocket updates
  useEffect(() => {
    if (!lastMessage || !auction) return;
    setAuction(prev => prev ? {
      ...prev,
      ...(lastMessage.currentPrice != null && { currentPrice: lastMessage.currentPrice }),
      ...(lastMessage.totalBids   != null && { totalBids:    lastMessage.totalBids }),
      ...(lastMessage.status      != null && { status:       lastMessage.status }),
      ...(lastMessage.endTime     != null && { endTime:      lastMessage.endTime }),
    } : null);
    if (lastMessage.messageType === 'BID_PLACED' && lastMessage.currentPrice != null) {
      setLiveBids(prev => {
        const updated = [{ price: lastMessage.currentPrice, at: new Date(), bidderId: lastMessage.winnerId }, ...prev].slice(0, BID_HISTORY_MAX);
        if (id) storeBids(id, updated);
        return updated;
      });
      showMsg(`Новая ставка: ${fmt(lastMessage.currentPrice)} сом`, 'success');
    }
    if (lastMessage.messageType === 'AUCTION_EXTENDED') {
      setWasExtended(true);
      showMsg('⏰ Аукцион продлён на 5 минут — сделана ставка в последние минуты!', 'warning');
    }
  }, [lastMessage]);

  // AUCTION-01: after a live bid the new minimum next bid comes from the server, never from price + step
  useEffect(() => { fetchAuction(); }, [id, liveBid]);

  useEffect(() => {
    if (!auction?.vehicleId) return;
    axios.get(`${BASE_URL}/api/v1/public/vehicles/${auction.vehicleId}`)
      .then(r => setVehicle(r.data))
      .catch(() => {});
    axios.get(`${BASE_URL}/api/v1/public/reports/by-vehicle/${auction.vehicleId}`)
      .then(r => setReport(r.data))
      .catch(() => {});
  }, [auction?.vehicleId]);

  useEffect(() => {
    if (!isAuthenticated || !id) return;
    let cancelled = false;
    setSubState('unknown');
    setSubStatusError(false);
    subscriptions.status(Number(id))
      .then(subscribed => { if (!cancelled) setSubState(subscribed ? 'subscribed' : 'not_subscribed'); })
      .catch(() => { if (!cancelled) setSubStatusError(true); });
    return () => { cancelled = true; };
  }, [id, isAuthenticated]);

  const authHeaders = () => {
    const token = accessToken || localStorage.getItem('accessToken');
    return token ? { Authorization: `Bearer ${token}` } : {};
  };

  const showMsg = (text: string, type: 'success' | 'error' | 'warning') => {
    setMessage({ text, type });
    setTimeout(() => setMessage(null), 5000);
  };

  const handleToggleSub = async () => {
    if (!isAuthenticated || !id || subInFlight.current) return;
    subInFlight.current = true;
    setSubLoading(true);
    try {
      if (isSubscribed) {
        await subscriptions.unsubscribe(Number(id));
        setSubState('not_subscribed');
      } else {
        await subscriptions.subscribe(Number(id));
        setSubState('subscribed');
      }
      setSubStatusError(false);
    } catch (e) {
      showMsg(apiErrorMessage(e, isSubscribed ? 'Не удалось отписаться от лота' : 'Не удалось подписаться на лот'), 'error');
    } finally {
      subInFlight.current = false;
      setSubLoading(false);
    }
  };

  const fetchAuction = async () => {
    try {
      const { data } = await axios.get(`${BASE_URL}/api/public/auctions/${id}`, { headers: authHeaders() });
      setAuction(data);
      setBidAmount(data.minimumNextBid != null ? String(data.minimumNextBid) : '');
      // Seed bid history only when localStorage has no history for this auction
      if (data.totalBids > 0 && id) {
        const stored = loadStoredBids(id);
        if (stored.length === 0) {
          const seed: LiveBid[] = [{ price: data.currentPrice, at: new Date() }];
          setLiveBids(seed);
          storeBids(id, seed);
        }
      }
    } catch {
      // handled via null check
    } finally {
      setLoading(false);
    }
  };

  const validateBid = (raw: string) => {
    if (!raw.trim()) return 'Введите сумму ставки';
    const formatError = moneyInputError(raw);
    if (formatError) return formatError;
    const val = toMoney(raw)!;
    if (auction?.minimumNextBid != null && val < auction.minimumNextBid)
      return `Минимальная ставка: ${fmt(auction.minimumNextBid)} сом`;
    return null;
  };

  const placeBid = async () => {
    if (!isAuthenticated) { navigate('/login'); return; }
    if (moneyActionInFlight.current) return;
    const err = validateBid(bidAmount);
    if (err) { setBidError(err); return; }
    setBidError('');
    moneyActionInFlight.current = true;
    setPendingAction('bid');
    try {
      await axios.post(`${BASE_URL}/api/auctions/${id}/bids`, { amount: toMoney(bidAmount) }, { headers: authHeaders() });
      setKycRefusal(null);
      setTrustRefusal(null);
      showMsg(`Ставка ${fmt(toMoney(bidAmount))} сом принята!`, 'success');
    } catch (e) {
      const error = parseApiError(e, 'Ошибка при размещении ставки');
      setKycRefusal(error.kind === 'kyc_required' ? { action: 'делать ставки', message: error.message } : null);
      setTrustRefusal(error.kind === 'trusted_required' ? { action: 'делать ставки' } : null);
      if (error.kind !== 'kyc_required' && error.kind !== 'trusted_required') {
        showMsg(error.message, error.kind === 'rate_limited' ? 'warning' : 'error');
      }
    } finally {
      moneyActionInFlight.current = false;
      setPendingAction(null);
    }
  };

  const buyNow = async () => {
    if (!isAuthenticated) { navigate('/login'); return; }
    if (!auction?.buyNowPrice) return;
    if (moneyActionInFlight.current) return;
    moneyActionInFlight.current = true;
    setPendingAction('buyNow');
    try {
      await axios.post(`${BASE_URL}/api/auctions/${id}/buy-now`, {}, { headers: authHeaders() });
      setKycRefusal(null);
      setTrustRefusal(null);
      showMsg(`Лот закреплён за вами по цене ${fmt(auction.buyNowPrice)} сом. Ожидается оплата: администратор свяжется с вами.`, 'success');
      fetchAuction();
    } catch (e) {
      const error = parseApiError(e, 'Ошибка при покупке');
      // BUYNOW-01: a refusal usually means the screen is stale (already bought, already started) -
      // reload the lot so the Buy Now button disappears instead of inviting another attempt.
      fetchAuction();
      setKycRefusal(error.kind === 'kyc_required' ? { action: 'купить по цене Buy Now', message: error.message } : null);
      setTrustRefusal(error.kind === 'trusted_required' ? { action: 'купить по цене Buy Now' } : null);
      if (error.kind !== 'kyc_required' && error.kind !== 'trusted_required') showMsg(error.message, 'error');
    } finally {
      moneyActionInFlight.current = false;
      setPendingAction(null);
    }
  };

  // ─── Loading / not found ─────────────────────────────────────────────────
  if (loading) return (
    <div className="flex items-center justify-center py-32">
      <div className="w-8 h-8 border-4 border-blue-200 border-t-blue-600 rounded-full animate-spin" />
    </div>
  );

  if (!auction) return (
    <div className="text-center py-24">
      <p className="text-gray-500 text-lg">Аукцион не найден</p>
      <button onClick={() => navigate('/')} className="mt-4 text-blue-600 hover:underline text-sm">← На главную</button>
    </div>
  );

  // ─── Computed ─────────────────────────────────────────────────────────────
  const photos: string[] = (() => {
    const extras: string[] = (() => {
      try { return auction.additionalPhotoUrls ? JSON.parse(auction.additionalPhotoUrls) : []; }
      catch { return []; }
    })();
    return [auction.mainPhotoUrl, ...extras].filter(Boolean) as string[];
  })();

  const minNextBid = auction.minimumNextBid ?? null;
  const isActive    = auction.status === 'ACTIVE';
  const isCreated   = auction.status === 'CREATED';
  const isCompleted = ['COMPLETED', 'SOLD', 'EXPIRED', 'CANCELLED', 'WAITING_FOR_PAYMENT', 'SALE_FAILED'].includes(auction.status);
  const isWinner    = isAuthenticated && user?.id != null && auction.winnerId === user.id
    && ['WAITING_FOR_PAYMENT', 'SOLD', 'SALE_FAILED'].includes(auction.status);

  const reserveProgress = auction.reservePrice
    ? Math.min((auction.currentPrice / auction.reservePrice) * 100, 100) : 0;

  const TABS: { key: Tab; label: string }[] = [
    { key: 'overview', label: 'Обзор' },
    { key: 'report',   label: report ? 'Диагностический отчёт' : 'Диагностика' },
    { key: 'bids',     label: `История ставок (${auction.totalBids})` },
    { key: 'docs',     label: 'Документы' },
  ];

  // ─── Render ───────────────────────────────────────────────────────────────
  return (
    <div className="max-w-5xl mx-auto px-4 py-8 space-y-6">
      {/* Breadcrumb */}
      <button onClick={() => navigate('/')} className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700">
        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
        </svg>
        Все аукционы
      </button>

      <div className="grid grid-cols-1 lg:grid-cols-5 gap-6">
        {/* ── Left column: gallery + tabs ─────────────────────────── */}
        <div className="lg:col-span-3 space-y-4">
          {/* Gallery */}
          {photos.length > 0 ? (
            <div className="space-y-2">
              <button type="button" className="w-full" onClick={() => setGalleryLightbox(selectedPhoto)}>
                <div className="relative aspect-[4/3] rounded-2xl overflow-hidden bg-gray-100">
                  <img
                    src={photos[selectedPhoto]}
                    alt=""
                    className="w-full h-full object-cover hover:opacity-95 transition-opacity cursor-zoom-in"
                  />
                  {/* the live-connection indicator belongs to a running lot only; a finished one has nothing live */}
                  {!isCompleted && (
                    <div className="absolute top-3 right-3">
                      <span className={`text-xs px-2 py-1 rounded-full font-medium ${
                        isConnected ? 'bg-green-500/90 text-white' : 'bg-gray-500/70 text-white'
                      }`}>
                        {isConnected ? '● Live' : '○ Офлайн'}
                      </span>
                    </div>
                  )}
                </div>
              </button>
              {photos.length > 1 && (
                <div className="flex gap-2 overflow-x-auto pb-1">
                  {photos.map((url, i) => (
                    <button key={i} onClick={() => setSelectedPhoto(i)}
                      className={`shrink-0 w-16 h-16 rounded-xl overflow-hidden border-2 transition-all ${
                        i === selectedPhoto ? 'border-blue-500' : 'border-transparent opacity-70 hover:opacity-100'
                      }`}>
                      <img src={url} alt="" className="w-full h-full object-cover" />
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <div className="aspect-[4/3] rounded-2xl bg-gray-100 flex items-center justify-center text-gray-300">
              <svg className="w-16 h-16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1}
                  d="M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
              </svg>
            </div>
          )}

          {/* Tabs */}
          <div className="border-b border-gray-200">
            <nav className="flex gap-0 overflow-x-auto">
              {TABS.map(tab => (
                <button key={tab.key} onClick={() => setActiveTab(tab.key)}
                  className={`px-4 py-3 text-sm font-medium whitespace-nowrap border-b-2 transition-colors ${
                    activeTab === tab.key
                      ? 'border-blue-600 text-blue-700'
                      : 'border-transparent text-gray-500 hover:text-gray-700'
                  }`}>
                  {tab.label}
                </button>
              ))}
            </nav>
          </div>

          {/* Tab content */}
          <div>
            {/* ── Overview ── */}
            {activeTab === 'overview' && (
              <div className="space-y-5">
                <div>
                  <h1 className="text-2xl font-bold text-gray-900">
                    {vehicle ? `${vehicle.brand} ${vehicle.model} ${vehicle.year}` : auction.vehicleInfo}
                  </h1>
                  {vehicle?.vin && (
                    <p className="text-sm text-gray-400 mt-0.5 font-mono">VIN: {vehicle.vin}</p>
                  )}
                </div>

                <div className="grid grid-cols-2 gap-3">
                  {[
                    vehicle?.year         && { label: 'Год выпуска',  value: String(vehicle.year) },
                    vehicle?.mileage      && { label: 'Пробег',       value: `${fmt(vehicle.mileage)} км` },
                    vehicle?.engineCapacity && { label: 'Двигатель',  value: `${vehicle.engineCapacity} л` },
                    vehicle?.fuelType     && { label: 'Топливо',      value: fuelLabel[vehicle.fuelType] || vehicle.fuelType },
                    vehicle?.transmission && { label: 'КПП',          value: transLabel[vehicle.transmission] || vehicle.transmission },
                    vehicle?.color        && { label: 'Цвет',         value: vehicle.color },
                  ].filter((spec): spec is { label: string; value: string } => Boolean(spec)).map(({ label, value }) => (
                    <div key={label} className="bg-gray-50 rounded-xl p-3">
                      <p className="text-xs text-gray-400 mb-0.5">{label}</p>
                      <p className="font-semibold text-gray-900 text-sm">{value}</p>
                    </div>
                  ))}
                </div>

                {vehicle?.description && (
                  <div className="bg-gray-50 rounded-xl p-4">
                    <p className="text-xs text-gray-400 mb-1">Описание</p>
                    <p className="text-sm text-gray-700 whitespace-pre-wrap">{vehicle.description}</p>
                  </div>
                )}

                {/* Auction params */}
                <div className="grid grid-cols-3 gap-3 text-sm">
                  {[
                    ['Стартовая цена',  `${fmt(auction.startingPrice)} сом`],
                    ['Минимальный шаг', `${fmt(auction.minBidStep)} сом`],
                    ['Депозит для участия', auction.requiredDepositAmount != null && auction.requiredDepositAmount > 0
                      ? `${fmt(auction.requiredDepositAmount)} сом` : 'Не настроен'],
                    ['Лот',            `#${auction.id}`],
                  ].map(([k, v]) => (
                    <div key={k} className="bg-gray-50 rounded-xl p-3">
                      <p className="text-xs text-gray-400 mb-0.5">{k}</p>
                      <p className="font-semibold text-gray-900">{v}</p>
                    </div>
                  ))}
                </div>

                {auction.reservePrice && (
                  <div className="p-4 bg-gray-50 rounded-xl">
                    <div className="flex justify-between text-xs text-gray-500 mb-2">
                      <span>Прогресс к резервной цене</span>
                      <span>{Math.round(reserveProgress)}%</span>
                    </div>
                    <div className="h-2 bg-gray-200 rounded-full overflow-hidden">
                      <div className="h-full bg-blue-500 rounded-full transition-all duration-500"
                        style={{ width: `${reserveProgress}%` }} />
                    </div>
                    <p className="text-xs text-gray-400 mt-2">
                      {reserveProgress >= 100
                        ? '✓ Резервная цена достигнута — продажа гарантирована'
                        : 'При достижении отметки продажа гарантирована'}
                    </p>
                  </div>
                )}
              </div>
            )}

            {/* ── Diagnostic report ── */}
            {activeTab === 'report' && (
              <div className="space-y-4">
                {report ? (
                  <>
                    {/* Header badge */}
                    <div className="flex items-center gap-3 p-4 bg-green-50 border border-green-100 rounded-2xl">
                      <div className="w-10 h-10 bg-green-600 rounded-xl flex items-center justify-center shrink-0">
                        <svg className="w-5 h-5 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                            d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                      </div>
                      <div>
                        <p className="font-semibold text-green-800 text-sm">Независимая диагностика пройдена</p>
                        <p className="text-xs text-green-600 mt-0.5">
                          Шаблон: {report.template?.name} · VIN: {report.vin}
                        </p>
                      </div>
                    </div>

                    {/* Scenario 18 step 2: overall score and key defects (items rated 3 or lower) */}
                    {(() => {
                      const rated = (report.template?.groups ?? []).flatMap(g => g.items.map(item => ({
                        group: g.name,
                        item,
                        answer: report.existingAnswers.find(a => a.inspectionItemId === item.id),
                      }))).filter(r => r.answer && !r.answer.skipped && r.answer.rating != null && r.answer.rating > 0);
                      if (rated.length === 0) return null;
                      const score = rated.reduce((sum, r) => sum + (r.answer!.rating as number), 0) / rated.length;
                      const defects = rated.filter(r => (r.answer!.rating as number) <= 3)
                        .sort((a, b) => (a.answer!.rating as number) - (b.answer!.rating as number));
                      return (
                        <div className="space-y-3" data-testid="report-summary">
                          <div className="flex items-center gap-3 px-4 py-3 bg-white border border-gray-100 rounded-xl">
                            <span className="text-2xl font-bold text-gray-900">{score.toFixed(1)}</span>
                            <div>
                              <p className="text-sm font-semibold text-gray-800">Общая оценка</p>
                              <p className="text-xs text-gray-400">из 5 · по {rated.length} оценённым пунктам</p>
                            </div>
                          </div>
                          {defects.length === 0 ? (
                            <p className="text-sm text-green-700 bg-green-50 rounded-xl px-4 py-2">Ключевых недостатков не выявлено</p>
                          ) : (
                            <div className="bg-amber-50 border border-amber-100 rounded-xl px-4 py-3">
                              <p className="text-sm font-semibold text-amber-800 mb-2">Ключевые недостатки ({defects.length})</p>
                              <ul className="space-y-1.5">
                                {defects.map(d => (
                                  <li key={d.item.id} className="text-sm text-amber-900">
                                    <span className="font-semibold">{d.answer!.rating}/5</span>
                                    {' · '}{d.group}: {d.item.title}
                                    {d.answer!.comment && <span className="text-amber-700"> — {d.answer!.comment}</span>}
                                  </li>
                                ))}
                              </ul>
                            </div>
                          )}
                        </div>
                      );
                    })()}

                    {/* Stats */}
                    {(() => {
                      const allItems = (report.template?.groups ?? []).flatMap(g => g.items);
                      const filled = allItems.filter(i => {
                        const a = report.existingAnswers.find(a => a.inspectionItemId === i.id);
                        if (!a) return false;
                        if (i.fieldType === 'NUMBER') return a.rating != null;
                        if (i.fieldType === 'PHOTO') return (a.photoUrls?.length ?? 0) > 0;
                        if (i.fieldType === 'CHECKBOX') return a.comment === 'true' || a.comment === 'false';
                        return (a.comment?.trim().length ?? 0) > 0;
                      }).length;
                      const pct = allItems.length ? Math.round((filled / allItems.length) * 100) : 0;
                      return (
                        <div className="flex items-center gap-3 px-4 py-3 bg-gray-50 rounded-xl text-sm">
                          <div className="flex-1">
                            <div className="flex justify-between text-xs text-gray-400 mb-1">
                              <span>Заполнено пунктов</span>
                              <span>{filled} / {allItems.length}</span>
                            </div>
                            <div className="h-1.5 bg-gray-200 rounded-full overflow-hidden">
                              <div className="h-full bg-blue-500 rounded-full" style={{ width: `${pct}%` }} />
                            </div>
                          </div>
                          <span className="text-xs font-medium text-gray-600">{pct}%</span>
                        </div>
                      );
                    })()}

                    {/* Sections */}
                    {(report.template?.groups ?? []).map(group => (
                      <DiagSection key={group.id} group={group} answers={report.existingAnswers} />
                    ))}
                  </>
                ) : (
                  <div className="text-center py-16 text-gray-400">
                    <svg className="w-12 h-12 mx-auto mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                        d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                    <p className="font-medium">Диагностический отчёт не найден</p>
                    <p className="text-sm mt-1">Отчёт ещё не прикреплён к этому лоту</p>
                  </div>
                )}
              </div>
            )}

            {/* ── Bids history ── */}
            {activeTab === 'bids' && (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <p className="text-sm text-gray-500">Всего ставок: <strong>{auction.totalBids}</strong></p>
                  {isConnected && (
                    <span className="text-xs text-green-600 flex items-center gap-1">
                      <span className="w-1.5 h-1.5 bg-green-500 rounded-full inline-block" />
                      Обновляется в реальном времени
                    </span>
                  )}
                </div>

                {liveBids.length > 0 ? (
                  <div className="space-y-2">
                    {liveBids.map((bid, i) => {
                      const isOwnBid = bid.bidderId != null && user?.id != null && bid.bidderId === user.id;
                      return (
                        <div key={i} className={`flex items-center justify-between px-4 py-3 rounded-xl ${
                          i === 0 ? 'bg-blue-50 border border-blue-100' : 'bg-gray-50'
                        }`}>
                          <div className="flex items-center gap-2">
                            {i === 0 && <span className="text-xs bg-blue-600 text-white px-2 py-0.5 rounded-full">Последняя</span>}
                            {isOwnBid && <span className="text-xs bg-green-100 text-green-700 px-2 py-0.5 rounded-full">Ваша</span>}
                            <span className="text-sm text-gray-500">
                              {bid.at.toLocaleTimeString('ru', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                            </span>
                          </div>
                          <span className={`font-bold ${i === 0 ? 'text-blue-700' : 'text-gray-700'}`}>
                            {fmt(bid.price)} сом
                          </span>
                        </div>
                      );
                    })}
                    <div ref={bidsEndRef} />
                  </div>
                ) : (
                  <div className="text-center py-12 text-gray-400">
                    <svg className="w-10 h-10 mx-auto mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                        d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
                    </svg>
                    <p className="text-sm">Ставки появятся здесь в реальном времени</p>
                    <p className="text-xs mt-1">Всего сделано ставок: {auction.totalBids}</p>
                  </div>
                )}
              </div>
            )}

            {/* ── Docs ── */}
            {activeTab === 'docs' && (
              <div className="text-center py-16 text-gray-400">
                <svg className="w-12 h-12 mx-auto mb-3 opacity-30" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                    d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
                </svg>
                <p className="font-medium">Документы не прикреплены</p>
                <p className="text-sm mt-1">Документы по автомобилю проверяются при передаче</p>
              </div>
            )}
          </div>
        </div>

        {/* ── Right column: price + bid form ────────────────────────── */}
        <div className="lg:col-span-2 space-y-4">
          <div className="sticky top-6 space-y-4">
            {/* Price card */}
            <div className="bg-white border border-gray-200 rounded-2xl p-5 shadow-sm space-y-4">
              <div>
                <h1 className="font-bold text-gray-900 leading-snug">
                  {vehicle ? `${vehicle.brand} ${vehicle.model} ${vehicle.year}` : auction.vehicleInfo}
                </h1>
                {vehicle?.vin && <p className="text-xs text-gray-400 mt-0.5 font-mono">VIN: {vehicle.vin}</p>}
              </div>

              <div className="text-center py-2">
                <p className="text-xs text-gray-400 mb-1">Текущая ставка</p>
                <p className="text-4xl font-extrabold text-blue-600 tabular-nums">
                  {fmt(auction.currentPrice)}
                </p>
                <p className="text-sm text-gray-400 mt-0.5">сом · {auction.totalBids} ставок</p>
              </div>

              <AuctionTimer startTime={auction.startTime} endTime={auction.endTime} status={auction.status} extended={wasExtended} />

              {kycRefusal && <KycRequiredNotice action={kycRefusal.action} message={kycRefusal.message} tradingNote />}
              {trustRefusal && <TrustedStatusRequiredNotice action={trustRefusal.action} />}

              {/* Flash message */}
              {message && (
                <div className={`p-3 rounded-xl text-sm font-medium ${
                  message.type === 'success'
                    ? 'bg-green-50 text-green-700 border border-green-200'
                    : message.type === 'warning'
                    ? 'bg-yellow-50 text-yellow-700 border border-yellow-200'
                    : 'bg-red-50 text-red-700 border border-red-200'
                }`}>
                  {message.text}
                </div>
              )}

              {/* Buy Now */}
              {auction.buyNowPrice && isCreated && (
                <div>
                  <button onClick={buyNow} disabled={pendingAction !== null}
                    className="w-full py-3 bg-green-600 text-white rounded-xl hover:bg-green-700 font-semibold disabled:opacity-50 disabled:cursor-not-allowed">
                    {pendingAction === 'buyNow' ? 'Отправка…' : `Купить сейчас за ${fmt(auction.buyNowPrice)} сом`}
                  </button>
                  <p className="text-xs text-gray-400 text-center mt-1">До начала аукциона — по фиксированной цене</p>
                </div>
              )}

              {/* Bid form */}
              {isActive && (
                <div className="space-y-2">
                  {!isAuthenticated && (
                    <div className="p-3 bg-yellow-50 border border-yellow-200 rounded-xl text-xs text-yellow-800">
                      <button onClick={() => navigate('/login')} className="text-blue-600 font-semibold hover:underline">Войдите</button>,
                      {' '}чтобы участвовать в торгах
                    </div>
                  )}
                  <label className="text-xs text-gray-400" data-testid="min-next-bid">
                    Мин. ставка: {minNextBid != null ? `${fmt(minNextBid)} сом` : '—'}
                  </label>
                  <div className="flex gap-2">
                    <input
                      type="number"
                      value={bidAmount}
                      min={minNextBid ?? undefined}
                      max={MONEY_CAPACITY}
                      step={0.01}
                      onChange={e => { setBidAmount(e.target.value); setBidError(''); }}
                      className={`flex-1 px-4 py-3 border rounded-xl text-base focus:ring-2 focus:ring-blue-500 focus:outline-none ${
                        bidError ? 'border-red-400' : 'border-gray-200'
                      }`}
                      placeholder={minNextBid != null ? fmt(minNextBid) : ''}
                      disabled={!isAuthenticated}
                    />
                    <button onClick={placeBid} disabled={!isAuthenticated || pendingAction !== null}
                      className="px-5 py-3 bg-blue-600 text-white rounded-xl hover:bg-blue-700 font-bold disabled:opacity-50 disabled:cursor-not-allowed">
                      {pendingAction === 'bid' ? 'Отправка…' : 'Ставка'}
                    </button>
                  </div>
                  {bidError && <p className="text-red-500 text-xs">{bidError}</p>}
                </div>
              )}

              {isCompleted && !isWinner && (
                <div className="text-center py-3 text-gray-500 text-sm border-t border-gray-100">
                  Аукцион завершён
                </div>
              )}

              {isWinner && (
                <BuyerSaleStatusPanel
                  status={auction.status}
                  paymentDeadline={auction.paymentDeadline}
                  overdue={auction.overdue}
                />
              )}

              {isAuthenticated && subStatusError && subState === 'unknown' && (
                <p className="text-xs text-gray-500">Не удалось проверить подписку на этот лот.</p>
              )}
              {isAuthenticated && (
                <button
                  onClick={handleToggleSub}
                  disabled={subLoading}
                  aria-pressed={isSubscribed}
                  className={`w-full py-2 rounded-lg text-sm font-medium border transition-colors ${
                    isSubscribed
                      ? 'bg-amber-50 border-amber-300 text-amber-700 hover:bg-amber-100'
                      : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50'
                  }`}
                >
                  {isSubscribed ? '⭐ Вы подписаны' : '⭐ Следить за лотом'}
                </button>
              )}
            </div>

            {/* Diagnostic summary badge (right panel) */}
            {report && (
              <button onClick={() => setActiveTab('report')}
                className="w-full flex items-center gap-3 p-4 bg-green-50 border border-green-100 rounded-2xl hover:bg-green-100 transition-colors text-left">
                <div className="w-8 h-8 bg-green-600 rounded-lg flex items-center justify-center shrink-0">
                  <svg className="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2.5}
                      d="M5 13l4 4L19 7" />
                  </svg>
                </div>
                <div className="min-w-0">
                  <p className="text-sm font-semibold text-green-800">Диагностика пройдена</p>
                  <p className="text-xs text-green-600 truncate">{report.template?.name}</p>
                </div>
                <svg className="w-4 h-4 text-green-500 ml-auto shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
                </svg>
              </button>
            )}
          </div>
        </div>
      </div>

      {galleryLightbox !== null && (
        <PhotoLightbox
          urls={photos}
          index={galleryLightbox}
          onClose={() => setGalleryLightbox(null)}
          onPrev={() => setGalleryLightbox(i => (i! - 1 + photos.length) % photos.length)}
          onNext={() => setGalleryLightbox(i => (i! + 1) % photos.length)}
        />
      )}
    </div>
  );
};
