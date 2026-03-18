import { useNavigate } from 'react-router-dom';
import { DollarSign } from 'lucide-react';
import { cn } from '../lib/utils';
import { COIN_LIST } from '../lib/coinConfig';

export function CoinSelect() {
  const navigate = useNavigate();

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <div className="flex items-center justify-center gap-2.5">
        <div className="p-1.5 rounded-lg bg-amber-500/10">
          <DollarSign className="w-5 h-5 text-amber-500" />
        </div>
        <h1 className="text-xl font-bold">币种交易</h1>
      </div>
      <div className={`grid grid-cols-1 ${COIN_LIST.length >= 3 ? 'sm:grid-cols-3' : 'sm:grid-cols-2'} gap-4`}>
        {COIN_LIST.map(c => (
          <button
            key={c.symbol}
            onClick={() => navigate(`/coin/${c.symbol}`)}
            className={cn(
              'flex flex-col items-center gap-3 rounded-2xl p-6 transition-all cursor-pointer neu-btn-sm',
              c.bgClass, c.hoverBgClass,
            )}
          >
            <c.icon className={cn('w-10 h-10', c.colorClass)} />
            <div className="text-center">
              <div className="font-bold text-lg">{c.name}</div>
              <div className="text-xs text-muted-foreground mt-1">{c.desc}</div>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
