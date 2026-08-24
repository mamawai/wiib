import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowDownRight, ArrowUpRight, Bot, ChevronLeft, ChevronRight, ClipboardList, GraduationCap, Loader2,
  NotebookPen, RefreshCcw, X, type LucideIcon,
} from 'lucide-react';
import { traderApi } from '../api';
import { STATUS_META } from './Arena';
import { EquityChart } from '../components/EquityChart';
import { Markdown } from '../components/Markdown';
import { DecisionCard } from '../components/arena/DecisionCard';
import { PlanBlock } from '../components/arena/PlanBlock';
import { ReasoningFold } from '../components/arena/ReasoningFold';
import { TradeCard } from '../components/arena/TradeCard';
import { cn, fmtDate, fmtDateTime, fmtNum } from '../lib/utils';
import type { AiTraderDecisionView, TradeRecordView, TraderDetailView, TraderEquityPoint } from '../types';
import type { TnEquityPoint } from '../types/testnet';

const REFRESH_MS = 60_000;
const DAY_MS = 86_400_000;
const PAGE = 50;
/** 净值曲线可选区间（天）；0=整局 */
const RANGES = [3, 7, 14, 30, 0] as const;
type Range = typeof RANGES[number];

/** 新加坡时区 yyyy-MM-dd 那一天的 [起, 止) 毫秒——时间线按天查询与 fmtDate/fmtDateTime 同一时区 */
function dayBounds(day: string): { from: number; to: number } {
  const from = Date.parse(`${day}T00:00:00+08:00`);
  return { from, to: from + DAY_MS };
}

/** 局次条 / 区间切换共用的小段选钮 */
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

/** 笔记卡（学习/记忆）：桌面全文内滚；手机折叠成预览+展开——三块笔记堆在单列里全文铺开会把页面拉得没法翻 */
function NotesCard({ icon: Icon, tone, title, time, content, empty }: {
  icon: LucideIcon; tone: string; title: string; time: number | null | undefined; content: string | null | undefined; empty: string;
}) {
  const { t } = useTranslation('ai');
  const text = content?.trim() || '';
  return (
    <div className="rounded-lg pt-card p-4 space-y-2">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="microlabel inline-flex items-center gap-1"><Icon className={cn('w-3 h-3', tone)} />{title}</span>
        {time != null && (
          <span className="ml-auto text-[10px] num text-muted-foreground/70">{t('detail.lastAt', { time: fmtDateTime(time) })}</span>
        )}
      </div>
      {!text ? (
        <div className="py-6 text-center text-xs text-muted-foreground">{empty}</div>
      ) : (
        <>
          <div className="hidden lg:block text-xs leading-relaxed text-foreground/90 lg:max-h-[48vh] overflow-y-auto pr-1">
            <Markdown content={text} />
          </div>
          <div className="lg:hidden"><ReasoningFold reasoning={text} /></div>
        </>
      )}
    </div>
  );
}

/**
 * trader 详情：局次条 → 净值曲线（可切区间）| 实时持仓挂单 → 学习笔记 | 记忆笔记 | 生效中的计划
 * → 已了结交易 | 决策时间线（可按天翻）。
 */
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
  const [range, setRange] = useState<Range>(3);
  // 时间线按天：null=不限日期；yyyy-MM-dd 是新加坡时区那一天
  const [day, setDay] = useState<string | null>(null);

  const load = useCallback(() => {
    if (!Number.isFinite(traderId)) return;
    const bounds = day ? dayBounds(day) : null;
    void traderApi.detail(traderId).then(setDetail).catch(() => setDetail(null));
    void traderApi.equityCurve(traderId, round ?? undefined).then(setCurve).catch(() => setCurve([]));
    void traderApi.trades(traderId).then(setTrades).catch(() => setTrades([]));
    void traderApi.decisions(traderId, PAGE, undefined, round ?? undefined, bounds?.from, bounds?.to).then(list => {
      setDecisions(list);
      setHasMore(list.length >= PAGE);
    }).catch(() => setDecisions([]));
  }, [traderId, round, day]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

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
  const pickRound = (r: number | null) => { setRound(r); setDay(null); };
  const today = fmtDate();
  // 没选日期时从今天起步
  const shiftDay = (delta: number) => setDay(d => fmtDate(dayBounds(d ?? today).from + delta * DAY_MS));

  // 区间锚在曲线最后一个点而不是"现在"：看历史局时 3 天＝那局的最后 3 天
  const visible = useMemo(() => {
    if (range === 0 || curve.length === 0) return curve;
    const from = curve[curve.length - 1].wakeTime - range * DAY_MS;
    return curve.filter(p => p.wakeTime >= from);
  }, [curve, range]);
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
            <span className="text-[11px] text-muted-foreground break-all">{tr.model ?? t('term.noModel')} · {tr.intervalCode}{tr.wakeWindow && ` · ${tr.wakeWindow}`}</span>
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

      {/* 局次条：每局是独立子账户各自注资 10000，曲线与时间线必须同进同出，不能混排 */}
      {rounds.length > 0 && (
        <div className="flex items-center gap-1 flex-wrap">
          {rounds.map(r => (
            <SegButton key={r} active={r === viewingRound} onClick={() => pickRound(r === tr?.roundNo ? null : r)}>R{r}</SegButton>
          ))}
        </div>
      )}

      {tr?.pausedReason && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-600 font-bold">
          {tr.pausedReason}
        </div>
      )}
      {/* 持仓/计划/已了结是实时现查当前账户的，看历史局时跟曲线不是同一局，一条横幅说清楚 */}
      {viewingHistory && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-600 font-bold">
          {t('detail.historyRoundBanner', { viewing: viewingRound, cur: tr.roundNo })}
        </div>
      )}

      <div className="grid lg:grid-cols-5 gap-4 items-start">
        <div className="lg:col-span-3 rounded-lg pt-card p-4 space-y-2">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="microlabel">
              {t('detail.equityCurve', { round: viewingHistory ? `R${viewingRound}` : t('detail.thisRound') })}
            </span>
            {windowDelta != null && windowPct != null && (
              <span className="text-[11px] text-muted-foreground">
                {t('detail.inRange')}{' '}
                <span className={cn('num font-bold', windowDelta >= 0 ? 'text-gain' : 'text-loss')}>
                  {windowDelta >= 0 ? '+' : ''}{fmtNum(windowDelta)} · {windowDelta >= 0 ? '+' : ''}{windowPct.toFixed(2)}%
                </span>
              </span>
            )}
            <div className="ml-auto flex gap-1">
              {RANGES.map(r => (
                <SegButton key={r} active={r === range} onClick={() => setRange(r)}>
                  {r === 0 ? t('detail.rangeAll') : t('detail.rangeDays', { n: r })}
                </SegButton>
              ))}
            </div>
          </div>
          {chartPoints.length > 1
            ? <EquityChart points={chartPoints} height={260} />
            : <div className="py-10 text-center text-xs text-muted-foreground">{t('detail.notEnoughPoints')}</div>}
        </div>

        <div className="lg:col-span-2 rounded-lg pt-card p-4 space-y-2">
          <span className="microlabel">{t('detail.positionsTitle')}</span>
          {detail && detail.positions.length === 0 && detail.pendingOrders.length === 0 && (
            <div className="py-6 text-center text-xs text-muted-foreground">{t('detail.flat')}</div>
          )}
          {detail?.positions.map(p => {
            const isLong = p.side === 'LONG';
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
      </div>

      {/* 两份笔记是 trader 每次唤醒真正读到的东西，跨局累积不随局次切换；计划是本局存活的，归档的配在已了结卡里 */}
      <div className="grid lg:grid-cols-3 gap-4 items-start">
        <NotesCard icon={GraduationCap} tone="text-sky-500" title={t('detail.learnNotesTitle')}
                   time={detail?.lastLearnAt} content={detail?.learningNotes} empty={t('detail.noLearnNotes')} />
        <NotesCard icon={NotebookPen} tone="text-violet-500" title={t('detail.memoryTitle')}
                   time={detail?.lastReviewAt} content={detail?.memory} empty={t('detail.noMemory')} />
        <div className="rounded-lg pt-card p-4 space-y-2">
          <span className="microlabel inline-flex items-center gap-1"><ClipboardList className="w-3 h-3 text-primary" />{t('detail.plansTitle')}</span>
          {detail && detail.plans.length === 0 && (
            <div className="py-6 text-center text-xs text-muted-foreground">{t('detail.noPlans')}</div>
          )}
          {detail?.plans.map(pl => {
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
      </div>

      <div className="grid lg:grid-cols-2 gap-4 items-start">
        <div className="rounded-lg pt-card p-4 space-y-2">
          <span className="microlabel">{t('detail.tradesTitle')}</span>
          {trades.length === 0 && (
            <div className="py-6 text-center text-xs text-muted-foreground">{t('detail.noTrades')}</div>
          )}
          {/* 内层滚动只给桌面双栏用；手机上单栏堆叠，双层滚动是灾难，跟页面自然滚 */}
          <div className="space-y-2 lg:max-h-[70vh] lg:overflow-y-auto lg:pr-1">
            {trades.map(r => <TradeCard key={r.positionId} r={r} />)}
          </div>
        </div>

        <div className="rounded-lg pt-card p-4 space-y-2.5">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="microlabel">
              {t('detail.timeline')}{viewingHistory && ` · R${viewingRound}`}
            </span>
            {/* 按天翻看：前后一天箭头 + 日期框；清掉回到不限日期 */}
            <div className="ml-auto flex items-center gap-1">
              <button type="button" className={ICON_BTN} aria-label={t('detail.prevDay')} onClick={() => shiftDay(-1)}>
                <ChevronLeft className="w-3.5 h-3.5" />
              </button>
              <input type="date" value={day ?? ''} max={today}
                     onChange={e => setDay(e.target.value || null)}
                     className="h-6 px-1.5 rounded border border-border bg-input text-[10px] num" />
              <button type="button" className={ICON_BTN} aria-label={t('detail.nextDay')}
                      disabled={!day || day >= today} onClick={() => shiftDay(1)}>
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
              {day && (
                <button type="button" className={ICON_BTN} aria-label={t('detail.allDays')} onClick={() => setDay(null)}>
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>
          {decisions.length === 0 && (
            <div className="py-10 text-center text-xs text-muted-foreground">{day ? t('detail.noDecisionsDay') : t('detail.noDecisions')}</div>
          )}
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
