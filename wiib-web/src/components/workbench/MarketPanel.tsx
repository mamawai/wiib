import { useCallback, useEffect, useState } from 'react';
import { Activity, RefreshCcw } from 'lucide-react';
import { quantApi } from '../../api';
import { cn } from '../../lib/utils';
import { AnalysisCard } from './AnalysisCard';
import type { QuantDeepAnalysisView } from '../../types';

// 只展示 quant 实际监控的标的（WATCH_SYMBOLS=BTC/ETH）
const SYMBOLS = ['BTCUSDT', 'ETHUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH' };
const REFRESH_MS = 60_000;

/**
 * 市场研判：最新一次深研判 + 币种切换。
 * 历史时间线已删——BYOK 后"谁烧的 key 全站可见"的全局历史语义不成立，按用户区分要动后端，先不做。
 * 深研判为 chat/定频/哨兵按需触发，60s 轮询足够。
 */
export function MarketPanel() {
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [analysis, setAnalysis] = useState<QuantDeepAnalysisView | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback((sym: string) => {
    quantApi.latestAnalysis(sym)
      .then(setAnalysis)
      .catch(() => setAnalysis(null))
      .finally(() => setLoading(false));
  }, []);

  /** 切币种在事件里重置再拉，effect 只负责首拉 + 定时刷新 */
  const switchSymbol = useCallback((sym: string) => {
    setSymbol(sym);
    setLoading(true);
    setAnalysis(null);
    load(sym);
  }, [load]);

  useEffect(() => { load('BTCUSDT'); }, [load]);
  useEffect(() => {
    const timer = setInterval(() => load(symbol), REFRESH_MS);
    return () => clearInterval(timer);
  }, [symbol, load]);

  return (
    <div className="rounded-lg pt-card p-4 space-y-3">
      <div className="flex items-center gap-2">
        <Activity className="w-4 h-4 text-primary" />
        <span className="text-sm font-black">深研判</span>
        <div className="ml-auto flex items-center gap-1">
          {SYMBOLS.map(s => (
            <button
              key={s}
              onClick={() => switchSymbol(s)}
              className={cn(
                'text-[11px] font-bold px-2.5 py-1.5 sm:px-2 sm:py-1 rounded-lg transition-all',
                symbol === s ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground',
              )}
            >
              {SYM_LABEL[s]}
            </button>
          ))}
          <button
            onClick={() => load(symbol)}
            className="border border-border hover:bg-surface-hover w-8 h-8 sm:w-7 sm:h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
            aria-label="刷新"
          >
            <RefreshCcw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
          </button>
        </div>
      </div>

      <AnalysisCard analysis={analysis} />
    </div>
  );
}
