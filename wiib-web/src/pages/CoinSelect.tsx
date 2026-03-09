import { useNavigate } from 'react-router-dom';
import { Bitcoin, Coins } from 'lucide-react';
import { cn } from '../lib/utils';

const COINS = [
  {
    key: 'BTCUSDT',
    icon: Bitcoin,
    title: 'BTC',
    desc: '比特币 / USDT 模拟交易',
    color: 'text-orange-400',
    bg: 'bg-orange-500/10 hover:bg-orange-500/20 border-orange-500/20',
  },
  {
    key: 'PAXGUSDT',
    icon: Coins,
    title: 'PAXG',
    desc: 'PAX Gold / USDT · 1枚=1盎司黄金（31.1035克）',
    color: 'text-yellow-400',
    bg: 'bg-yellow-500/10 hover:bg-yellow-500/20 border-yellow-500/20',
  },
] as const;

export function CoinSelect() {
  const navigate = useNavigate();

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <h1 className="text-2xl font-bold text-center">币种交易</h1>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        {COINS.map(c => (
          <button
            key={c.key}
            onClick={() => navigate(`/coin/${c.key}`)}
            className={cn(
              'flex flex-col items-center gap-3 rounded-2xl border-[3px] border-edge p-6 transition-all cursor-pointer',
              'shadow-[4px_4px_0_0_var(--color-edge)] hover:shadow-[2px_2px_0_0_var(--color-edge)] hover:translate-x-[2px] hover:translate-y-[2px]',
              'active:shadow-[0px_0px_0_0_var(--color-edge)] active:translate-x-[4px] active:translate-y-[4px]',
              c.bg,
            )}
          >
            <c.icon className={cn('w-10 h-10', c.color)} />
            <div className="text-center">
              <div className="font-bold text-lg">{c.title}</div>
              <div className="text-xs text-muted-foreground mt-1">{c.desc}</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
