import { useCallback, useEffect, useState } from 'react';
import { Activity, RefreshCcw } from 'lucide-react';
import { quantApi } from '../../api';
import { useUserStore } from '../../stores/userStore';
import { cn } from '../../lib/utils';
import { ChatPanel } from './ChatPanel';
import { AnalysisCard } from './AnalysisCard';
import type { QuantDeepAnalysisView } from '../../types';

// 只展示 quant 实际监控的标的（WATCH_SYMBOLS=BTC/ETH）
const SYMBOLS = ['BTCUSDT', 'ETHUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH' };
const REFRESH_MS = 60_000;

const fmtTime = (t: number) =>
  new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

/**
 * 研判工作台：左对话（Supervisor 调度）右深研判（详情 + 历史时间线）。
 * 预测/脆弱度展示已随预测管线下线（2026-08）；深研判为 chat 按需触发，60s 轮询足够。
 */
export function Workbench() {
  // 数据区全员可看；Supervisor 对话按 token 计费，仅管理员可用（后端 @RequireAdmin 同步门禁）
  const isAdmin = useUserStore(s => s.user)?.id === 1;
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [analyses, setAnalyses] = useState<QuantDeepAnalysisView[]>([]);
  const [selected, setSelected] = useState<QuantDeepAnalysisView | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback((sym: string) => {
    quantApi.analysisList(sym, 30)
      .then(list => setAnalyses(list))
      .catch(() => setAnalyses([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    setLoading(true);
    setSelected(null);
    load(symbol);
    const timer = setInterval(() => load(symbol), REFRESH_MS);
    return () => clearInterval(timer);
  }, [symbol, load]);

  const displayed = selected ?? analyses[0] ?? null;

  return (
    <div className="space-y-4">
      <div className={`grid gap-4 items-start ${isAdmin ? 'lg:grid-cols-2' : ''}`}>
        {isAdmin && <ChatPanel />}

        <div className="space-y-4">
          <div className="rounded-lg pt-card p-4 space-y-3">
            <div className="flex items-center gap-2">
              <Activity className="w-4 h-4 text-primary" />
              <span className="text-sm font-black">深研判</span>
              <div className="ml-auto flex items-center gap-1">
                {SYMBOLS.map(s => (
                  <button
                    key={s}
                    onClick={() => setSymbol(s)}
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

            <AnalysisCard analysis={displayed} />
          </div>

          {/* 历史研判时间线：点击切换详情 */}
          {analyses.length > 1 && (
            <div className="rounded-lg pt-card p-4 space-y-2">
              <span className="microlabel">历史研判</span>
              <div className="space-y-1 max-h-72 overflow-y-auto">
                {analyses.map(a => (
                  <button
                    key={a.id}
                    onClick={() => setSelected(a)}
                    className={cn(
                      'w-full text-left rounded-md border px-3 py-2 text-xs leading-relaxed transition-all',
                      displayed?.id === a.id
                        ? 'border-primary/50 bg-card-2'
                        : 'border-border hover:bg-surface-hover',
                    )}
                  >
                    <span className="num text-muted-foreground mr-2">{fmtTime(a.closeTime)}</span>
                    {a.noDirection && (
                      <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600 mr-1">看不清</span>
                    )}
                    <span className="text-foreground">{a.narrative?.slice(0, 60)}{(a.narrative?.length ?? 0) > 60 ? '…' : ''}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
