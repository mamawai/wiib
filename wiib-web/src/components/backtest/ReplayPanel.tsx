import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowDownRight, ArrowUpRight, CalendarDays, ChevronRight, Dices, Flag, Gauge,
  History, Loader2, Pause, Play, RotateCcw, Skull, Wallet, X,
} from 'lucide-react';
import { backtestApi } from '../../api';
import { BacktestChart, type ChartTradeMark } from './BacktestChart';
import { EquityChart } from '../EquityChart';
import { useToast } from '../ui/use-toast';
import { getCoinPriceDecimals } from '../../lib/coinConfig';
import { aggregateBars, barIndexAt, IV_OPTIONS, ivLabel } from '../../lib/klineAgg';
import { cn, fmtDateTime, fmtNum } from '../../lib/utils';
import {
  close, endSession, equity, initialState, open, stats, step,
  type ReplayState, type ReplayTrade,
} from '../../lib/replayEngine';
import type { ReplayCoverage } from '../../types';
import type { TnEquityPoint } from '../../types/testnet';

const M5 = 300_000;
/** 开局前置上下文根数：给玩家一屏"过去"可看，也给画线留参照 */
const CONTEXT_BARS = 200;
const DURATIONS = [
  { label: '3 天', days: 3 }, { label: '7 天', days: 7 },
  { label: '14 天', days: 14 }, { label: '28 天', days: 28 },
];
/** 自动播放速度档（bar/秒） */
const AUTO_SPEEDS = [1, 3, 10];
const PCT_OPTIONS = [25, 50, 75, 100];

const dayStartUtc = (yyyyMmDd: string) => Date.parse(`${yyyyMmDd}T00:00:00Z`);
const toDateInput = (ms: number) => new Date(ms).toISOString().slice(0, 10);

const REASON_LABEL: Record<ReplayTrade['reason'], string> = {
  MANUAL: '平仓', LIQUIDATION: '爆仓', END: '结算',
};

interface Session {
  symbol: string;
  blind: boolean;
  /** 回放段首根 openTime(ms)；盲测的时间脱敏基准 */
  startMs: number;
  /** 原始 5m 行（含上下文段）；切周期在此之上前端聚合 */
  raw: number[][];
  balance: number;
  leverage: number;
}

/**
 * 手动复盘：按所选周期（5m/15m/1h/4h/1d）逐根揭示 K 线，按收盘价开多/开空/平仓
 * （合约式单净仓+局级杠杆）。底层数据一律 5m，对局中可切周期，之后按新周期推进。
 * 撮合在 lib/replayEngine（纯函数），本组件只管节奏与展示。成绩不落库，刷新即失。
 */
export function ReplayPanel() {
  const { toast } = useToast();

  // ---- 配置 ----
  const [coverage, setCoverage] = useState<ReplayCoverage[]>([]);
  const [symbol, setSymbol] = useState('ETHUSDT');
  const [blind, setBlind] = useState(true);
  const [customDate, setCustomDate] = useState(() => toDateInput(Date.now() - 30 * 86_400_000));
  const [days, setDays] = useState(3);
  const [balance, setBalance] = useState('100000');
  const [leverage, setLeverage] = useState('5');
  const [loading, setLoading] = useState(false);

  // ---- 本局 ----
  const [session, setSession] = useState<Session | null>(null);
  const [state, setState] = useState<ReplayState>(() => initialState(100_000));
  const [played, setPlayed] = useState(0);      // 已揭示的可播放 bar 数（当前周期口径）
  const [ivMin, setIvMin] = useState(5);        // 回放周期（分钟），对局中可切
  const [finished, setFinished] = useState<'END' | 'LIQUIDATION' | null>(null);
  const [auto, setAuto] = useState(0);          // 0=手动，其余为 bar/秒
  const [pct, setPct] = useState(100);
  /** 结算用权益点（真实时间；只在结算面板展示，盲测不泄露） */
  const eqPointsRef = useRef<TnEquityPoint[]>([]);

  /** 当前周期视图：open < startMs 的桶算上下文（开局即揭示），其余为可播放段 */
  const derived = useMemo(() => {
    if (!session) return null;
    const aggBars = aggregateBars(session.raw, ivMin);
    let ctxCount = 0;
    while (ctxCount < aggBars.length && aggBars[ctxCount][0] < session.startMs) ctxCount++;
    return { aggBars, ctxCount, playable: aggBars.length - ctxCount };
  }, [session, ivMin]);

  // doNext 被键盘/interval 调，用 ref 拿最新闭包
  const stateRef = useRef(state);
  const playedRef = useRef(played);
  const sessionRef = useRef(session);
  const finishedRef = useRef(finished);
  const derivedRef = useRef(derived);
  useEffect(() => { stateRef.current = state; }, [state]);
  useEffect(() => { playedRef.current = played; }, [played]);
  useEffect(() => { sessionRef.current = session; }, [session]);
  useEffect(() => { finishedRef.current = finished; }, [finished]);
  useEffect(() => { derivedRef.current = derived; }, [derived]);

  useEffect(() => {
    backtestApi.historyCoverage().then(setCoverage).catch(() => { /* 配置台显示"暂无数据" */ });
  }, []);

  const cov = coverage.find(c => c.symbol === symbol);

  // ---- 开局 ----
  const handleStart = useCallback(async () => {
    if (!cov) {
      toast('该币种暂无本地 K 线数据', 'error');
      return;
    }
    const bal = Number(balance) || 100000;
    const lev = Math.max(1, Math.min(100, Number(leverage) || 5));
    const need = days * 288;
    const minStart = cov.earliestMs + CONTEXT_BARS * M5;
    const maxStart = cov.latestMs - need * M5;
    if (maxStart <= minStart) {
      toast('本地 K 线深度不足一局所需', 'error');
      return;
    }
    let startMs: number;
    if (blind) {
      const slots = Math.floor((maxStart - minStart) / M5);
      startMs = minStart + Math.floor(Math.random() * (slots + 1)) * M5;
    } else {
      const picked = dayStartUtc(customDate);
      if (!Number.isFinite(picked)) {
        toast('日期无效', 'error');
        return;
      }
      startMs = Math.min(Math.max(picked, minStart), maxStart);
    }
    setLoading(true);
    try {
      const page = await backtestApi.historyKlines(symbol, startMs - CONTEXT_BARS * M5, startMs + need * M5);
      const rows = page.rows;
      const playable5 = rows.filter(r => r[0] >= startMs).length;
      if (playable5 < 30) {
        toast('该区间 K 线不足，换个起点再试', 'error');
        return;
      }
      eqPointsRef.current = [];
      setState(initialState(bal));
      setPlayed(0);
      setIvMin(5);
      setFinished(null);
      setAuto(0);
      setSession({ symbol, blind, startMs, raw: rows, balance: bal, leverage: lev });
    } catch (e) {
      toast((e as Error).message || 'K 线拉取失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [cov, balance, leverage, days, blind, customDate, symbol, toast]);

  // ---- 逐根推进（键盘/自动播放共用；按当前周期一根一根走，不可回退，重开一局即复位） ----
  const doNext = useCallback(() => {
    const ses = sessionRef.current;
    const d = derivedRef.current;
    if (!ses || !d || finishedRef.current) return;
    const i = d.ctxCount + playedRef.current;
    if (i >= d.aggBars.length) return;
    const bar = d.aggBars[i];
    let st = step(stateRef.current, bar[4], i, bar[0]);
    const newPlayed = playedRef.current + 1;
    let fin: 'END' | 'LIQUIDATION' | null = null;
    if (st.liquidated) {
      fin = 'LIQUIDATION';
    } else if (newPlayed >= d.playable) {
      st = endSession(st, bar[4], i, bar[0]);
      fin = 'END';
    }
    eqPointsRef.current.push({ time: bar[0], cumPnl: equity(st, bar[4]) - ses.balance });
    setState(st);
    setPlayed(newPlayed);
    if (fin) {
      setFinished(fin);
      setAuto(0);
    }
  }, []);

  // ---- 对局中切周期：进度按时间对齐到新周期已完整走完的 bar（只回退不前进，不泄露未来） ----
  const switchIv = (min: number) => {
    const ses = session;
    const d = derived;
    if (!ses || !d || min === ivMin) return;
    const newAgg = aggregateBars(ses.raw, min);
    const newIvMs = min * 60_000;
    let ctx = 0;
    while (ctx < newAgg.length && newAgg[ctx][0] < ses.startMs) ctx++;
    if (finished) {
      // 结算后切周期只是换视图，全量揭示
      setPlayed(newAgg.length - ctx);
      setIvMin(min);
      return;
    }
    const playedToMs = played > 0 ? d.aggBars[d.ctxCount + played - 1][0] + ivMin * 60_000 : ses.startMs;
    let np = 0;
    while (ctx + np < newAgg.length && newAgg[ctx + np][0] + newIvMs <= playedToMs) np++;
    setPlayed(np);
    setIvMin(min);
    setAuto(0);
    // 进度向下对齐会重走一小段，把重叠窗口的旧权益点裁掉，曲线保持单调
    const nextStepMs = newAgg[ctx + np]?.[0] ?? Infinity;
    eqPointsRef.current = eqPointsRef.current.filter(p => p.time < nextStepMs);
    toast(`已切换到 ${ivLabel(min)}，回放将按 ${ivLabel(min)} 周期推进`, 'info');
  };

  // 空格 = 下一根（输入框聚焦时不抢键）
  useEffect(() => {
    if (!session) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.code !== 'Space') return;
      const el = document.activeElement;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) return;
      e.preventDefault();
      doNext();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [session, doNext]);

  // 自动播放
  useEffect(() => {
    if (!auto || !session || finished) return;
    const timer = setInterval(doNext, 1000 / auto);
    return () => clearInterval(timer);
  }, [auto, session, finished, doNext]);

  // ---- 当前价（最后一根已揭示 bar 的收盘；开局时=上下文末根） ----
  const aggBars = derived?.aggBars ?? [];
  const ctxCount = derived?.ctxCount ?? 0;
  const playable = derived?.playable ?? 0;
  const curIdx = session ? ctxCount + played - 1 : 0;
  const curBar = session && aggBars.length > 0 ? aggBars[Math.max(0, Math.min(curIdx, aggBars.length - 1))] : null;
  const curPrice = curBar ? curBar[4] : 0;
  const curEquity = curBar ? equity(state, curPrice) : 0;

  /** 盲测未结算时显示 D{n} HH:mm 相对时间，其余显示真实时间 */
  const fmtReplayTime = useCallback((ms: number) => {
    const ses = sessionRef.current;
    if (ses?.blind && !finishedRef.current) {
      const day = Math.floor((ms - ses.startMs) / 86_400_000) + 1;
      const d = new Date(ms + 8 * 3_600_000);
      return `D${day} ${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}`;
    }
    return fmtDateTime(ms);
  }, []);

  // ---- 交易操作（只能按当前收盘价） ----
  const handleOpen = (side: 'LONG' | 'SHORT') => {
    if (!session || !curBar || finished) return;
    setState(s => open(s, side, pct / 100, session.leverage, curPrice, curIdx, curBar[0]));
  };
  const handleClose = () => {
    if (!session || !curBar || finished) return;
    setState(s => close(s, curPrice, curIdx, curBar[0], 'MANUAL'));
  };

  // ---- 图表标记（按成交时间映射到当前周期的桶：开在 14:05 的单切 15m 后落在 14:00 蜡烛上） ----
  const marks = useMemo<ChartTradeMark[]>(() => {
    if (!derived || derived.aggBars.length === 0) return [];
    const bars = derived.aggBars;
    const at = (t: number) => barIndexAt(bars, t);
    const out: ChartTradeMark[] = state.trades.map(t => {
      const oi = at(t.openTime);
      const ci = at(t.closeTime);
      return {
        openBarIndex: oi, openTime: bars[oi][0], side: t.side,
        closeBarIndex: ci, closeTime: bars[ci][0], pnl: t.pnl,
        exitLabel: REASON_LABEL[t.reason],
      };
    });
    if (state.position) {
      const oi = at(state.position.openTime);
      out.push({ openBarIndex: oi, openTime: bars[oi][0], side: state.position.side });
    }
    return out;
  }, [state, derived]);

  const pos = state.position;
  const unrealized = pos ? (curPrice - pos.entryPrice) * pos.qty * (pos.side === 'LONG' ? 1 : -1) : 0;
  const st = finished && session ? stats(state, session.balance, eqPointsRef.current.map(p => p.cumPnl + session.balance)) : null;

  // ==================== 配置台 ====================
  if (!session) {
    return (
      <div className="rounded-lg pt-card p-4 md:p-5 space-y-4 max-w-2xl">
        <div className="flex flex-wrap items-end gap-3">
          <div>
            <div className="microlabel uppercase mb-1">币种</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              {['BTCUSDT', 'ETHUSDT'].map(sym => (
                <button key={sym} type="button"
                  onClick={() => setSymbol(sym)}
                  className={cn('px-3 h-9 text-xs font-bold transition-colors',
                    symbol === sym ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                  {sym.replace('USDT', '')}
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="microlabel uppercase mb-1">开局方式</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              <button type="button" onClick={() => setBlind(true)}
                className={cn('px-3 h-9 text-xs font-bold flex items-center gap-1.5 transition-colors',
                  blind ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                <Dices className="w-3.5 h-3.5" /> 随机盲测
              </button>
              <button type="button" onClick={() => setBlind(false)}
                className={cn('px-3 h-9 text-xs font-bold flex items-center gap-1.5 transition-colors',
                  !blind ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                <CalendarDays className="w-3.5 h-3.5" /> 自选起点
              </button>
            </div>
          </div>
          {!blind && (
            <div>
              <div className="microlabel uppercase mb-1">起始日期</div>
              <input type="date" value={customDate}
                onChange={e => setCustomDate(e.target.value)}
                className="h-9 px-2.5 rounded-md border border-border bg-input text-xs num" />
            </div>
          )}
          <div>
            <div className="microlabel uppercase mb-1">复盘时长</div>
            <div className="flex rounded-md border border-border overflow-hidden">
              {DURATIONS.map(d => (
                <button key={d.days} type="button" onClick={() => setDays(d.days)}
                  className={cn('px-3 h-9 text-xs font-bold transition-colors num',
                    days === d.days ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
                  {d.label}
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="microlabel uppercase mb-1">初始资金</div>
            <input type="number" min={1} value={balance}
              onChange={e => setBalance(e.target.value)}
              className="h-9 w-28 px-2.5 rounded-md border border-border bg-input text-xs num" />
          </div>
          <div>
            <div className="microlabel uppercase mb-1">杠杆</div>
            <input type="number" min={1} max={100} value={leverage}
              onChange={e => setLeverage(e.target.value)}
              className="h-9 w-16 px-2.5 rounded-md border border-border bg-input text-xs num" />
          </div>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void handleStart()}
            disabled={loading || !cov}
            className={cn(
              'h-10 px-5 rounded-lg font-black text-sm flex items-center gap-2 transition-all machined',
              'bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98]',
              'disabled:opacity-50 disabled:cursor-not-allowed',
            )}
          >
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
            开始复盘
          </button>
          <span className="text-[10px] text-muted-foreground leading-snug">
            {cov
              ? <>本地数据 {toDateInput(cov.earliestMs)} ~ {toDateInput(cov.latestMs)} · 盲测隐藏真实日期，结算后揭晓</>
              : '正在读取本地 K 线覆盖范围…'}
            <br />按所选周期逐根推进（空格 = 下一根，对局中可切 5m/15m/1h/4h/1d），只能按收盘价买卖 · 复盘进度不保存，刷新即失
          </span>
        </div>
      </div>
    );
  }

  // ==================== 对局中 / 结算 ====================
  const decimals = getCoinPriceDecimals(session.symbol);
  return (
    <div className="space-y-5">
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        {/* 左：图表 + 播放 + 交易操作 */}
        <div className="space-y-5 min-w-0">
          <div className="rounded-lg pt-card p-3 md:p-4 space-y-3">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="microlabel uppercase">
                {session.symbol} · {ivLabel(ivMin)} · {session.blind && !finished ? '盲测' : '复盘'}
              </span>
              <div className="flex rounded border border-border overflow-hidden">
                {IV_OPTIONS.map(o => (
                  <button key={o.min} type="button" onClick={() => switchIv(o.min)}
                    className={cn('px-1.5 h-6 text-[10px] font-bold transition-colors num',
                      ivMin === o.min ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                    {o.label}
                  </button>
                ))}
              </div>
              {curBar && (
                <span className="text-[10px] num text-primary font-bold">{fmtReplayTime(curBar[0])}</span>
              )}
              <span className="ml-auto text-[10px] text-muted-foreground num">
                {played} / {playable} 根
              </span>
            </div>

            <BacktestChart
              bars={aggBars}
              marks={marks}
              cursor={ctxCount + played}
              symbol={session.symbol}
              decimals={decimals}
              blindBaseMs={session.blind && !finished ? session.startMs : null}
              bucketSec={ivMin * 60}
            />

            {/* 播放控制 */}
            <div className="flex items-center gap-2.5 flex-wrap pt-1 border-t border-border/40">
              <button
                type="button"
                onClick={doNext}
                disabled={!!finished}
                className={cn(
                  'h-10 px-4 rounded-lg font-black text-sm flex items-center gap-1.5 transition-all machined',
                  'bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98]',
                  'disabled:opacity-40 disabled:cursor-not-allowed',
                )}
              >
                下一根 <ChevronRight className="w-4 h-4" />
              </button>
              <div className="flex items-center gap-1">
                <Gauge className="w-3.5 h-3.5 text-muted-foreground" />
                {AUTO_SPEEDS.map(sp => (
                  <button key={sp} type="button" disabled={!!finished}
                    onClick={() => setAuto(a => (a === sp ? 0 : sp))}
                    className={cn('px-2 h-7 rounded text-[10px] font-bold transition-colors num disabled:opacity-40',
                      auto === sp ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                    {sp}根/秒
                  </button>
                ))}
                {auto > 0 && (
                  <button type="button" onClick={() => setAuto(0)}
                    className="w-7 h-7 rounded flex items-center justify-center text-primary" aria-label="暂停">
                    <Pause className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
              <span className="hidden md:inline text-[10px] text-muted-foreground">空格 = 下一根 · 不可回退</span>
              {!finished && (
                <button type="button"
                  onClick={() => {
                    // 主动结束：有仓先按当前收盘清算
                    if (curBar) setState(s => endSession(s, curPrice, curIdx, curBar[0]));
                    setFinished('END');
                    setAuto(0);
                  }}
                  className="ml-auto h-8 px-3 rounded-lg border border-border hover:bg-surface-hover text-[11px] font-bold text-muted-foreground hover:text-foreground flex items-center gap-1">
                  <Flag className="w-3.5 h-3.5" /> 结束本局
                </button>
              )}
            </div>

            {/* 交易操作条：大触区，移动端优先 */}
            {!finished && (
              <div className="flex items-stretch gap-2 flex-wrap pt-1 border-t border-border/40">
                {!pos ? (
                  <>
                    <div className="flex items-center gap-1">
                      <Wallet className="w-3.5 h-3.5 text-muted-foreground" />
                      {PCT_OPTIONS.map(p => (
                        <button key={p} type="button" onClick={() => setPct(p)}
                          className={cn('px-2 h-9 rounded text-[10px] font-bold transition-colors num',
                            pct === p ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground')}>
                          {p}%
                        </button>
                      ))}
                    </div>
                    <button type="button" onClick={() => handleOpen('LONG')}
                      className="flex-1 min-w-[110px] h-11 rounded-lg bg-gain text-white font-black text-sm flex items-center justify-center gap-1.5 hover:brightness-105 active:scale-[.98] machined">
                      <ArrowUpRight className="w-4 h-4" /> 开多 @ {fmtNum(curPrice, decimals)}
                    </button>
                    <button type="button" onClick={() => handleOpen('SHORT')}
                      className="flex-1 min-w-[110px] h-11 rounded-lg bg-loss text-white font-black text-sm flex items-center justify-center gap-1.5 hover:brightness-105 active:scale-[.98] machined">
                      <ArrowDownRight className="w-4 h-4" /> 开空 @ {fmtNum(curPrice, decimals)}
                    </button>
                  </>
                ) : (
                  <>
                    <div className="flex-1 min-w-[180px] rounded-lg border border-border bg-card-2 px-3 py-1.5 text-[11px] flex items-center gap-3">
                      <span className={cn('font-black', pos.side === 'LONG' ? 'text-gain' : 'text-loss')}>
                        {pos.side === 'LONG' ? '多' : '空'} {pos.leverage}x
                      </span>
                      <span className="num text-muted-foreground">入 {fmtNum(pos.entryPrice, decimals)}</span>
                      <span className={cn('num font-black ml-auto', unrealized >= 0 ? 'text-gain' : 'text-loss')}>
                        {unrealized >= 0 ? '+' : ''}{fmtNum(unrealized)}
                      </span>
                    </div>
                    <button type="button" onClick={handleClose}
                      className="flex-1 min-w-[110px] h-11 rounded-lg bg-primary text-primary-foreground font-black text-sm flex items-center justify-center gap-1.5 hover:brightness-105 active:scale-[.98] machined">
                      <X className="w-4 h-4" /> 平仓 @ {fmtNum(curPrice, decimals)}
                    </button>
                  </>
                )}
              </div>
            )}
          </div>

          {/* 结算面板 */}
          {finished && st && (
            <div className="rounded-lg pt-card p-4 md:p-5 space-y-4">
              <div className="flex items-center gap-2">
                {finished === 'LIQUIDATION'
                  ? <><Skull className="w-4 h-4 text-loss" /><span className="text-sm font-black text-loss">爆仓出局</span></>
                  : <><Flag className="w-4 h-4 text-primary" /><span className="text-sm font-black">本局结算</span></>}
                <span className="ml-auto text-[10px] text-muted-foreground num">
                  {/* 盲测揭晓真实区间 */}
                  {fmtDateTime(session.startMs)} ~ {curBar ? fmtDateTime(curBar[0]) : ''}
                </span>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className={cn('text-lg font-black num', st.netProfit >= 0 ? 'text-gain' : 'text-loss')}>
                    {st.netProfit >= 0 ? '+' : ''}{fmtNum(st.netProfit)}
                  </div>
                  <div className="microlabel uppercase mt-1">净利 · {(st.returnPct * 100).toFixed(1)}%</div>
                </div>
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className="text-lg font-black num">
                    {st.totalTrades > 0 ? `${(st.winRate * 100).toFixed(0)}%` : '—'}
                  </div>
                  <div className="microlabel uppercase mt-1">胜率 · {st.wins}胜{st.losses}负</div>
                </div>
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className="text-lg font-black num text-loss">{(st.maxDrawdownPct * 100).toFixed(1)}%</div>
                  <div className="microlabel uppercase mt-1">最大回撤</div>
                </div>
                <div className="rounded-md border border-border bg-card-2 px-3 py-2.5">
                  <div className="text-lg font-black num">{fmtNum(st.finalEquity, 0)}</div>
                  <div className="microlabel uppercase mt-1">最终权益 · 费 {fmtNum(st.totalFees, 0)}</div>
                </div>
              </div>
              {eqPointsRef.current.length > 1 && <EquityChart points={eqPointsRef.current} />}
              <button
                type="button"
                onClick={() => setSession(null)}
                className="h-10 px-5 rounded-lg font-black text-sm flex items-center gap-2 bg-primary text-primary-foreground hover:brightness-105 active:scale-[.98] machined"
              >
                <RotateCcw className="w-4 h-4" /> 再来一局
              </button>
            </div>
          )}
        </div>

        {/* 右：权益读数 + 本局交易记录 */}
        <div className="space-y-5 min-w-0">
          <div className="rounded-lg pt-card p-3 grid grid-cols-2 gap-2">
            <div className="rounded-md border border-border bg-card-2 px-3 py-2">
              <div className={cn('text-base font-black num', curEquity >= session.balance ? 'text-gain' : 'text-loss')}>
                {fmtNum(curEquity, 0)}
              </div>
              <div className="microlabel uppercase mt-0.5">权益</div>
            </div>
            <div className="rounded-md border border-border bg-card-2 px-3 py-2">
              <div className="text-base font-black num">{fmtNum(state.cash, 0)}</div>
              <div className="microlabel uppercase mt-0.5">可用现金</div>
            </div>
          </div>

          <div className="rounded-lg pt-card p-3 flex flex-col min-w-0 lg:max-h-[560px] max-h-[360px]">
            <div className="flex items-center gap-1.5 pb-2 border-b border-border/40 shrink-0">
              <History className="w-3.5 h-3.5 text-primary" />
              <span className="text-[11px] font-black">本局交易</span>
              <span className="ml-auto text-[10px] text-muted-foreground num">{state.trades.length} 笔</span>
            </div>
            <div className="flex-1 overflow-y-auto py-1 space-y-0.5 overscroll-contain">
              {state.trades.length === 0 ? (
                <div className="py-10 text-center text-[11px] text-muted-foreground">还没有成交</div>
              ) : (
                [...state.trades].reverse().map((t, i) => {
                  const isLong = t.side === 'LONG';
                  const win = t.pnl >= 0;
                  return (
                    <div key={state.trades.length - i}
                      className="flex items-center gap-2.5 py-1.5 px-2 rounded-md text-[11px] border-b border-border/40 last:border-0">
                      <span className={cn('w-1 self-stretch rounded-full shrink-0', isLong ? 'bg-gain' : 'bg-loss')} />
                      <div className="min-w-0">
                        <div className="font-bold leading-tight">
                          <span className={cn('text-[10px] font-black', isLong ? 'text-gain' : 'text-loss')}>{isLong ? '多' : '空'}</span>
                          <span className="ml-1 text-[10px] text-muted-foreground">{t.leverage}x</span>
                          <span className="ml-1.5 text-[9px] font-bold px-1 py-px rounded bg-muted text-muted-foreground">
                            {REASON_LABEL[t.reason]}
                          </span>
                        </div>
                        <div className="text-[10px] text-muted-foreground num leading-tight mt-0.5">
                          {fmtNum(t.entryPrice, decimals)} → {fmtNum(t.exitPrice, decimals)}
                        </div>
                        <div className="text-[9px] text-muted-foreground/60 num leading-tight">
                          {fmtReplayTime(t.openTime)} ~ {fmtReplayTime(t.closeTime)}
                        </div>
                      </div>
                      <span className={cn('ml-auto font-black num shrink-0', win ? 'text-gain' : 'text-loss')}>
                        {win ? '+' : ''}{fmtNum(t.pnl)}
                      </span>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
