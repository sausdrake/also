import { useEffect, useState } from 'react';

interface AuctionTimerProps {
  startTime?: string;
  endTime?: string;
  status: string;
}

export const AuctionTimer = ({ startTime, endTime, status }: AuctionTimerProps) => {
  const [timeLeft, setTimeLeft] = useState<string>('');
  const [isUrgent, setIsUrgent] = useState(false);

  useEffect(() => {
    const interval = setInterval(() => {
      const now = new Date();
      
      if (status === 'CREATED' && startTime) {
        const start = new Date(startTime);
        const diff = start.getTime() - now.getTime();
        
        if (diff <= 0) {
          setTimeLeft('Скоро начнётся');
          setIsUrgent(false);
        } else {
          const hours = Math.floor(diff / (1000 * 60 * 60));
          const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
          const seconds = Math.floor((diff % (1000 * 60)) / 1000);
          setTimeLeft(`Старт через ${hours}ч ${minutes}м ${seconds}с`);
          setIsUrgent(hours === 0 && minutes < 5);
        }
      } 
      else if (status === 'ACTIVE' && endTime) {
        const end = new Date(endTime);
        const diff = end.getTime() - now.getTime();
        
        if (diff <= 0) {
          setTimeLeft('Аукцион завершён');
          setIsUrgent(false);
        } else {
          const hours = Math.floor(diff / (1000 * 60 * 60));
          const minutes = Math.floor((diff % (1000 * 60 * 60)) / (1000 * 60));
          const seconds = Math.floor((diff % (1000 * 60)) / 1000);
          setTimeLeft(`${hours}ч ${minutes}м ${seconds}с`);
          setIsUrgent(hours === 0 && minutes < 5);
        }
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [startTime, endTime, status]);

  if (!startTime && !endTime) return null;

  return (
    <div className={`text-center p-3 rounded-lg ${isUrgent ? 'bg-red-100 text-red-700 animate-pulse' : 'bg-gray-100 text-gray-700'}`}>
      <div className="text-sm font-medium">⏱️ {status === 'CREATED' ? 'До начала' : 'До окончания'}</div>
      <div className={`text-xl font-bold ${isUrgent ? 'text-red-600' : ''}`}>{timeLeft}</div>
    </div>
  );
};