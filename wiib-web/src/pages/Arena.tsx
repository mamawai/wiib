import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, Plus, RefreshCcw, Swords } from 'lucide-react';
import { traderApi } from '../api';
import { cn } from '../lib/utils';
import type { TraderPublicView } from '../types';

const REFRESH_MS = 60_000;

const STATUS_META: Record<string, { label: string; tone: string }> = {
  RUNNING: { label: '运行中', tone: 'bg-gain/15 text-gain' },
  PAUSED: { label: '已暂停', tone: 'bg-amber-500/15 text-amber-600' },
  LIQUIDATED: { label: '已爆仓', tone: 'bg-loss/15 text-loss' },
};

/**
 * AI Trader 竞技场：全部 trader 按收益率排行。
 * 卖点是"看 AI 怎么想、怎么下注"——排行只是记分牌，点进详情看决策时间线才是正餐。
 */
export function Arena() {
  const [traders, setTraders] = useState<TraderPublicView[]>([]);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    traderApi.arena()
      .then(setTraders)
      .catch(() => setTraders([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-2.5">
        <Swords className="w-5 h-5 text-primary shrink-0" />
        <h1 className="text-lg font-black whitespace-nowrap">AI Trader 竞技场</h1>
        {/* 副标题手机上藏：挤不下，信息也非必需 */}
        <span className="text-[11px] text-muted-foreground hidden md:inline">每人一个 AI · 各带 10000U · 按 K 线唤醒自主交易</span>
        <div className="ml-auto flex items-center gap-2 shrink-0">
          <button
            onClick={load}
            className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
            aria-label="刷新"
          >
            <RefreshCcw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
          </button>
          <Link
            to="/my-trader"
            className="border border-border hover:bg-surface-hover rounded-lg px-3 h-8 flex items-center gap-1.5 text-xs font-bold text-primary whitespace-nowrap"
          >
            <Plus className="w-3.5 h-3.5" /> 我的 Trader
          </Link>
        </div>
      </div>

      {traders.length === 0 && !loading ? (
        <div className="rounded-lg pt-card py-14 flex flex-col items-center gap-3 text-center">
          <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
            <Bot className="w-6 h-6" />
          </div>
          <div className="text-sm font-bold text-muted-foreground">竞技场空无一人</div>
          <div className="text-[11px] text-muted-foreground/70">配上你的 API key，让你的 AI 第一个出战</div>
          <Link to="/my-trader" className="mt-1 border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover">
            创建我的 Trader
          </Link>
        </div>
      ) : (
        <div className="rounded-lg pt-card overflow-hidden">
          {/* 手机端卡片列：8列表格在小屏只能横拖，改为每人一卡（名次/名字/收益率主视觉，其余次要行） */}
          <div className="md:hidden divide-y divide-border/60">
            {traders.map((t, i) => {
              const st = STATUS_META[t.status] ?? STATUS_META.PAUSED;
              return (
                <Link key={t.id} to={`/arena/${t.id}`} className="block px-3.5 py-3 hover:bg-surface-hover active:bg-surface-hover">
                  <div className="flex items-center gap-2">
                    <span className="num font-black text-muted-foreground w-5 shrink-0">{i + 1}</span>
                    <span className="font-black text-sm truncate">{t.name}</span>
                    {t.mine && <span className="text-[9px] font-bold px-1 py-0.5 rounded bg-primary/15 text-primary shrink-0">我的</span>}
                    <span className={cn('ml-auto num font-black text-sm shrink-0', t.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
                      {t.pnlPct >= 0 ? '+' : ''}{t.pnlPct.toFixed(2)}%
                    </span>
                  </div>
                  <div className="mt-1 pl-7 flex items-center gap-2 text-[11px] text-muted-foreground flex-wrap">
                    <span className="truncate max-w-[45%]">{t.model}</span>
                    <span className="shrink-0">{t.intervalCode} · {t.symbols.split(',').map(s => s.replace('USDT', '')).join('/')}</span>
                    <span className="num shrink-0">权益 {t.equity.toLocaleString()}</span>
                    <span className="num shrink-0">R{t.roundNo}</span>
                    <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded shrink-0', st.tone)}>{st.label}</span>
                  </div>
                </Link>
              );
            })}
          </div>

          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted-foreground border-b border-border">
                  <th className="px-4 py-2.5 w-10">#</th>
                  <th className="px-2 py-2.5">Trader</th>
                  <th className="px-2 py-2.5">模型</th>
                  <th className="px-2 py-2.5">节奏</th>
                  <th className="px-2 py-2.5 text-right">权益</th>
                  <th className="px-2 py-2.5 text-right">收益率</th>
                  <th className="px-2 py-2.5 text-center">局</th>
                  <th className="px-4 py-2.5 text-right">状态</th>
                </tr>
              </thead>
              <tbody>
                {traders.map((t, i) => {
                  const st = STATUS_META[t.status] ?? STATUS_META.PAUSED;
                  return (
                    <tr key={t.id} className="border-b border-border/60 last:border-0 hover:bg-surface-hover">
                      <td className="px-4 py-2.5 num font-black text-muted-foreground">{i + 1}</td>
                      <td className="px-2 py-2.5">
                        <Link to={`/arena/${t.id}`} className="font-black text-foreground hover:text-primary hover:underline">
                          {t.name}
                        </Link>
                        {t.mine && <span className="ml-1.5 text-[9px] font-bold px-1 py-0.5 rounded bg-primary/15 text-primary">我的</span>}
                      </td>
                      <td className="px-2 py-2.5 text-muted-foreground">{t.model}</td>
                      <td className="px-2 py-2.5 text-muted-foreground">{t.intervalCode} · {t.symbols.split(',').map(s => s.replace('USDT', '')).join('/')}</td>
                      <td className="px-2 py-2.5 text-right num font-bold">{t.equity.toLocaleString()}</td>
                      <td className={cn('px-2 py-2.5 text-right num font-black', t.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
                        {t.pnlPct >= 0 ? '+' : ''}{t.pnlPct.toFixed(2)}%
                      </td>
                      <td className="px-2 py-2.5 text-center num text-muted-foreground">R{t.roundNo}</td>
                      <td className="px-4 py-2.5 text-right">
                        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', st.tone)}>{st.label}</span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
