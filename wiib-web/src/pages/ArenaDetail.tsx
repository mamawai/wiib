import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowDownRight, ArrowUpRight, Bot, ChevronDown, ChevronLeft, ChevronUp, ClipboardList, GraduationCap, Loader2,
  MousePointerClick, NotebookPen, RefreshCcw, Zap,
} from 'lucide-react';
import { traderApi } from '../api';
import { STATUS_META } from './Arena';
import { EquityChart } from '../components/EquityChart';
import { Markdown } from '../components/Markdown';
import i18n from '../i18n';
import { cn, fmtDateTime, fmtDuration, fmtNum, fmtTokens } from '../lib/utils';
import type {
  AiTraderDecisionView, AiTraderPlanView, PlanRevision, TradeDecisionRef, TradeRecordView, TraderDetailView, TraderEquityPoint,
} from '../types';
import type { TnEquityPoint } from '../types/testnet';

const REFRESH_MS = 60_000;

/** 徽章存的是词表 key：模块级常量只算一次，存翻好的字面量切了语言也不会变（下面几张表同理） */
const DECISION_STATUS: Record<string, { labelKey: string; tone: string }> = {
  OK: { labelKey: 'decision.ok', tone: 'bg-primary/15 text-primary' },
  ERROR: { labelKey: 'decision.error', tone: 'bg-loss/15 text-loss' },
  SKIPPED: { labelKey: 'decision.skipped', tone: 'bg-muted text-muted-foreground' },
};

/** 工具名人话化：交易动作独立成行展示参数，数据查询弱化成 chip——一眼分清"看了什么"和"做了什么" */
const TOOL_KEY: Record<string, string> = {
  open_position: 'tool.openPosition', close_position: 'tool.closePosition', set_stop_loss: 'tool.setStopLoss',
  set_take_profit: 'tool.setTakeProfit', cancel_order: 'tool.cancelOrder', write_plan: 'tool.writePlan',
  get_account: 'tool.getAccount', klines: 'tool.klines', indicators: 'tool.indicators',
  market_snapshot: 'tool.marketSnapshot', funding_history: 'tool.fundingHistory',
  orderbook_depth: 'tool.orderbookDepth', option_iv: 'tool.optionIv', news_search: 'tool.newsSearch',
};

/** 工具的展示名；表里没有的（后端加了新工具）原样显示 id */
function toolName(tool: string): string {
  const key = TOOL_KEY[tool];
  return key ? i18n.t(`ai:${key}`) : tool;
}
const TRADE_TOOLS = new Set(['open_position', 'close_position', 'set_stop_loss', 'set_take_profit', 'cancel_order', 'write_plan']);

interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
}

/** 交易动作的关键参数一行话（按工具挑重点，不倒整个 JSON）。词表在函数体里现查，切语言即变 */
function tradeArgsSummary(a: ActionRow): string {
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  const tr = (key: string, vars?: Record<string, string>) => i18n.t(`ai:${key}`, vars ?? {});
  switch (a.tool) {
    case 'open_position': {
      const parts = [g('symbol'), g('side') === 'LONG' ? tr('args.long') : g('side') === 'SHORT' ? tr('args.short') : g('side'),
        g('quantity') && tr('args.qty', { n: g('quantity') }), g('leverage') && `${g('leverage')}x`,
        g('stopLossPrice') && tr('args.sl', { p: g('stopLossPrice') }),
        g('takeProfitPrice') && tr('args.tp', { p: g('takeProfitPrice') }),
        g('playType')];
      return parts.filter(Boolean).join(' · ');
    }
    case 'close_position':
      return [tr('args.position', { id: g('positionId') }), g('quantity') && tr('args.qty', { n: g('quantity') }), g('reason')]
        .filter(Boolean).join(' · ');
    case 'set_stop_loss':
      return [`→${g('stopLossPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'set_take_profit':
      return [`→${g('takeProfitPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'write_plan':
      return [g('playType'), g('invalidationCondition') && tr('args.invalidation', { c: g('invalidationCondition') })]
        .filter(Boolean).join(' · ');
    case 'cancel_order':
      return tr('args.order', { id: g('orderId') });
    default:
      return '';
  }
}

/** 决策全文折叠：默认一行纯文本预览，展开成 markdown——时间线卡与交易记录卡同一套阅读节奏 */
function ReasoningFold({ reasoning }: { reasoning: string | null }) {
  const { t } = useTranslation(['ai', 'common']);
  const [open, setOpen] = useState(false);
  const text = reasoning?.trim() || '';
  if (!text) return null;
  // 折叠预览是纯文本，去掉 markdown 符号免得满屏井号
  const preview = text.replace(/[#*`]/g, '').replace(/\s+/g, ' ').slice(0, 120) + (text.length > 120 ? '…' : '');
  return (
    <div className="text-xs leading-relaxed text-foreground/90">
      {open ? <Markdown content={text} /> : <p>{preview}</p>}
      {text.length > 120 && (
        <button onClick={() => setOpen(!open)} className="mt-1 text-[10px] font-bold text-primary flex items-center gap-0.5">
          {open ? <>{t('common:collapse')} <ChevronUp className="w-3 h-3" /></> : <>{t('detail.expandFull')} <ChevronDown className="w-3 h-3" /></>}
        </button>
      )}
    </div>
  );
}

/** 单条决策卡：时间/权益 + 数据工具chip + 交易动作行（参数/拒因） + 推理 markdown 折叠——竞技场的观赏核心。 */
function DecisionCard({ d }: { d: AiTraderDecisionView }) {
  const { t } = useTranslation('ai');
  // 复盘行不是交易决策，徽章与配色单独一套：reviewer 的每日日志，时间线上要一眼认出
  // 学习行同样不交易，但来源不同（复盘看自己、学习看同侪），再分一套色——两种日志行混在时间线上要能一眼分清
  const meta = d.kind === 'REVIEW'
    ? (d.status === 'OK'
        ? { labelKey: 'term.dailyReview', tone: 'bg-violet-500/15 text-violet-500' }
        : { labelKey: 'decision.reviewFailed', tone: 'bg-loss/15 text-loss' })
    : d.kind === 'LEARN'
      ? (d.status === 'OK'
          ? { labelKey: 'term.peerLearn', tone: 'bg-sky-500/15 text-sky-500' }
          : { labelKey: 'decision.learnFailed', tone: 'bg-loss/15 text-loss' })
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

  return (
    <div className={cn('rounded-md border bg-card p-3 space-y-2',
      d.kind === 'REVIEW' ? 'border-violet-500/35 bg-violet-500/[0.04]'
        : d.kind === 'LEARN' ? 'border-sky-500/35 bg-sky-500/[0.04]' : 'border-border')}>
      <div className="flex items-center gap-2 flex-wrap">
        <span className={cn('inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded', meta.tone)}>
          {d.kind === 'REVIEW' && <NotebookPen className="w-3 h-3" />}
          {d.kind === 'LEARN' && <GraduationCap className="w-3 h-3" />}{t(meta.labelKey)}
        </span>
        {/* 警报唤醒凸显：这条不是例行K线节奏，是哨兵在极端波动时叫醒的 */}
        {d.kind === 'ALERT' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">
            <Zap className="w-3 h-3" />{t('decision.alert')}
          </span>
        )}
        {/* 手动唤醒凸显：扳机在主人手里（对话轨 wake_trader），不是例行K线节奏 */}
        {d.kind === 'MANUAL' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-teal-500/15 text-teal-600">
            <MousePointerClick className="w-3 h-3" />{t('term.manualWake')}
          </span>
        )}
        <span className="text-[11px] num text-muted-foreground">{fmtDateTime(d.wakeTime)}</span>
        {d.equity != null && (
          <span className="text-[11px] text-muted-foreground">{t('term.equity')} <span className="num font-bold text-foreground">{fmtNum(d.equity)}</span></span>
        )}
        <span className="ml-auto text-[10px] text-muted-foreground/70 num">
          {[
            d.latencyMs != null && `${(d.latencyMs / 1000).toFixed(1)}s`,
            // 复盘无工具（单次调用）不显示"0次工具"占位；学习是 ReactAgent 有 peer_insights 工具，照常显示
            d.kind !== 'REVIEW' && t('detail.toolCalls', { count: d.toolCalls }),
            d.modelCalls != null && t('detail.modelCalls', { count: d.modelCalls }),
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
              {toolName(a.tool)}{a.status === 'error' && ' ✕'}
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
                  {toolName(a.tool)}
                  {a.rejected ? t('trade.rejected') : a.status === 'error' ? t('trade.errored') : unknown ? t('trade.unknown') : ''}
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

      <ReasoningFold reasoning={d.reasoning} />
    </div>
  );
}

/** 持仓的交易计划卡：论点/失效条件/原始快照 + 修订历史——退出纪律的公开凭证。 */
function PlanBlock({ plan }: { plan: AiTraderPlanView }) {
  const { t } = useTranslation('ai');
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
        <span className="font-black text-primary">{t('plan.title')}</span>
        {plan.playType && <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-primary/15 text-primary">{plan.playType}</span>}
        <span className="text-muted-foreground/80 num ml-auto">{t('plan.setAt', { time: fmtDateTime(plan.openedWakeTime) })}</span>
      </div>
      {plan.signalsUsed && <p className="text-muted-foreground">{t('plan.basis', { s: plan.signalsUsed })}</p>}
      <p><span className="font-bold text-foreground">{t('plan.invalidation')}</span>{plan.invalidationCondition}</p>
      {/* 间距走 gap 不用空格字符：末项缺席时不会拖着个尾巴，窄屏也能换行 */}
      <p className="text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
        {plan.entryPrice != null && <span>{t('plan.entry')} {fmtNum(plan.entryPrice)}</span>}
        {plan.stopLossPrice != null && <span>{t('plan.origSl')} {fmtNum(plan.stopLossPrice)}</span>}
        {plan.takeProfitPrice != null && <span>{t('plan.target')} {fmtNum(plan.takeProfitPrice)}</span>}
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

/** 了结方式徽章色：止盈/止损是计划兑现，主动平仓是模型的手，强平是事故 */
const CLOSE_MANNER_TONE: Record<string, string> = {
  '止盈带走': 'bg-gain/15 text-gain', '止损带走': 'bg-loss/15 text-loss',
  '主动平仓': 'bg-primary/15 text-primary', '强平': 'bg-loss/25 text-loss',
};

/** 单笔已了结交易：头行（币种·多空·了结方式·入场→出场·盈亏）→ 计划（论点/失效条件/修订史）→ 开仓/平仓决策折叠 */
function TradeCard({ r }: { r: TradeRecordView }) {
  const { t } = useTranslation('ai');
  const isLong = r.side === 'LONG';
  const pnl = r.closedPnl;
  return (
    <div className="rounded-md border border-border bg-card p-2.5 text-[11px] space-y-1.5">
      <div className="flex items-center gap-2 flex-wrap">
        {isLong ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />}
        <span className="font-black text-xs">{r.symbol}</span>
        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
          {isLong ? t('term.long') : t('term.short')}{r.leverage != null && ` ${r.leverage}x`}
        </span>
        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', CLOSE_MANNER_TONE[r.closeManner] ?? 'bg-muted text-muted-foreground')}>
          {r.closeManner}
        </span>
        <span className="text-muted-foreground num">{fmtNum(r.entryPrice)} → {r.closedPrice != null ? fmtNum(r.closedPrice) : '—'}</span>
        <span className={cn('ml-auto num font-black', pnl == null ? 'text-muted-foreground' : pnl >= 0 ? 'text-gain' : 'text-loss')}>
          {pnl == null ? '—' : `${pnl >= 0 ? '+' : ''}${fmtNum(pnl)}`}
        </span>
      </div>
      <div className="text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
        <span>{t('trade.openedAt', { time: fmtDateTime(r.openedAt) })}</span>
        <span>{t('trade.closedAt', { time: fmtDateTime(r.closedAt) })}</span>
        <span>{t('trade.held', { d: fmtDuration(r.openedAt, r.closedAt) })}</span>
      </div>
      {r.plan ? <PlanBlock plan={r.plan} /> : <p className="text-muted-foreground/70">{t('trade.noPlan')}</p>}
      {r.openDecision && <DecisionRefBlock label={t('trade.openDecision')} d={r.openDecision} />}
      {r.closeDecision && <DecisionRefBlock label={t('trade.closeDecision')} d={r.closeDecision} />}
    </div>
  );
}

/** 交易记录挂的那一轮决策：标签 + 时刻 + 一句话理由（平仓才有）+ 全文折叠 */
function DecisionRefBlock({ label, d }: { label: string; d: TradeDecisionRef }) {
  const { t } = useTranslation('ai');
  return (
    <div className="rounded border border-border/60 bg-card-2/40 px-2.5 py-2 space-y-1 leading-relaxed">
      <div className="flex items-center gap-1.5 flex-wrap">
        <span className="font-black">{label}</span>
        <span className="text-muted-foreground/80 num">{fmtDateTime(d.wakeTime)}</span>
        {d.kind === 'ALERT' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">
            <Zap className="w-3 h-3" />{t('decision.alertRound')}
          </span>
        )}
        {d.kind === 'MANUAL' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-teal-500/15 text-teal-600">
            <MousePointerClick className="w-3 h-3" />{t('decision.manualRound')}
          </span>
        )}
        {d.reason && <span className="text-muted-foreground">—— {d.reason}</span>}
      </div>
      <ReasoningFold reasoning={d.reasoning} />
    </div>
  );
}

/** trader 详情：净值曲线 + 实时持仓挂单 + 已了结交易 + 决策时间线。 */
export function ArenaDetail() {
  const { t } = useTranslation(['ai', 'common']);
  const { id } = useParams();
  const traderId = Number(id);
  const [detail, setDetail] = useState<TraderDetailView | null>(null);
  const [curve, setCurve] = useState<TraderEquityPoint[]>([]);
  const [decisions, setDecisions] = useState<AiTraderDecisionView[]>([]);
  const [trades, setTrades] = useState<TradeRecordView[]>([]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  // null=跟随当前局（会随 detail 刷新自动跟上）；数字=用户选了某一历史局
  const [round, setRound] = useState<number | null>(null);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    void traderApi.detail(traderId).then(setDetail).catch(() => setDetail(null));
    void traderApi.equityCurve(traderId, round ?? undefined).then(setCurve).catch(() => setCurve([]));
    void traderApi.trades(traderId).then(setTrades).catch(() => setTrades([]));
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

  // tr=这只 trader（不叫 t，那是词表查询函数）
  const tr = detail?.trader;
  const st = tr ? (STATUS_META[tr.status] ?? STATUS_META.PAUSED) : null;
  // round=null 表示跟随当前局，落到显示时统一成具体数字
  const viewingRound = round ?? tr?.roundNo ?? 1;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-2.5 flex-wrap">
        <Link to="/arena" className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label={t('term.backToArena')}>
          <ChevronLeft className="w-4 h-4" />
        </Link>
        <Bot className="w-5 h-5 text-primary" />
        <h1 className="text-lg font-black">{tr?.name ?? '…'}</h1>
        {tr && st && (
          <>
            <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', st.tone)}>{t(st.labelKey)}</span>
            <span className="text-[11px] text-muted-foreground break-all">{tr.model ?? t('term.noModel')} · {tr.intervalCode}{tr.wakeWindow && ` · ${tr.wakeWindow}`} · R{tr.roundNo}</span>
            <span className={cn('num font-black', tr.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
              {tr.pnlPct >= 0 ? '+' : ''}{tr.pnlPct.toFixed(2)}%
            </span>
          </>
        )}
        <button
          onClick={load}
          className="ml-auto border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
          aria-label={t('common:refresh')}
        >
          <RefreshCcw className="w-3.5 h-3.5" />
        </button>
      </div>

      {tr?.pausedReason && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-600 font-bold">
          {tr.pausedReason}
        </div>
      )}

      <div className="grid lg:grid-cols-2 gap-4 items-start">
        <div className="space-y-4">
          <div className="rounded-lg pt-card p-4 space-y-2">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="microlabel">
                {t('detail.equityCurve', {
                  round: viewingRound === tr?.roundNo ? t('detail.thisRound') : `R${viewingRound}`,
                })}
              </span>
              {/* 局次切换：每局是独立子账户各自注资 10000，曲线与时间线必须同进同出，不能混排。
                  只列保留窗口内的局——后端只留最近 10 局（TraderService.MAX_ROUNDS_KEPT），更早的已整局清除 */}
              {(tr?.roundNo ?? 1) > 1 && (
                <div className="ml-auto flex gap-1">
                  {Array.from({ length: Math.min(tr?.roundNo ?? 1, 10) },
                    (_, i) => Math.max(1, (tr?.roundNo ?? 1) - 9) + i).map(r => (
                    <button key={r} type="button"
                            onClick={() => setRound(r === tr?.roundNo ? null : r)}
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
              : <div className="py-10 text-center text-xs text-muted-foreground">{t('detail.notEnoughPoints')}</div>}
          </div>

          <div className="rounded-lg pt-card p-4 space-y-2">
            <span className="microlabel">{t('detail.positionsTitle')}</span>
            {/* 持仓是实时现查当前账户的，看历史局时这块跟左边的曲线不是同一局，得说清楚（tr 未到时不闪黄字） */}
            {tr && viewingRound !== tr.roundNo && (
              <p className="text-[10px] text-amber-600">
                {t('detail.positionsRoundWarn', { cur: tr.roundNo, viewing: viewingRound })}
              </p>
            )}
            {detail && detail.positions.length === 0 && detail.pendingOrders.length === 0 && (
              <div className="py-6 text-center text-xs text-muted-foreground">{t('detail.flat')}</div>
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
                      {isLong ? t('term.long') : t('term.short')} {p.leverage}x
                    </span>
                    <span className="text-muted-foreground">{t('detail.qty')} <span className="num font-bold text-foreground">{p.quantity}</span></span>
                    <span className="text-muted-foreground">{t('detail.entry')} <span className="num font-bold text-foreground">{fmtNum(p.entryPrice)}</span></span>
                    <span className="ml-auto">
                      <span className={cn('num font-black', p.unrealizedPnl >= 0 ? 'text-gain' : 'text-loss')}>
                        {p.unrealizedPnl >= 0 ? '+' : ''}{fmtNum(p.unrealizedPnl)}
                      </span>
                    </span>
                  </div>
                  {/* 两个数组都空时 length 求值为 0，裸 && 会把 0 渲染到页面上，得先转 boolean */}
                  {Boolean(p.stopLosses?.length || p.takeProfits?.length) && (
                    <div className="mt-1 text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
                      {p.stopLosses?.length ? <span>{t('detail.curSl')} {p.stopLosses.map(s => fmtNum(s.price)).join(' / ')}</span> : null}
                      {p.takeProfits?.length ? <span>{t('detail.curTp')} {p.takeProfits.map(tp => fmtNum(tp.price)).join(' / ')}</span> : null}
                    </div>
                  )}
                  {plan && <PlanBlock plan={plan} />}
                </div>
              );
            })}
            {detail?.pendingOrders.map(o => {
              // 开/平 与 多/空 拼成一个词：中文能直接接起来，英文中间要空格，所以四种组合各一条词条
              const isLong = o.orderSide.includes('LONG');
              const sideKey = o.orderSide.startsWith('OPEN')
                ? (isLong ? 'detail.openLong' : 'detail.openShort')
                : (isLong ? 'detail.closeLong' : 'detail.closeShort');
              return (
                <div key={o.orderId} className="rounded-md border border-dashed border-border bg-card-2 p-2.5 text-[11px] text-muted-foreground flex items-center gap-2 flex-wrap">
                  <span className="font-bold text-foreground/80">{t('detail.limitOrder')}</span>
                  <span className="font-black text-foreground">{o.symbol}</span>
                  <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded',
                    isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
                    {t(sideKey)} {o.leverage}x
                  </span>
                  <span>{t('detail.qty')} <span className="num font-bold text-foreground">{o.quantity}</span></span>
                  {o.limitPrice != null && <span>{t('detail.limitPrice')} <span className="num font-bold text-foreground">{fmtNum(o.limitPrice)}</span></span>}
                </div>
              );
            })}
          </div>

          <div className="rounded-lg pt-card p-4 space-y-2">
            <span className="microlabel">{t('detail.tradesTitle')}</span>
            {/* 已平仓位来自当前局的 sim 子账户；看历史局时说清楚，与上面持仓卡同一口径 */}
            {tr && viewingRound !== tr.roundNo && (
              <p className="text-[10px] text-amber-600">{t('detail.tradesRoundWarn', { cur: tr.roundNo })}</p>
            )}
            {trades.length === 0 && (
              <div className="py-6 text-center text-xs text-muted-foreground">{t('detail.noTrades')}</div>
            )}
            <div className="space-y-2 lg:max-h-[60vh] lg:overflow-y-auto lg:pr-1">
              {trades.map(r => <TradeCard key={r.positionId} r={r} />)}
            </div>
          </div>
        </div>

        <div className="rounded-lg pt-card p-4 space-y-2.5">
          <span className="microlabel">
            {t('detail.timeline')}{viewingRound !== tr?.roundNo && ` · R${viewingRound}`}
          </span>
          {decisions.length === 0 && (
            <div className="py-10 text-center text-xs text-muted-foreground">{t('detail.noDecisions')}</div>
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
                {loadingMore && <Loader2 className="w-3.5 h-3.5 animate-spin" />} {t('detail.loadMore')}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
