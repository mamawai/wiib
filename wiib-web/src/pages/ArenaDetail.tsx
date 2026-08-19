import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  ArrowDownRight, ArrowUpRight, Bot, ChevronDown, ChevronLeft, ChevronUp, ClipboardList, GraduationCap, Loader2,
  MousePointerClick, NotebookPen, RefreshCcw, Zap,
} from 'lucide-react';
import { traderApi } from '../api';
import { STATUS_META } from './Arena';
import { EquityChart } from '../components/EquityChart';
import { Markdown } from '../components/Markdown';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../lib/utils';
import type { AiTraderDecisionView, AiTraderPlanView, PlanRevision, TraderDetailView, TraderEquityPoint } from '../types';
import type { TnEquityPoint } from '../types/testnet';

const REFRESH_MS = 60_000;

const DECISION_STATUS: Record<string, { label: string; tone: string }> = {
  OK: { label: '决策', tone: 'bg-primary/15 text-primary' },
  ERROR: { label: '失败', tone: 'bg-loss/15 text-loss' },
  SKIPPED: { label: '跳过', tone: 'bg-muted text-muted-foreground' },
};

/** 工具名中文化：交易动作独立成行展示参数，数据查询弱化成 chip——一眼分清"看了什么"和"做了什么" */
const TOOL_CN: Record<string, string> = {
  open_position: '开仓', close_position: '平仓', set_stop_loss: '移动止损',
  set_take_profit: '移动止盈', cancel_order: '撤单', write_plan: '补立计划',
  get_account: '查账户', klines: 'K线', indicators: '指标', market_snapshot: '市场快照',
  funding_history: '资金费', orderbook_depth: '盘口', option_iv: '期权IV', news_search: '快讯',
};
const TRADE_TOOLS = new Set(['open_position', 'close_position', 'set_stop_loss', 'set_take_profit', 'cancel_order', 'write_plan']);

interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
}

/** 交易动作的关键参数一行话（按工具挑重点，不倒整个 JSON） */
function tradeArgsSummary(a: ActionRow): string {
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  switch (a.tool) {
    case 'open_position': {
      const parts = [g('symbol'), g('side') === 'LONG' ? '做多' : g('side') === 'SHORT' ? '做空' : g('side'),
        g('quantity') && `${g('quantity')}张`, g('leverage') && `${g('leverage')}x`,
        g('stopLossPrice') && `止损${g('stopLossPrice')}`, g('takeProfitPrice') && `止盈${g('takeProfitPrice')}`,
        g('playType')];
      return parts.filter(Boolean).join(' · ');
    }
    case 'close_position':
      return [`仓位#${g('positionId')}`, g('quantity') && `${g('quantity')}张`, g('reason')].filter(Boolean).join(' · ');
    case 'set_stop_loss':
      return [`→${g('stopLossPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'set_take_profit':
      return [`→${g('takeProfitPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'write_plan':
      return [g('playType'), g('invalidationCondition') && `失效条件：${g('invalidationCondition')}`].filter(Boolean).join(' · ');
    case 'cancel_order':
      return `订单#${g('orderId')}`;
    default:
      return '';
  }
}

/** 单条决策卡：时间/权益 + 数据工具chip + 交易动作行（参数/拒因） + 推理 markdown 折叠——竞技场的观赏核心。 */
function DecisionCard({ d }: { d: AiTraderDecisionView }) {
  const [open, setOpen] = useState(false);
  // 复盘行不是交易决策，徽章与配色单独一套：reviewer 的每日日志，时间线上要一眼认出
  // 学习行同样不交易，但来源不同（复盘看自己、学习看同侪），再分一套色——两种日志行混在时间线上要能一眼分清
  const meta = d.kind === 'REVIEW'
    ? (d.status === 'OK'
        ? { label: '每日复盘', tone: 'bg-violet-500/15 text-violet-500' }
        : { label: '复盘失败', tone: 'bg-loss/15 text-loss' })
    : d.kind === 'LEARN'
      ? (d.status === 'OK'
          ? { label: '同侪学习', tone: 'bg-sky-500/15 text-sky-500' }
          : { label: '学习失败', tone: 'bg-loss/15 text-loss' })
      : DECISION_STATUS[d.status] ?? DECISION_STATUS.OK;
  const actions = useMemo<ActionRow[]>(() => {
    try {
      return d.actionsJson ? JSON.parse(d.actionsJson) as ActionRow[] : [];
    } catch {
      return [];
    }
  }, [d.actionsJson]);
  const dataCalls = actions.filter(a => !TRADE_TOOLS.has(a.tool));
  const trades = actions.filter(a => TRADE_TOOLS.has(a.tool));
  const reasoning = d.reasoning?.trim() || '';
  // 折叠预览是纯文本，去掉 markdown 符号免得满屏井号
  const preview = reasoning.replace(/[#*`]/g, '').replace(/\s+/g, ' ').slice(0, 120) + (reasoning.length > 120 ? '…' : '');

  return (
    <div className={cn('rounded-md border bg-card p-3 space-y-2',
      d.kind === 'REVIEW' ? 'border-violet-500/35 bg-violet-500/[0.04]'
        : d.kind === 'LEARN' ? 'border-sky-500/35 bg-sky-500/[0.04]' : 'border-border')}>
      <div className="flex items-center gap-2 flex-wrap">
        <span className={cn('inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded', meta.tone)}>
          {d.kind === 'REVIEW' && <NotebookPen className="w-3 h-3" />}
          {d.kind === 'LEARN' && <GraduationCap className="w-3 h-3" />}{meta.label}
        </span>
        {/* 警报唤醒凸显：这条不是例行K线节奏，是哨兵在极端波动时叫醒的 */}
        {d.kind === 'ALERT' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">
            <Zap className="w-3 h-3" />波动警报
          </span>
        )}
        {/* 手动唤醒凸显：扳机在主人手里（对话轨 wake_trader），不是例行K线节奏 */}
        {d.kind === 'MANUAL' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-teal-500/15 text-teal-600">
            <MousePointerClick className="w-3 h-3" />手动唤醒
          </span>
        )}
        <span className="text-[11px] num text-muted-foreground">{fmtDateTime(d.wakeTime)}</span>
        {d.equity != null && (
          <span className="text-[11px] text-muted-foreground">权益 <span className="num font-bold text-foreground">{fmtNum(d.equity)}</span></span>
        )}
        <span className="ml-auto text-[10px] text-muted-foreground/70 num">
          {[
            d.latencyMs != null && `${(d.latencyMs / 1000).toFixed(1)}s`,
            // 复盘无工具（单次调用）不显示"0次工具"占位；学习是 ReactAgent 有 peer_insights 工具，照常显示
            d.kind !== 'REVIEW' && `${d.toolCalls}次工具`,
            d.modelCalls != null && `${d.modelCalls}次模型`,
            // token 为 null＝上游端点没报 usage，显示「—」而不是 0：0 会被读成"这轮没花钱"
            d.modelCalls != null && `${d.totalTokens == null ? '—' : fmtTokens(d.totalTokens)} tokens`,
          ].filter(Boolean).join(' · ')}
        </span>
      </div>

      {/* 数据查询：弱化 chip，交代"它看了什么"再决策 */}
      {dataCalls.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {dataCalls.map((a, i) => (
            <span key={i} className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground/80"
                  title={a.error || undefined}>
              {TOOL_CN[a.tool] ?? a.tool}{a.status === 'error' && ' ✕'}
            </span>
          ))}
        </div>
      )}

      {/* 交易动作：独立成行带参数与拒因——"它做了什么"是时间线的主角 */}
      {trades.length > 0 && (
        <div className="space-y-1">
          {trades.map((a, i) => {
            const failed = a.rejected || a.status === 'error';
            // unknown=重发确认后仍没问到结果，可能成交也可能没有。不单独标出来就跟成交长得一模一样，
            // 而这条时间线是对所有人公开的账面事实
            const unknown = a.status === 'unknown';
            return (
              <div key={i} className={cn('rounded border px-2 py-1.5 text-[11px] leading-relaxed',
                failed ? 'border-loss/40 bg-loss/5'
                  : unknown ? 'border-warning/40 bg-warning/5' : 'border-border bg-card-2/60')}>
                <span className={cn('font-black mr-1.5',
                  failed ? 'text-loss' : unknown ? 'text-warning' : 'text-foreground')}>
                  {TOOL_CN[a.tool] ?? a.tool}
                  {a.rejected ? '·被拒' : a.status === 'error' ? '·出错' : unknown ? '·结果未知' : ''}
                </span>
                <span className="text-muted-foreground">{tradeArgsSummary(a)}</span>
                {a.rejected && <p className="mt-0.5 text-loss">{a.rejected}</p>}
                {a.error && <p className="mt-0.5 text-loss">{a.error}</p>}
              </div>
            );
          })}
        </div>
      )}

      {d.status === 'ERROR' && d.error && (
        <p className="text-[11px] text-loss leading-relaxed">{d.error}</p>
      )}

      {reasoning && (
        <div className="text-xs leading-relaxed text-foreground/90">
          {open ? <Markdown content={reasoning} /> : <p>{preview}</p>}
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

/** 持仓的交易计划卡：论点/失效条件/原始快照 + 修订历史——退出纪律的公开凭证。 */
function PlanBlock({ plan }: { plan: AiTraderPlanView }) {
  const revisions = useMemo<PlanRevision[]>(() => {
    try {
      return plan.revisionsJson ? JSON.parse(plan.revisionsJson) as PlanRevision[] : [];
    } catch {
      return [];
    }
  }, [plan.revisionsJson]);

  return (
    <div className="mt-1.5 rounded border border-primary/25 bg-primary/5 px-2.5 py-2 space-y-1 text-[11px] leading-relaxed">
      <div className="flex items-center gap-1.5 flex-wrap">
        <ClipboardList className="w-3 h-3 text-primary" />
        <span className="font-black text-primary">交易计划</span>
        {plan.playType && <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-primary/15 text-primary">{plan.playType}</span>}
        <span className="text-muted-foreground/80 num ml-auto">{fmtDateTime(plan.openedWakeTime)} 立</span>
      </div>
      {plan.signalsUsed && <p className="text-muted-foreground">依据：{plan.signalsUsed}</p>}
      <p><span className="font-bold text-foreground">失效条件：</span>{plan.invalidationCondition}</p>
      {/* 间距走 gap 不用空格字符：末项缺席时不会拖着个尾巴，窄屏也能换行 */}
      <p className="text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
        {plan.entryPrice != null && <span>入场 {fmtNum(plan.entryPrice)}</span>}
        {plan.stopLossPrice != null && <span>原始止损 {fmtNum(plan.stopLossPrice)}</span>}
        {plan.takeProfitPrice != null && <span>目标 {fmtNum(plan.takeProfitPrice)}</span>}
      </p>
      {revisions.length > 0 && (
        <div className="border-t border-primary/15 pt-1 space-y-0.5">
          {revisions.map((r, i) => (
            <p key={i} className="text-muted-foreground">
              <span className="num text-muted-foreground/70">{fmtDateTime(r.time)}</span>
              <span className="font-bold text-foreground/80 mx-1">{r.type}</span>
              {r.change && <span className="num">{r.change}</span>}
              {r.reason && <span> —— {r.reason}</span>}
            </p>
          ))}
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
  // null=跟随当前局（会随 detail 刷新自动跟上）；数字=用户选了某一历史局
  const [round, setRound] = useState<number | null>(null);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    void traderApi.detail(traderId).then(setDetail).catch(() => setDetail(null));
    void traderApi.equityCurve(traderId, round ?? undefined).then(setCurve).catch(() => setCurve([]));
    void traderApi.decisions(traderId, 50, undefined, round ?? undefined).then(list => {
      setDecisions(list);
      setHasMore(list.length >= 50);
    }).catch(() => setDecisions([]));
  }, [traderId, round]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const loadMore = useCallback(() => {
    const oldest = decisions[decisions.length - 1];
    if (!oldest) return;
    setLoadingMore(true);
    traderApi.decisions(traderId, 50, oldest.wakeTime, round ?? undefined)
      .then(list => {
        setDecisions(prev => [...prev, ...list]);
        setHasMore(list.length >= 50);
      })
      .finally(() => setLoadingMore(false));
  }, [traderId, decisions, round]);

  // 净值曲线复用 EquityChart（累计盈亏口径）：equity-10000 起点归零
  const chartPoints = useMemo<TnEquityPoint[]>(
    () => curve.map(p => ({ time: p.wakeTime, cumPnl: p.equity - 10000 })),
    [curve]);

  const t = detail?.trader;
  const st = t ? (STATUS_META[t.status] ?? STATUS_META.PAUSED) : null;
  // round=null 表示跟随当前局，落到显示时统一成具体数字
  const viewingRound = round ?? t?.roundNo ?? 1;

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
            <span className="text-[11px] text-muted-foreground break-all">{t.model ?? "未配置模型"} · {t.intervalCode}{t.wakeWindow && ` · ${t.wakeWindow}`} · R{t.roundNo}</span>
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
            <div className="flex items-center gap-2 flex-wrap">
              <span className="microlabel">
                {viewingRound === t?.roundNo ? '本局' : `R${viewingRound}`}净值（初始 10000）
              </span>
              {/* 局次切换：每局是独立子账户各自注资 10000，曲线与时间线必须同进同出，不能混排。
                  只列保留窗口内的局——后端只留最近 10 局（TraderService.MAX_ROUNDS_KEPT），更早的已整局清除 */}
              {(t?.roundNo ?? 1) > 1 && (
                <div className="ml-auto flex gap-1">
                  {Array.from({ length: Math.min(t?.roundNo ?? 1, 10) },
                    (_, i) => Math.max(1, (t?.roundNo ?? 1) - 9) + i).map(r => (
                    <button key={r} type="button"
                            onClick={() => setRound(r === t?.roundNo ? null : r)}
                            className={cn('px-1.5 h-6 rounded border text-[10px] font-bold num',
                              r === viewingRound
                                ? 'border-primary/60 bg-card-2 text-primary'
                                : 'border-border text-muted-foreground hover:text-foreground')}>
                      R{r}
                    </button>
                  ))}
                </div>
              )}
            </div>
            {chartPoints.length > 1
              ? <EquityChart points={chartPoints} />
              : <div className="py-10 text-center text-xs text-muted-foreground">数据点不足，等它多醒几次</div>}
          </div>

          <div className="rounded-lg pt-card p-4 space-y-2">
            <span className="microlabel">当前持仓 / 挂单</span>
            {/* 持仓是实时现查当前账户的，看历史局时这块跟左边的曲线不是同一局，得说清楚 */}
            {viewingRound !== t?.roundNo && (
              <p className="text-[10px] text-amber-600">
                下方持仓属于当前局 R{t?.roundNo}，与你正在查看的 R{viewingRound} 无关
              </p>
            )}
            {detail && detail.positions.length === 0 && detail.pendingOrders.length === 0 && (
              <div className="py-6 text-center text-xs text-muted-foreground">空仓观望中</div>
            )}
            {detail?.positions.map(p => {
              const isLong = p.side === 'LONG';
              // 计划按 (symbol, side) 一对一挂在持仓下：sim 同向自动并仓，任意时刻至多一仓
              const plan = detail.plans.find(pl => pl.symbol === p.symbol && pl.side === p.side);
              return (
                <div key={p.id} className="rounded-md border border-border bg-card p-2.5 text-[11px]">
                  <div className="flex items-center gap-2 flex-wrap">
                    {isLong ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />}
                    <span className="font-black text-xs">{p.symbol}</span>
                    <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
                      {isLong ? '多' : '空'} {p.leverage}x
                    </span>
                    <span className="text-muted-foreground">数量 <span className="num font-bold text-foreground">{p.quantity}</span></span>
                    <span className="text-muted-foreground">开仓 <span className="num font-bold text-foreground">{fmtNum(p.entryPrice)}</span></span>
                    <span className="ml-auto">
                      <span className={cn('num font-black', p.unrealizedPnl >= 0 ? 'text-gain' : 'text-loss')}>
                        {p.unrealizedPnl >= 0 ? '+' : ''}{fmtNum(p.unrealizedPnl)}
                      </span>
                    </span>
                  </div>
                  {/* 两个数组都空时 length 求值为 0，裸 && 会把 0 渲染到页面上，得先转 boolean */}
                  {Boolean(p.stopLosses?.length || p.takeProfits?.length) && (
                    <div className="mt-1 text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
                      {p.stopLosses?.length ? <span>当前止损 {p.stopLosses.map(s => fmtNum(s.price)).join(' / ')}</span> : null}
                      {p.takeProfits?.length ? <span>当前止盈 {p.takeProfits.map(t => fmtNum(t.price)).join(' / ')}</span> : null}
                    </div>
                  )}
                  {plan && <PlanBlock plan={plan} />}
                </div>
              );
            })}
            {detail?.pendingOrders.map(o => (
              <div key={o.orderId} className="rounded-md border border-dashed border-border bg-card-2 p-2.5 text-[11px] text-muted-foreground flex items-center gap-2 flex-wrap">
                <span className="font-bold text-foreground/80">限价挂单</span>
                <span className="font-black text-foreground">{o.symbol}</span>
                <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded',
                  o.orderSide.includes('LONG') ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
                  {o.orderSide.startsWith('OPEN') ? '开' : '平'}{o.orderSide.includes('LONG') ? '多' : '空'} {o.leverage}x
                </span>
                <span>数量 <span className="num font-bold text-foreground">{o.quantity}</span></span>
                {o.limitPrice != null && <span>限价 <span className="num font-bold text-foreground">{fmtNum(o.limitPrice)}</span></span>}
              </div>
            ))}
          </div>
        </div>

        <div className="rounded-lg pt-card p-4 space-y-2.5">
          <span className="microlabel">
            决策时间线 · 看它怎么想{viewingRound !== t?.roundNo && ` · R${viewingRound}`}
          </span>
          {decisions.length === 0 && (
            <div className="py-10 text-center text-xs text-muted-foreground">还没有任何决策，启动后每根K线醒一次，每日复盘另记一条</div>
          )}
          {/* 内层滚动只给桌面双栏用；手机上单栏堆叠，双层滚动是灾难，跟页面自然滚 */}
          <div className="space-y-2.5 lg:max-h-[70vh] lg:overflow-y-auto lg:pr-1">
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
