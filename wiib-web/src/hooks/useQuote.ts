import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useEffect, useState } from 'react';
import type { Quote, DayTick } from '../types';

interface UseQuoteResult {
  quote: Quote | null;
  realtimeTicks: DayTick[];
}

const MAX_TICKS = 1440;

export function useQuote(stockCode: string | undefined, initialTicks?: DayTick[]): UseQuoteResult {
  const [quote, setQuote] = useState<Quote | null>(null);
  const [realtimeTicks, setRealtimeTicks] = useState<DayTick[]>([]);

  // 初始化历史数据
  useEffect(() => {
    if (initialTicks && initialTicks.length > 0) {
      setRealtimeTicks([...initialTicks].slice(0, MAX_TICKS));
    } else {
      setRealtimeTicks([]);
    }
  }, [initialTicks]);

  useEffect(() => {
    if (!stockCode) return;

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/quotes'),
      onConnect: () => {
        console.log('行情WebSocket已连接');
        client.subscribe(`/topic/quote/${stockCode}`, (msg) => {
          const data: Quote = JSON.parse(msg.body);
          setQuote(data);

          // 直接追加到末尾（按顺序填充下一个槽位）
          setRealtimeTicks((prev) => {
            if (prev.length >= MAX_TICKS) {
              return prev;
            }
            return [...prev, { time: '', price: data.price }];
          });
        });
      },
      onDisconnect: () => {
        console.log('行情WebSocket已断开');
      },
      onStompError: (frame) => {
        console.error('STOMP错误:', frame);
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [stockCode]);

  return { quote, realtimeTicks };
}
