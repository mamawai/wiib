import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowDownRight, ArrowUpRight, Bot, ChevronDown, ChevronLeft, ChevronRight, ClipboardList, GraduationCap, Loader2,
  NotebookPen, RefreshCcw, X, type LucideIcon,
} from 'lucide-react';
import { traderApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { STATUS_META } from './Arena';
import { EquityChart } from '../components/EquityChart';
import { Markdown } from '../components/Markdown';
import { DecisionCard } from '../components/arena/DecisionCard';
import { PlanBlock } from '../components/arena/PlanBlock';
import { PositionsTable } from '../components/arena/PositionsTable';
import { ScoreStrip } from '../components/arena/ScoreStrip';
import { TradeCard } from '../components/arena/TradeCard';
import { cn, fmtDate, fmtDateTime, fmtNum, fmtTokens } from '../lib/utils';
import type { AiTraderDecisionView, TradeDecisionRef, TradeRecordView, TraderDetailView, TraderEquityPoint } from '../types';
import type { TnEquityPoint } from '../types/testnet';

const REFRESH_MS = 60_000;
const DAY_MS = 86_400_000;
const PAGE = 50;
/** 净值曲线可选区间（天）；0=整局 */
const RANGES = [3, 7, 14, 30, 0] as const;
type Range = typeof RANGES[number];
type Tab = 'timeline' | 'trades';

/** 新加坡时区 yyyy-MM-dd 那一天的 [起, 止) 毫秒——时间线按天查询与 fmtDate/fmtDateTime 同一时区 */
function dayBounds(day: string): { from: number; to: number } {
  const from = Date.parse(`${day}T00:00:00+08:00`);
  return { from, to: from + DAY_MS };
}

/** 局次 / 区间切换共用的小段选钮 */
function SegButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" onClick={onClick}
            className={cn('px-1.5 h-6 rounded border text-[10px] font-bold num',
              active ? 'border-primary/60 bg-card-2 text-primary' : 'border-border text-muted-foreground hover:text-foreground')}>
      {children}
    </button>
  );
}

const ICON_BTN = 'w-6 h-6 rounded border border-border flex items-center justify-center text-muted-foreground hover:text-primary disabled:opacity-40 disabled:hover:text-muted-foreground';

/**
 * 最大回撤%：净值从峰值回落的最大幅度，口径同后端 ReviewMaterialAssembler——
 * 峰值只涨不跌，每个点跟当前峰值比，取最深的那次。整局算，不跟区间按钮走：
 * 跟着区间变会被读成"近3天回撤"，那是另一回事。
 */
function maxDrawdownPct(points: TraderEquityPoint[]): number | null {
  if (points.length < 2) return null;
  let peak = points[0].equity;
  let maxDd = 0;
  for (const p of points) {
    if (p.equity > peak) peak = p.equity;
    else if (peak > 0) maxDd = Math.max(maxDd, (peak - p.equity) / peak * 100);
  }
  return maxDd;
}

/** 主栏卡的 tab 头 */
function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" onClick={onClick}
            className={cn('px-2.5 pt-1.5 pb-2 -mb-px text-xs font-extrabold border-b-2 inline-flex items-center gap-1.5',
              active ? 'text-foreground border-primary' : 'text-muted-foreground border-transparent hover:text-foreground')}>
      {children}
    </button>
  );
}

/**
 * 笔记卡（记忆/学习）：默认一行——标题 + 首句预览 + 最近时间，点开才铺 markdown；两份笔记是参考资料，不跟实时数据抢版面。
 * PC 上展开封顶 50vh、正文卡内滚；手机不限高跟页面滚
 */
function NotesCard({ icon: Icon, tone, title, time, content, empty, className }: {
  icon: LucideIcon; tone: string; title: string; time: number | null | undefined; content: string | null | undefined;
  empty: string; className?: string;
}) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  const text = content?.trim() || '';
  // 预览是纯文本，去掉 markdown 符号免得一行井号
  const preview = text.replace(/[#*`>_-]/g, '').replace(/\s+/g, ' ').slice(0, 120);
  return (
    <div className={cn('rounded-lg pt-card flex flex-col', open && 'lg:max-h-[50vh]', className)}>
      <button type="button" onClick={() => text && setOpen(o => !o)} disabled={!text}
              className="w-full flex items-center gap-2 px-4 py-3 text-left disabled:cursor-default shrink-0">
        <Icon className={cn('w-3 h-3 shrink-0', tone)} />
        <span className="microlabel shrink-0">{title}</span>
        {text
          ? <span className="flex-1 min-w-0 truncate text-[11px] text-muted-foreground">{preview}</span>
          : <span className="flex-1 min-w-0 truncate text-[11px] text-muted-foreground/70">{empty}</span>}
        {time != null && text && (
          <span className="text-[10px] num text-muted-foreground/70 whitespace-nowrap">{t('detail.lastAt', { time: fmtDateTime(time) })}</span>
        )}
        {text && <ChevronDown className={cn('w-3.5 h-3.5 shrink-0 text-muted-foreground transition-transform', open && 'rotate-180')} />}
      </button>
      {open && (
        <div className="px-4 pb-4 text-xs leading-relaxed text-foreground/90 lg:flex-1 lg:min-h-0 lg:overflow-y-auto">
          <Markdown content={text} />
        </div>
      )}
    </div>
  );
}

/**
 * trader 详情：记分牌 → 六格仪表条 → 贯通两栏【主栏 净值曲线 + 决策时间线/已了结 ‖ 侧栏 实时持仓挂单 + 生效计划 + 记忆/学习笔记】。
 * PC（lg+）每张卡各有高度上限，内容多了卡内滚，卡的位置不随内容跑；手机不限高整页滚、卡序另排（见布局处注释）。
 */
export function ArenaDetail() {
  const { t } = useTranslation(['ai', 'common']);
  const { toast } = useToast();
  const { id } = useParams();
  const traderId = Number(id);
  const [detail, setDetail] = useState<TraderDetailView | null>(null);
  const [curve, setCurve] = useState<TraderEquityPoint[]>([]);
  const [decisions, setDecisions] = useState<AiTraderDecisionView[]>([]);
  // 时间线当前筛选范围内的 token 合计；null=没数据，或整段上游都没回 usage
  const [tokens, setTokens] = useState<number | null>(null);
  const [trades, setTrades] = useState<TradeRecordView[]>([]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  // null=跟随当前局（会随 detail 刷新自动跟上）；数字=用户选了某一历史局
  const [round, setRound] = useState<number | null>(null);
  const [range, setRange] = useState<Range>(3);
  // 时间线按天：null=不限日期；yyyy-MM-dd 是新加坡时区那一天
  const [day, setDay] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('timeline');
  // 从已了结交易跳过来要找的那一条决策：描边 + 滚到它
  const [focusId, setFocusId] = useState<number | null>(null);
  const listRef = useRef<HTMLDivElement>(null);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    void traderApi.detail(traderId).then(setDetail).catch(() => setDetail(null));
    void traderApi.equityCurve(traderId, round ?? undefined).then(setCurve).catch(() => setCurve([]));
    void traderApi.trades(traderId).then(setTrades).catch(() => setTrades([]));
  }, [traderId, round]);

  // 忽略开关（仅主人可见）：成功后本地改写该行，不整页重拉；失败要出声——静默吞掉用户会以为已忽略
  const toggleStale = useCallback((r: TradeRecordView) => {
    if (!r.plan) return;
    const next = r.plan.stale !== true;
    void traderApi.setPlanStale(r.plan.id, next).then(() =>
      setTrades(prev => prev.map(x => x.positionId === r.positionId && x.plan
        ? { ...x, plan: { ...x.plan, stale: next } } : x))
    ).catch((e: Error) => toast(e.message || t('toast.actionFailed'), 'error'));
  }, [toast, t]);

  // 时间线单独拉：按天翻看只动它，持仓/曲线/已了结不跟着重拉
  const loadDecisions = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    const bounds = day ? dayBounds(day) : null;
    void traderApi.decisions(traderId, PAGE, undefined, round ?? undefined, bounds?.from, bounds?.to).then(list => {
      setDecisions(list);
      setHasMore(list.length >= PAGE);
    }).catch(() => setDecisions([]));
    // token 合计跟时间线同一套筛选，但列表是分页的、求和不能靠前端，另发一个并行请求让库去 SUM
    void traderApi.tokenUsage(traderId, round ?? undefined, bounds?.from, bounds?.to)
      .then(setTokens).catch(() => setTokens(null));
  }, [traderId, round, day]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);
  useEffect(() => {
    loadDecisions();
    const timer = setInterval(loadDecisions, REFRESH_MS);
    return () => clearInterval(timer);
  }, [loadDecisions]);

  const loadMore = useCallback(() => {
    const oldest = decisions[decisions.length - 1];
    if (!oldest) return;
    const bounds = day ? dayBounds(day) : null;
    setLoadingMore(true);
    traderApi.decisions(traderId, PAGE, oldest.wakeTime, round ?? undefined, bounds?.from, bounds?.to)
      .then(list => {
        setDecisions(prev => [...prev, ...list]);
        setHasMore(list.length >= PAGE);
      })
      .finally(() => setLoadingMore(false));
  }, [traderId, decisions, round, day]);

  // 局与局的日期不重叠，切局时日期筛选一并清掉
  const pickRound = (r: number | null) => { setRound(r); setDay(null); setFocusId(null); };
  const changeDay = (d: string | null) => { setDay(d); setFocusId(null); };
  const today = fmtDate();
  // 没选日期时从今天起步
  const shiftDay = (delta: number) => changeDay(fmtDate(dayBounds(day ?? today).from + delta * DAY_MS));

  // 已了结交易 → 它的开/平仓那一轮：切回时间线、筛到那一天、当前局（已了结只有当前局的）
  const jumpToDecision = (d: TradeDecisionRef) => {
    setTab('timeline');
    setRound(null);
    setDay(fmtDate(d.wakeTime));
    setFocusId(d.id);
    listRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  // 那一天的列表到位后再滚到目标那条
  useEffect(() => {
    if (focusId == null) return;
    document.getElementById(`decision-${focusId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [focusId, decisions]);

  // 区间锚在曲线最后一个点而不是"现在"：看历史局时 3 天＝那局的最后 3 天
  const windowed = useMemo(() => {
    if (range === 0 || curve.length === 0) return curve;
    const from = curve[curve.length - 1].wakeTime - range * DAY_MS;
    return curve.filter(p => p.wakeTime >= from);
  }, [curve, range]);
  // 区间内点不够画线（末点之前是长停工）就退到整局，按钮高亮跟着退
  const effectiveRange: Range = windowed.length > 1 ? range : 0;
  const visible = effectiveRange === 0 ? curve : windowed;
  // 净值曲线复用 EquityChart（累计盈亏口径）：equity-10000 起点归零；区间只裁 x 轴，y 轴仍是整局口径
  const chartPoints = useMemo<TnEquityPoint[]>(
    () => visible.map(p => ({ time: p.wakeTime, cumPnl: p.equity - 10000 })),
    [visible]);
  // 区间内变化：窗口首尾权益之差，百分比按窗口起点权益
  const windowDelta = visible.length > 1 ? visible[visible.length - 1].equity - visible[0].equity : null;
  const windowPct = windowDelta != null && visible[0].equity ? windowDelta / visible[0].equity * 100 : null;

  // tr=这只 trader（不叫 t，那是词表查询函数）
  const tr = detail?.trader;
  const st = tr ? (STATUS_META[tr.status] ?? STATUS_META.PAUSED) : null;
  // round=null 表示跟随当前局，落到显示时统一成具体数字
  const viewingRound = round ?? tr?.roundNo ?? 1;
  const viewingHistory = tr != null && viewingRound !== tr.roundNo;
  // 只列保留窗口内的局——后端只留最近 10 局（TraderService.MAX_ROUNDS_KEPT），更早的已整局清除
  const rounds = tr
    ? Array.from({ length: Math.min(tr.roundNo, 10) }, (_, i) => Math.max(1, tr.roundNo - 9) + i)
    : [];

  // 记分牌：第几天＝曲线首点到末点（末点就是最近一次唤醒）；笔数/胜率从已了结交易现算，只有当前局的
  const dayNo = curve.length > 0
    ? Math.floor((curve[curve.length - 1].wakeTime - curve[0].wakeTime) / DAY_MS) + 1
    : null;
  const closed = trades.filter(r => r.closedPnl != null);
  const winRate = closed.length > 0 ? Math.round(closed.filter(r => (r.closedPnl as number) > 0).length / closed.length * 100) : null;
  const maxDd = maxDrawdownPct(curve);
  // token 那格的微标签跟着时间线筛选走：翻到某天就是那天，没翻就是当前看的这一局
  const tokenScope = day ? day.slice(5) : viewingHistory ? `R${viewingRound}` : t('detail.thisRound');

  return (
    <div className="page-shell page-shell-wide p-4 md:p-6 space-y-4">
      {/* 记分牌一行：身份在左，收益率·权益·四格·局次·刷新在右，窄屏右组整体换到下一行 */}
      <div className="rounded-lg pt-card px-3 py-2 md:px-4 flex items-center gap-x-3 gap-y-2 flex-wrap">
        <Link to="/arena" className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label={t('term.backToArena')}>
          <ChevronLeft className="w-4 h-4" />
        </Link>
        <Bot className="w-5 h-5 text-primary" />
        <h1 className="text-base font-black">{tr?.name ?? '…'}</h1>
        {tr && st && (
          <>
            <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', st.tone)}>{t(st.labelKey)}</span>
            <span className="text-[11px] text-muted-foreground break-all">{tr.model ?? t('term.noModel')} · {tr.intervalCode}{tr.wakeWindow && ` · ${tr.wakeWindow}`}</span>
          </>
        )}
        <div className="ml-auto flex items-center gap-x-4 gap-y-2 flex-wrap">
          {tr && (
            <>
              <div className={cn('num text-2xl font-black leading-none tracking-tight', tr.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
                {tr.pnlPct >= 0 ? '+' : ''}{tr.pnlPct.toFixed(2)}%
              </div>
              <div className="flex flex-col">
                <span className="microlabel">{t('detail.equityLabel')}</span>
                <b className="num text-sm font-extrabold leading-tight">{fmtNum(tr.equity)}</b>
              </div>
            </>
          )}
          {/* 局次：每局是独立子账户各自注资 10000，曲线与时间线同进同出；只有一局时不出现 */}
          {rounds.length > 1 && (
            <div className="flex items-center gap-1.5">
              <span className="text-[11px] text-muted-foreground">{t('detail.round')}</span>
              {rounds.map(r => (
                <SegButton key={r} active={r === viewingRound} onClick={() => pickRound(r === tr?.roundNo ? null : r)}>R{r}</SegButton>
              ))}
            </div>
          )}
          <button
            onClick={() => { load(); loadDecisions(); }}
            className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
            aria-label={t('common:refresh')}
          >
            <RefreshCcw className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* 仪表条：六格平铺。原来这些挤在记分牌右侧，局次按钮最多十个，一多就换行挤成一团 */}
      <ScoreStrip cells={[
        { label: viewingHistory ? `R${viewingRound}` : t('detail.thisRound'),
          value: dayNo != null ? t('detail.dayN', { n: dayNo }) : '—' },
        { label: t('detail.closedCount'), value: t('detail.tradesN', { count: closed.length }) },
        { label: t('detail.winRate'), value: winRate != null ? `${winRate}%` : '—' },
        { label: t('detail.maxDd'),
          tone: maxDd ? 'text-loss' : undefined,
          value: maxDd == null ? '—' : maxDd > 0 ? `-${maxDd.toFixed(2)}%` : '0.00%' },
        { label: effectiveRange === 0 ? t('detail.rangeAll') : t('detail.rangeDays', { n: effectiveRange }),
          tone: windowDelta != null ? (windowDelta >= 0 ? 'text-gain' : 'text-loss') : undefined,
          value: windowDelta != null && windowPct != null
            ? `${windowDelta >= 0 ? '+' : ''}${fmtNum(windowDelta)} · ${windowDelta >= 0 ? '+' : ''}${windowPct.toFixed(2)}%`
            : '—' },
        { label: t('detail.tokensOf', { scope: tokenScope }),
          value: tokens != null ? fmtTokens(tokens) : '—' },
      ]} />

      {tr?.pausedReason && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-1.5 text-[11px] text-amber-600 font-bold">
          {tr.pausedReason}
        </div>
      )}
      {/* 持仓/计划/已了结是实时现查当前账户的，看历史局时跟曲线不是同一局，一条横幅说清楚 */}
      {viewingHistory && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-1.5 text-[11px] text-amber-600 font-bold">
          {t('detail.historyRoundBanner', { viewing: viewingRound, cur: tr.roundNo })}
        </div>
      )}

      {/* 贯通两栏：左主栏 曲线 + 时间线，右侧栏 持仓 + 计划 + 两份笔记。
          窄屏两个栏 div 退成 contents，五张卡直接落进外层单列 grid，再靠 order 排成
          曲线 → 持仓 → 计划 → 笔记 → 时间线：时间线能一直往下加载，压在最后才不会把别的挤没 */}
      <div className="grid lg:grid-cols-5 gap-4 items-start">
        <div className="contents lg:flex lg:col-span-3 lg:flex-col lg:gap-4">
          <div className="order-1 lg:order-none rounded-lg pt-card p-4 flex flex-col gap-2">
            <div className="flex items-center gap-2 flex-wrap shrink-0">
              <span className="microlabel">
                {t('detail.equityCurve', { round: viewingHistory ? `R${viewingRound}` : t('detail.thisRound') })}
              </span>
              <div className="ml-auto flex gap-1">
                {RANGES.map(r => (
                  <SegButton key={r} active={r === effectiveRange} onClick={() => setRange(r)}>
                    {r === 0 ? t('detail.rangeAll') : t('detail.rangeDays', { n: r })}
                  </SegButton>
                ))}
              </div>
            </div>
            {chartPoints.length > 1
              ? <EquityChart points={chartPoints} className="flex-1 min-h-[260px]" />
              : <div className="flex-1 min-h-[260px] flex items-center justify-center text-xs text-muted-foreground">{t('detail.notEnoughPoints')}</div>}
          </div>

          {/* 时间线封顶 75vh，列表卡内滚 */}
          <div ref={listRef} className="order-6 lg:order-none rounded-lg pt-card p-4 flex flex-col gap-2.5 lg:max-h-[75vh] scroll-mt-16">
            <div className="flex items-center gap-1 flex-wrap border-b border-border -mx-4 px-3 -mt-1 shrink-0">
              <TabButton active={tab === 'timeline'} onClick={() => setTab('timeline')}>
                {t('detail.timeline')}{viewingHistory && ` · R${viewingRound}`}
              </TabButton>
              <TabButton active={tab === 'trades'} onClick={() => setTab('trades')}>
                {t('detail.tradesTitle')}
                <span className="num text-[10px] px-1 rounded bg-muted text-muted-foreground">{trades.length}</span>
              </TabButton>
              {/* 按天翻看：前后一天箭头 + 日期框；清掉回到不限日期。只属于时间线 */}
              {tab === 'timeline' && (
                <div className="ml-auto flex items-center gap-1 pb-1.5">
                  <button type="button" className={ICON_BTN} aria-label={t('detail.prevDay')} onClick={() => shiftDay(-1)}>
                    <ChevronLeft className="w-3.5 h-3.5" />
                  </button>
                  <input type="date" value={day ?? ''} max={today}
                         onChange={e => changeDay(e.target.value || null)}
                         className="h-6 px-1.5 rounded border border-border bg-input text-[10px] num" />
                  <button type="button" className={ICON_BTN} aria-label={t('detail.nextDay')}
                          disabled={!day || day >= today} onClick={() => shiftDay(1)}>
                    <ChevronRight className="w-3.5 h-3.5" />
                  </button>
                  {day && (
                    <button type="button" className={ICON_BTN} aria-label={t('detail.allDays')} onClick={() => changeDay(null)}>
                      <X className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>
              )}
            </div>

            {tab === 'timeline' ? (
              decisions.length === 0 ? (
                <div className="flex-1 flex items-center justify-center py-10 text-xs text-muted-foreground">{day ? t('detail.noDecisionsDay') : t('detail.noDecisions')}</div>
              ) : (
                <div className="space-y-2.5 lg:flex-1 lg:min-h-0 lg:overflow-y-auto lg:pr-1">
                  {decisions.map(d => <DecisionCard key={d.id} d={d} highlight={d.id === focusId} />)}
                  {hasMore && (
                    <button
                      onClick={loadMore}
                      disabled={loadingMore}
                      className="w-full border border-border hover:bg-surface-hover rounded-lg py-2 text-xs font-bold text-muted-foreground hover:text-primary flex items-center justify-center gap-1.5"
                    >
                      {loadingMore && <Loader2 className="w-3.5 h-3.5 animate-spin" />} {t('detail.loadMore')}
                    </button>
                  )}
                </div>
              )
            ) : (
              trades.length === 0 ? (
                <div className="flex-1 flex items-center justify-center py-10 text-xs text-muted-foreground">{t('detail.noTrades')}</div>
              ) : (
                <div className="space-y-2 lg:flex-1 lg:min-h-0 lg:overflow-y-auto lg:pr-1">
                  {trades.map(r => <TradeCard key={r.positionId} r={r} onJump={jumpToDecision}
                                              onToggleStale={detail?.trader.mine ? toggleStale : undefined} />)}
                </div>
              )
            )}
          </div>
        </div>

        <div className="contents lg:flex lg:col-span-2 lg:flex-col lg:gap-4">
          {/* 持仓封顶 26rem，表格卡内滚 */}
          <div className="order-2 lg:order-none rounded-lg pt-card p-4 flex flex-col gap-2 lg:max-h-[26rem]">
            <span className="microlabel shrink-0">{t('detail.positionsTitle')}</span>
            {detail && (detail.positions.length === 0 && detail.pendingOrders.length === 0 ? (
              <div className="flex-1 flex items-center justify-center py-6 text-xs text-muted-foreground">{t('detail.flat')}</div>
            ) : (
              <div className="lg:flex-1 lg:min-h-0 lg:overflow-y-auto lg:pr-1">
                <PositionsTable positions={detail.positions} orders={detail.pendingOrders} />
              </div>
            ))}
          </div>

          {/* 计划是本局存活的，归档的配在已了结卡里；两份笔记跨局累积不随局次切换 */}
          <div className="order-3 lg:order-none rounded-lg pt-card p-4 flex flex-col gap-2 lg:max-h-[45vh]">
            <span className="microlabel inline-flex items-center gap-1 shrink-0"><ClipboardList className="w-3 h-3 text-primary" />{t('detail.plansTitle')}</span>
            {detail && (detail.plans.length === 0 ? (
              <div className="flex-1 flex items-center justify-center py-6 text-xs text-muted-foreground">{t('detail.noPlans')}</div>
            ) : (
              <div className="space-y-2 lg:flex-1 lg:min-h-0 lg:overflow-y-auto lg:pr-1">
                {detail.plans.map(pl => {
                  const isLong = pl.side === 'LONG';
                  return (
                    <div key={pl.id} className="text-[11px]">
                      <div className="flex items-center gap-2">
                        {isLong ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />}
                        <span className="font-black text-xs">{pl.symbol}</span>
                        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
                          {isLong ? t('term.long') : t('term.short')}
                        </span>
                      </div>
                      <PlanBlock plan={pl} />
                    </div>
                  );
                })}
              </div>
            ))}
          </div>
          <NotesCard className="order-4 lg:order-none" icon={NotebookPen} tone="text-violet-500" title={t('detail.memoryTitle')}
                     time={detail?.lastReviewAt} content={detail?.memory} empty={t('detail.noMemory')} />
          <NotesCard className="order-5 lg:order-none" icon={GraduationCap} tone="text-sky-500" title={t('detail.learnNotesTitle')}
                     time={detail?.lastLearnAt} content={detail?.learningNotes} empty={t('detail.noLearnNotes')} />
        </div>
      </div>
    </div>
  );
}
