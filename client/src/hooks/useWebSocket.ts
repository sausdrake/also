import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

interface WebSocketMessage {
  auctionId: number;
  currentPrice: number;
  totalBids: number;
  status: string;
  messageType: string;
  winnerId?: number;
  winningBid?: number;
  endTime?: string;
  timeLeftSeconds?: number;
}

export const useWebSocket = (auctionId: number) => {
  const [isConnected, setIsConnected] = useState(false);
  const [lastMessage, setLastMessage] = useState<WebSocketMessage | null>(null);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    if (!auctionId) return;

    const socket = new SockJS(`${import.meta.env.VITE_API_URL || 'http://localhost:8081'}/ws-auction`);
    const client = new Client({
      webSocketFactory: () => socket as any,
      debug: () => {},
      reconnectDelay: 5000,
      onConnect: () => {
        console.log('✅ WebSocket connected');
        setIsConnected(true);
        
        client.subscribe(`/topic/auction/${auctionId}`, (message) => {
          const data = JSON.parse(message.body);
          console.log('📨 WebSocket message:', data);
          setLastMessage(data);
        });
      },
      onDisconnect: () => {
        console.log('🔌 WebSocket disconnected');
        setIsConnected(false);
      },
      onStompError: (frame) => {
        console.error('❌ STOMP error:', frame);
      },
    });

    client.activate();
    clientRef.current = client;

    return () => {
      if (clientRef.current) {
        clientRef.current.deactivate();
      }
    };
  }, [auctionId]);

  return { isConnected, lastMessage };
};