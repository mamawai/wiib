import { useNavigate } from 'react-router-dom';
import { Pickaxe, Spade } from 'lucide-react';
import { cn } from '../lib/utils';

const GAMES = [
  {
    key: 'mines',
    path: '/mines',
    icon: Pickaxe,
    title: '翻翻爆金币',
    desc: '5×5 格子藏 5 雷，翻得越多倍率越高',
    color: 'text-amber-400',
    bg: 'bg-amber-500/10 hover:bg-amber-500/20 border-amber-500/20',
  },
  {
    key: 'blackjack',
    path: '/blackjack',
    icon: Spade,
    title: '21点',
    desc: '经典 Blackjack，积分对战庄家',
    color: 'text-emerald-400',
    bg: 'bg-emerald-500/10 hover:bg-emerald-500/20 border-emerald-500/20',
  },
] as const;

export function Games() {
  const navigate = useNavigate();

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <h1 className="text-2xl font-bold text-center">小游戏</h1>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        {GAMES.map(g => (
          <button
            key={g.key}
            onClick={() => navigate(g.path)}
            className={cn(
              'flex flex-col items-center gap-3 rounded-xl border p-6 transition-all cursor-pointer active:scale-[0.97]',
              g.bg,
            )}
          >
            <g.icon className={cn('w-10 h-10', g.color)} />
            <div className="text-center">
              <div className="font-bold text-lg">{g.title}</div>
              <div className="text-xs text-muted-foreground mt-1">{g.desc}</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
