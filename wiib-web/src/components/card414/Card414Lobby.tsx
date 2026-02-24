import { useState, useCallback } from 'react';
import { card414Api } from '../../api';
import { useToast } from '../ui/use-toast';
import { Button } from '../ui/button';
import { cn } from '../../lib/utils';
import type { CardRoom } from '../../types';

interface Props {
  player: { uuid: string; nickname: string };
  onNicknameChange: (n: string) => void;
  onEnterRoom: (r: CardRoom) => void;
}

export function Card414Lobby({ player, onNicknameChange, onEnterRoom }: Props) {
  const { toast } = useToast();
  const [nickname, setNickname] = useState(player.nickname);
  const [joinCode, setJoinCode] = useState('');
  const [rooms, setRooms] = useState<CardRoom[]>([]);
  const [loading, setLoading] = useState(false);

  const ensureNickname = (): boolean => {
    const n = nickname.trim();
    if (!n) {
      toast('请输入昵称', 'error');
      return false;
    }
    if (n !== player.nickname) onNicknameChange(n);
    return true;
  };

  const createRoom = useCallback(async () => {
    if (!ensureNickname()) return;
    setLoading(true);
    try {
      const r = await card414Api.createRoom(player.uuid, nickname.trim());
      onEnterRoom(r);
    } catch (e) {
      toast((e as Error).message || '创建失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [player.uuid, nickname, onEnterRoom, toast]);

  const joinRoom = useCallback(async () => {
    if (!ensureNickname()) return;
    const code = joinCode.trim().toUpperCase();
    if (!code) { toast('请输入房间号', 'error'); return; }
    setLoading(true);
    try {
      const r = await card414Api.joinRoom(player.uuid, nickname.trim(), code);
      onEnterRoom(r);
    } catch (e) {
      toast((e as Error).message || '加入失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [player.uuid, nickname, joinCode, onEnterRoom, toast]);

  const refreshRooms = useCallback(async () => {
    try {
      const list = await card414Api.listRooms();
      setRooms(list);
    } catch { /* */ }
  }, []);

  return (
    <div className="max-w-md mx-auto px-4 py-12 space-y-8">
      <div className="text-center space-y-2">
        <h1 className="text-3xl font-bold">414 扑克</h1>
        <p className="text-sm text-muted-foreground">四人组队对战</p>
      </div>

      {/* 昵称 */}
      <div className="space-y-2">
        <label className="text-sm font-medium">昵称</label>
        <input
          type="text"
          value={nickname}
          onChange={e => setNickname(e.target.value)}
          maxLength={16}
          placeholder="输入你的昵称"
          className="w-full rounded-lg border bg-card px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-primary"
        />
      </div>

      {/* 创建房间 */}
      <Button onClick={createRoom} disabled={loading} className="w-full" size="lg">
        创建房间
      </Button>

      {/* 加入房间 */}
      <div className="flex gap-2">
        <input
          type="text"
          value={joinCode}
          onChange={e => setJoinCode(e.target.value.toUpperCase())}
          maxLength={6}
          placeholder="房间号"
          className="flex-1 rounded-lg border bg-card px-3 py-2 text-sm uppercase outline-none focus:ring-2 focus:ring-primary tracking-widest text-center font-mono"
        />
        <Button onClick={joinRoom} disabled={loading} variant="secondary">
          加入
        </Button>
      </div>

      {/* 房间列表 */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium">公开房间</span>
          <button onClick={refreshRooms} className="text-xs text-muted-foreground hover:text-foreground">
            刷新
          </button>
        </div>
        {rooms.length === 0 ? (
          <p className="text-xs text-muted-foreground text-center py-4">暂无公开房间</p>
        ) : (
          <div className="space-y-1">
            {rooms.map(r => {
              const count = r.seats.filter(s => s.uuid).length;
              return (
                <button
                  key={r.roomCode}
                  onClick={() => { setJoinCode(r.roomCode); }}
                  className={cn(
                    'w-full flex items-center justify-between rounded-lg border px-3 py-2 text-sm',
                    'hover:bg-muted/50 transition-colors'
                  )}
                >
                  <span className="font-mono">{r.roomCode}</span>
                  <span className="text-muted-foreground">{count}/4</span>
                </button>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
