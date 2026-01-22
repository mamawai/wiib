import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useEffect, useRef } from 'react';
import type { AssetChangeEvent, PositionChangeEvent, OrderStatusEvent } from '../types';

interface UserEventsCallbacks {
  onAssetChange?: (event: AssetChangeEvent) => void;
  onPositionChange?: (event: PositionChangeEvent) => void;
  onOrderStatus?: (event: OrderStatusEvent) => void;
}

export function useUserEvents(userId: number | undefined, callbacks: UserEventsCallbacks) {
  const callbacksRef = useRef(callbacks);
  callbacksRef.current = callbacks;

  useEffect(() => {
    if (!userId) return;

    const client = new Client({
      webSocketFactory: () => new SockJS('/ws/quotes'),
      onConnect: () => {
        console.log('用户事件WebSocket已连接');

        client.subscribe(`/topic/user/${userId}/asset`, (msg) => {
          const data: AssetChangeEvent = JSON.parse(msg.body);
          callbacksRef.current.onAssetChange?.(data);
        });

        client.subscribe(`/topic/user/${userId}/position`, (msg) => {
          const data: PositionChangeEvent = JSON.parse(msg.body);
          callbacksRef.current.onPositionChange?.(data);
        });

        client.subscribe(`/topic/user/${userId}/order`, (msg) => {
          const data: OrderStatusEvent = JSON.parse(msg.body);
          callbacksRef.current.onOrderStatus?.(data);
        });
      },
      onDisconnect: () => {
        console.log('用户事件WebSocket已断开');
      },
      onStompError: (frame) => {
        console.error('STOMP错误:', frame);
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [userId]);
}
