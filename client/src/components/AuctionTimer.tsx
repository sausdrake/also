import { useEffect, useState } from 'react';
import { parseDate } from '../utils/formatDate';

interface AuctionTimerProps {
  startTime?: string;
  endTime?: string;
  status: string;
  extended?: boolean;
}

const ENDED = new Set(['COMPLETED', 'SOLD', 'EXPIRED', 'CANCELLED']);

const fmt = (ms: number) => {
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  return `${h}ч ${m}м ${s}с`;
};

export const AuctionTimer = ({ startTime, endTime, status, extended }: AuctionTimerProps) => {
  const [timeLeft, setTimeLeft] = useState('');
  const [isUrgent, setIsUrgent] = useState(false);

  useEffect(() => {
    if (ENDED.has(status)) return;

    const tick = () => {
      const now = Date.now();
      if (status === 'CREATED' && startTime) {
        const diff = (parseDate(startTime)?.getTime() ?? 0) - now;
        if (diff <= 0) {
          setTimeLeft('Начинается...');
          setIsUrgent(false);
        } else {
          setTimeLeft(fmt(diff));
          setIsUrgent(diff < 5 * 60_000);
        }
      } else if (status === 'ACTIVE' && endTime) {
        const diff = (parseDate(endTime)?.getTime() ?? 0) - now;
        if (diff <= 0) {
          setTimeLeft('Завершается...');
          setIsUrgent(false);
        } else {
          setTimeLeft(fmt(diff));
          setIsUrgent(diff < 5 * 60_000);
        }
      }
    };

    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [startTime, endTime, status]);

  if (ENDED.has(status)) {
    return (
      <div className="text-center p-3 rounded-lg bg-gray-100 text-gray-500">
        <div className="text-sm font-medium">🏁 Аукцион завершён</div>
      </div>
    );
  }

  if (!timeLeft) return null;

  return (
    <div className={`text-center p-3 rounded-lg ${isUrgent ? 'bg-red-100 text-red-700 animate-pulse' : 'bg-gray-100 text-gray-700'}`}>
      <div className="text-sm font-medium flex items-center justify-center gap-1">
        <span>⏱️ {status === 'CREATED' ? 'До начала' : 'До окончания'}</span>
        {extended && <span className="text-xs bg-amber-100 text-amber-700 px-1.5 py-0.5 rounded-full font-semibold">+5 мин</span>}
      </div>
      <div className={`text-xl font-bold ${isUrgent ? 'text-red-600' : ''}`}>{timeLeft}</div>
    </div>
  );
};
