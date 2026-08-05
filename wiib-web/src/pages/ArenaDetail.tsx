import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ArrowDownRight, ArrowUpRight, Bot, ChevronDown, ChevronLeft, ChevronUp, Loader2, RefreshCcw,
} from 'lucide-react';
import { traderApi } from '../api';
import { EquityChart } from '../components/EquityChart';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import type { AiTraderDecisionView, TraderDetailView, TraderEquityPoint } from '../types';
import type { TnEquityPoint } from '../types/testnet';

const REFRESH_MS = 60_000;

const STATUS_META: Record<string, { label: string; tone: string }> = {
  RUNNING: { label: '运行中', tone: 'bg-gain/15 text-gain' },
  PAUSED: { label: '已暂停', tone: 'bg-amber-500/15 text-amber-600' },
  LIQUIDATED: { label: '已爆仓', tone: 'bg-loss/15 text-loss' },
};

const DECISION_STATUS: Record<string, { label: string; tone: string }> = {
  OK: { label: '决策', tone: 'bg-primary/15 text-primary' },
  ERROR: { label: '失败', tone: 'bg-loss/15 text-loss' },
  SKIPPED: { label: '跳过', tone: 'bg-muted text-muted-foreground' },
};

interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
}

/** 单条决策卡：时间/权益/动作徽章 + 推理全文折叠——竞技场的观赏核心。 */
function DecisionCard({ d }: { d: AiTraderDecisionView }) {
  const [open, setOpen] = useState(false);
  const meta = DECISION_STATUS[d.status] ?? DECISION_STATUS.OK;
  const actions = useMemo<ActionRow[]>(() => {
    try {
      return d.actionsJson ? JSON.parse(d.actionsJson) as ActionRow[] : [];
    } catch {
      return [];
    }
  }, [d.actionsJson]);
  const trades = actions.filter(a => a.tool !== 'get_account');
  const reasoning = d.reasoning?.trim() || '';
  const preview = reasoning.length > 120 ? reasoning.slice(0, 120) + '…' : reasoning;

  return (
    <div className="rounded-md border border-border bg-card p-3 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', meta.tone)}>{meta.label}</span>
        <span className="text-[11px] num text-muted-foreground">{fmtDateTime(d.wakeTime)}</span>
        {d.equity != null && (
          <span className="text-[11px] text-muted-foreground">权益 <span className="num font-bold text-foreground">{fmtNum(d.equity)}</span></span>
        )}
        {d.latencyMs != null && <span className="ml-auto text-[10px] text-muted-foreground/70 num">{(d.latencyMs / 1000).toFixed(1)}s · {d.toolCalls}次工具</span>}
      </div>

      {trades.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {trades.map((a, i) => (
            <span
              key={i}
              className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded border',
                a.rejected ? 'border-loss/40 text-loss' : a.status === 'error' ? 'border-loss/40 text-loss' : 'border-border text-foreground')}
              title={a.rejected || a.error || undefined}
            >
              {a.tool}
              {a.args?.playType != null && ` · ${String(a.args.playType)}`}
              {a.rejected && ' ✕拒'}
              {a.status === 'error' && ' ✕错'}
            </span>
          ))}
        </div>
      )}

      {d.status === 'ERROR' && d.error && (
        <p className="text-[11px] text-loss leading-relaxed">{d.error}</p>
      )}

      {reasoning && (
        <div className="text-xs leading-relaxed text-foreground/90">
          <p className="whitespace-pre-wrap">{open ? reasoning : preview}</p>
          {reasoning.length > 120 && (
            <button onClick={() => setOpen(!open)} className="mt-1 text-[10px] font-bold text-primary flex items-center gap-0.5">
              {open ? <>收起 <ChevronUp className="w-3 h-3" /></> : <>展开全文 <ChevronDown className="w-3 h-3" /></>}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

/** trader 详情：净值曲线 + 实时持仓挂单 + 决策时间线。 */
export function ArenaDetail() {
  const { id } = useParams();
  const traderId = Number(id);
  const [detail, setDetail] = useState<TraderDetailView | null>(null);
  const [curve, setCurve] = useState<TraderEquityPoint[]>([]);
  const [decisions, setDecisions] = useState<AiTraderDecisionView[]>([]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    void traderApi.detail(traderId).then(setDetail).catch(() => setDetail(null));
    void traderApi.equityCurve(traderId).then(setCurve).catch(() => setCurve([]));
    void traderApi.decisions(traderId, 50).then(list => {
      setDecisions(list);
      setHasMore(list.length >= 50);
    }).catch(() => setDecisions([]));
  }, [traderId]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const loadMore = useCallback(() => {
    const oldest = decisions[decisions.length - 1];
    if (!oldest) return;
    setLoadingMore(true);
    traderApi.decisions(traderId, 50, oldest.wakeTime)
      .then(list => {
        setDecisions(prev => [...prev, ...list]);
        setHasMore(list.length >= 50);
      })
      .finally(() => setLoadingMore(false));
  }, [traderId, decisions]);

  // 净值曲线复用 EquityChart（累计盈亏口径）：equity-10000 起点归零
  const chartPoints = useMemo<TnEquityPoint[]>(
    () => curve.map(p => ({ time: p.wakeTime, cumPnl: p.equity - 10000 })),
    [curve]);

  const t = detail?.trader;
  const st = t ? (STATUS_META[t.status] ?? STATUS_META.PAUSED) : null;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-2.5 flex-wrap">
        <Link to="/arena" className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="返回竞技场">
          <ChevronLeft className="w-4 h-4" />
        </Link>
        <Bot className="w-5 h-5 text-primary" />
        <h1 className="text-lg font-black">{t?.name ?? '…'}</h1>
        {t && st && (
          <>
            <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', st.tone)}>{st.label}</span>
            <span className="text-[11px] text-muted-foreground">{t.model} · {t.intervalCode} · R{t.roundNo}</span>
            <span className={cn('num font-black', t.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
              {t.pnlPct >= 0 ? '+' : ''}{t.pnlPct.toFixed(2)}%
            </span>
          </>
        )}
        <button
          onClick={load}
          className="ml-auto border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
          aria-label="刷新"
        >
          <RefreshCcw className="w-3.5 h-3.5" />
        </button>
      </div>

      {t?.pausedReason && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-600 font-bold">
          {t.pausedReason}
        </div>
      )}

      <div className="grid lg:grid-cols-2 gap-4 items-start">
        <div className="space-y-4">
          <div className="rounded-lg pt-card p-4 space-y-2">
            <span className="microlabel">本局净值（初始 10000）</span>
            {chartPoints.length > 1
              ? <EquityChart points={chartPoints} />
              : <div className="py-10 text-center text-xs text-muted-foreground">数据点不足，等它多醒几次</div>}
          </div>

          <div className="rounded-lg pt-card p-4 space-y-2">
            <span className="microlabel">当前持仓 / 挂单</span>
            {detail && detail.positions.length === 0 && detail.pendingOrders.length === 0 && (
              <div className="py-6 text-center text-xs text-muted-foreground">空仓观望中</div>
            )}
            {detail?.positions.map(p => {
              const isLong = p.side === 'LONG';
              return (
                <div key={p.id} className="rounded-md border border-border bg-card p-2.5 flex items-center gap-2 text-[11px] flex-wrap">
                  {isLong ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />}
                  <span className="font-black text-xs">{p.symbol}</span>
                  <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
                    {isLong ? '多' : '空'} {p.leverage}x
                  </span>
                  <span className="text-muted-foreground">数量 <span className="num font-bold text-foreground">{p.quantity}</span></span>
                  <span className="text-muted-foreground">开仓 <span className="num font-bold text-foreground">{fmtNum(p.entryPrice)}</span></span>
                  <span className="ml-auto">
                    <span className={cn('num font-black', (p.unrealizedPnl ?? 0) >= 0 ? 'text-gain' : 'text-loss')}>
                      {(p.unrealizedPnl ?? 0) >= 0 ? '+' : ''}{fmtNum(p.unrealizedPnl ?? 0)}
                    </span>
                  </span>
                </div>
              );
            })}
            {detail?.pendingOrders.map(o => (
              <div key={String((o as unknown as { id?: number }).id ?? Math.random())} className="rounded-md border border-dashed border-border bg-card-2 p-2.5 text-[11px] text-muted-foreground">
                挂单 {JSON.stringify(o).slice(0, 120)}
              </div>
            ))}
          </div>
        </div>

        <div className="rounded-lg pt-card p-4 space-y-2.5">
          <span className="microlabel">决策时间线 · 看它怎么想</span>
          {decisions.length === 0 && (
            <div className="py-10 text-center text-xs text-muted-foreground">还没有任何决策，启动后每根K线醒一次</div>
          )}
          <div className="space-y-2.5 max-h-[70vh] overflow-y-auto pr-1">
            {decisions.map(d => <DecisionCard key={d.id} d={d} />)}
            {hasMore && decisions.length > 0 && (
              <button
                onClick={loadMore}
                disabled={loadingMore}
                className="w-full border border-border hover:bg-surface-hover rounded-lg py-2 text-xs font-bold text-muted-foreground hover:text-primary flex items-center justify-center gap-1.5"
              >
                {loadingMore && <Loader2 className="w-3.5 h-3.5 animate-spin" />} 加载更早
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
