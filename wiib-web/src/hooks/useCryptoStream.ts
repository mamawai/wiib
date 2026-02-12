import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useEffect, useRef, useState } from 'react';

export interface CryptoTick {
  price: number;
  ts: number;
}

const THROTTLE_MS = 3000;

export function useCryptoStream(symbol: string | undefined): CryptoTick | null {
  const [tick, setTick] = useState<CryptoTick | null>(null);
  const clientRef = useRef<Client | null>(null);
  const lastUpdateRef = useRef(0);
  const pendingRef = useRef<CryptoTick | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!symbol) return;

    const flush = (t: CryptoTick) => {
      lastUpdateRef.current = Date.now();
      pendingRef.current = null;
      setTick(t);
    };

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/quotes'),
      onConnect: () => {
        client.subscribe(`/topic/crypto/${symbol}`, (msg) => {
          try {
            const data = JSON.parse(msg.body);
            const t: CryptoTick = { price: parseFloat(data.price), ts: data.ts };
            const elapsed = Date.now() - lastUpdateRef.current;
            if (elapsed >= THROTTLE_MS) {
              flush(t);
            } else {
              pendingRef.current = t;
              if (!timerRef.current) {
                timerRef.current = setTimeout(() => {
                  timerRef.current = null;
                  if (pendingRef.current) flush(pendingRef.current);
                }, THROTTLE_MS - elapsed);
              }
            }
          } catch { /* ignore */ }
        });
      },
    });

    clientRef.current = client;
    client.activate();

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = null;
      client.deactivate();
      clientRef.current = null;
    };
  }, [symbol]);

  return tick;
}
