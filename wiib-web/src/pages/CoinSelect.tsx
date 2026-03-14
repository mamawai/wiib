import { useNavigate } from 'react-router-dom';
import { cn } from '../lib/utils';
import { COIN_LIST } from '../lib/coinConfig';

export function CoinSelect() {
  const navigate = useNavigate();

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-6">
      <h1 className="text-2xl font-bold text-center">币种交易</h1>
      <div className={`grid grid-cols-1 ${COIN_LIST.length >= 3 ? 'sm:grid-cols-3' : 'sm:grid-cols-2'} gap-4`}>
        {COIN_LIST.map(c => (
          <button
            key={c.symbol}
            onClick={() => navigate(`/coin/${c.symbol}`)}
            className={cn(
              'flex flex-col items-center gap-3 rounded-2xl border-[3px] border-edge p-6 transition-all cursor-pointer',
              'shadow-[4px_4px_0_0_var(--color-edge)] hover:shadow-[2px_2px_0_0_var(--color-edge)] hover:translate-x-0.5 hover:translate-y-0.5',
              'active:shadow-[0px_0px_0_0_var(--color-edge)] active:translate-x-1 active:translate-y-1',
              c.bgClass, `hover:${c.bgClass.replace('/10', '/20')}`,
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
