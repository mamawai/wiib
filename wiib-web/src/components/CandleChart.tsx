import { cn, fmtNum, fmtDateTime } from '../lib/utils';
import { useEffect, useRef, useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import i18n from '../i18n';
import {
  createChart, createSeriesMarkers, CrosshairMode, CandlestickSeries, HistogramSeries, LineSeries, LineStyle,
  type IChartApi, type ISeriesApi, type UTCTimestamp, type MouseEventParams,
  type DeepPartial, type HandleScrollOptions, type IPriceLine, type SeriesMarker,
} from 'lightweight-charts';
import { Eye, EyeOff, Globe, History, Layers, Magnet, Maximize2, Minimize2, Trash2 } from 'lucide-react';
import { futuresApi, quantApi, type NewsEventItem } from '../api';
import { useKlineStream } from '../hooks/useKlineStream';
import { useIsDark } from '../hooks/useIsDark';
import { useFullscreen } from '../hooks/useFullscreen';
import { getCoinPriceDecimals } from '../lib/coinConfig';
import { bollSeries, emaSeries, macdSeries, maSeries, rsiSeries } from '../lib/indicators';
import type { ChartCtx } from '../lib/chartDrawings';
import { useDrawings } from './chart/useDrawings';
import { DrawToolPicker } from './chart/DrawToolPicker';
import { NewsMarkersLayer } from './chart/NewsMarkersLayer';

/** 一根 K：series 只用 OHLC，量/额留给气泡和成交量柱。 */
interface Bar { time: number; openMs: number; open: number; high: number; low: number; close: number; volume: number; quote: number; }

const TZ = -8 * 3600;                                       // 固定 UTC+8 偏移(秒)：横轴统一显示新加坡时间且边界对齐
const toBarTime = (ms: number) => Math.floor(ms / 1000) - TZ;
const barDate = (t: number) => new Date((t + TZ) * 1000);   // 反算真实时刻用于格式化
const fmtVol = (n: number) => n >= 1e6 ? (n / 1e6).toFixed(2) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(2) + 'K' : n.toFixed(2);
/** 币安原始行 → Bar：k[0]=开盘ms，1-4=OHLC，5=量(基础币)，7=额(USDT) */
const toBar = (k: number[]): Bar => ({
  time: toBarTime(k[0]), openMs: k[0],
  open: +k[1], high: +k[2], low: +k[3], close: +k[4], volume: +k[5], quote: +k[7],
});
const VOL_UP = 'rgba(8,153,129,.5)', VOL_DOWN = 'rgba(242,54,69,.5)';

/** 日线气泡只显示日期：1d 的 bar 开在 UTC 0 点(=新加坡 08:00)，挂个 08:00 纯噪音 */
const fmtBarTime = (d: Date, interval: string) =>
  interval === '1d'
    ? d.toLocaleDateString('zh-CN', { timeZone: 'Asia/Singapore', month: '2-digit', day: '2-digit' })
    : fmtDateTime(d);

/** 气泡 HTML（灰白半透明底，亮/暗主题下都清晰）：时间·开高低收·涨跌·涨跌幅·振幅·量·额。 */
function tooltipHtml(bar: Bar, bars: Bar[], idx: Map<number, number>, d: number, base: string, interval: string): string {
  const i = idx.get(bar.time);
  const prevClose = (i != null && i > 0) ? bars[i - 1].close : bar.open;   // 昨收=前一根收盘
  const chg = bar.close - prevClose;
  const chgPct = prevClose ? chg / prevClose * 100 : 0;
  const amp = prevClose ? (bar.high - bar.low) / prevClose * 100 : 0;
  const up = chg >= 0, col = up ? '#089981' : '#f23645', sign = up ? '+' : '';
  const tStr = fmtBarTime(barDate(bar.time), interval);
  const row = (k: string, v: string, c = '#1f2328') =>
    `<div style="display:flex;justify-content:space-between;gap:18px"><span style="color:#6b7280">${k}</span><span style="color:${c};font-weight:700">${v}</span></div>`;
  // 词表在函数体里现查：气泡每次悬停重新拼，切语言下一次悬停就是新的
  return `<div style="color:#6b7280;font-weight:700;margin-bottom:5px;padding-bottom:5px;border-bottom:1px solid rgba(0,0,0,.1)">${tStr}</div>`
    + row(i18n.t('market:chart.open'), fmtNum(bar.open, d)) + row(i18n.t('market:chart.high'), fmtNum(bar.high, d))
    + row(i18n.t('market:chart.low'), fmtNum(bar.low, d)) + row(i18n.t('market:chart.close'), fmtNum(bar.close, d))
    + row(i18n.t('market:chart.change'), sign + fmtNum(chg, d), col) + row(i18n.t('market:chart.changePct'), sign + chgPct.toFixed(2) + '%', col)
    + row(i18n.t('market:chart.amplitude'), amp.toFixed(2) + '%')
    + row(i18n.t('market:chart.volume'), fmtVol(bar.volume) + ' ' + base)
    + row(i18n.t('market:chart.turnover'), fmtVol(bar.quote) + ' USDT');
}

// ========== 副图指标 (MACD / RSI) ==========

const RSI_PERIODS = [6, 12, 24] as const;              // 国内常用的三档，短中长各一条
const RSI_COLORS = ['#ff9800', '#2196f3', '#9c27b0'];
const DIF_COLOR = '#f0b90b', DEA_COLOR = '#2962ff';
const REF_LINE = 'rgba(128,128,128,.4)';               // RSI 70/30 灰色虚线
const LEGEND_DIM = '#8a8f9a';                          // legend 里指标名那截的灰

/**
 * MACD 柱四色：浓色=动能增强，淡色=动能衰减。
 * 等价于国内软件"实心柱/空心柱"的语义 —— lightweight-charts 的 histogram
 * 每根柱只能给一个填充色，做不出描边空心，而 TradingView 官方 MACD 也是这套四色。
 */
const HIST_UP_S = 'rgba(38,166,154,.9)', HIST_UP_W = 'rgba(38,166,154,.28)';
const HIST_DN_S = 'rgba(239,83,80,.9)', HIST_DN_W = 'rgba(239,83,80,.28)';

/** 比前一根更远离零轴 = 动能还在增强 = 浓色(实心)；往零轴回收 = 淡色(空心) */
function histColor(cur: number, prev: number | null): string {
  const strong = prev === null || (cur >= 0 ? cur >= prev : cur <= prev);
  if (cur >= 0) return strong ? HIST_UP_S : HIST_UP_W;
  return strong ? HIST_DN_S : HIST_DN_W;
}

/**
 * 副图 series 句柄 + 读数条；indicators=false 或两个副图都关时压根不建，整个为 null。
 * MACD/RSI 各可单独关：关掉的那组 series 不建（hist/dif/dea 为空、rsi 为空数组），
 * pane 序号动态分配（macdPane/rsiPane，-1 = 未开）——只开 RSI 时它就在 pane 1。
 */
interface IndSeries {
  chart: IChartApi;
  hist?: ISeriesApi<'Histogram'>;
  dif?: ISeriesApi<'Line'>;
  dea?: ISeriesApi<'Line'>;
  rsi: ISeriesApi<'Line'>[];                 // 与 RSI_PERIODS 同序；RSI 关闭时为空
  macdPane: number;
  rsiPane: number;
  macdLegend: HTMLDivElement | null;
  rsiLegend: HTMLDivElement | null;
  decimals: number;
  /** 最新一根的值，鼠标没悬停时 legend 常驻显示这个 */
  last: { dif?: number; dea?: number; hist?: number; rsi: (number | undefined)[] };
}

/** null 段跳过不画（预热期指标无值），返回 LWC 要的点数组 */
const toLine = (bars: Bar[], s: (number | null)[]) =>
  bars.flatMap((b, i) => s[i] === null ? [] : [{ time: b.time as UTCTimestamp, value: s[i] as number }]);

/** 读数条里的一格：`DIF:2.61`，无值显示 -- */
const legendCell = (color: string, label: string, v: number | undefined, digits: number) =>
  `<span style="color:${color};margin-right:9px">${label}:${v === undefined ? '--' : v.toFixed(digits)}</span>`;

/**
 * 手机竖屏（图表宽 < 380）走紧凑读数：字号降一档、砍掉指标参数前缀。
 * 按图表实际宽度判断而不是视口——同一台机器横屏、或 PC 上窗口拖窄，都该跟着缩。
 */
const isCompact = (chart: IChartApi) => chart.options().width < 380;

// ========== 主图叠加指标 (MA / EMA / BOLL) ==========

/** MA 和 EMA 共用这组周期 */
const MA_PERIODS = [7, 25, 99] as const;
const MA_COLORS = ['#f0b90b', '#e91e63', '#26c6da'];    // 7黄 25粉 99青
const EMA_COLORS = ['#ff9800', '#ab47bc', '#66bb6a'];   // 同周期错开色相，免得跟 MA 混
const BOLL_PERIOD = 20, BOLL_MULT = 2;
const BOLL_MID_COLOR = '#90a4ae', BOLL_BAND_COLOR = 'rgba(144,164,174,.65)';

export type OverlayKey = 'ma' | 'ema' | 'boll';

interface OverlaySeries {
  ma: ISeriesApi<'Line'>[];
  ema: ISeriesApi<'Line'>[];
  boll: ISeriesApi<'Line'>[];                // [upper, mid, lower]
  /** 最新一根的值，没悬停时读数条显示它 */
  last: { ma: (number | undefined)[]; ema: (number | undefined)[]; boll: (number | undefined)[] };
}

const lastOfSeries = (s: (number | null)[]) => {
  const v = s[s.length - 1];
  return v === null || v === undefined ? undefined : v;
};

/** 主图三组指标一次算齐 */
function computeOverlay(bars: Bar[]) {
  const closes = bars.map(b => b.close);
  const b = bollSeries(closes, BOLL_PERIOD, BOLL_MULT);
  return {
    ma: MA_PERIODS.map(p => maSeries(closes, p)),
    ema: MA_PERIODS.map(p => emaSeries(closes, p)),
    boll: [b.upper, b.mid, b.lower],
  };
}

function cacheOverlayLast(ov: OverlaySeries, c: ReturnType<typeof computeOverlay>) {
  ov.last = { ma: c.ma.map(lastOfSeries), ema: c.ema.map(lastOfSeries), boll: c.boll.map(lastOfSeries) };
}

function setOverlayData(ov: OverlaySeries, bars: Bar[]) {
  const c = computeOverlay(bars);
  c.ma.forEach((s, k) => ov.ma[k].setData(toLine(bars, s)));
  c.ema.forEach((s, k) => ov.ema[k].setData(toLine(bars, s)));
  c.boll.forEach((s, k) => ov.boll[k].setData(toLine(bars, s)));
  cacheOverlayLast(ov, c);
}

/** 同副图的道理：EMA/MA/BOLL 都只有末端一个值会随未收盘那根变，重算全量后只 update 末点 */
function updateOverlayLast(ov: OverlaySeries, bars: Bar[]) {
  const n = bars.length - 1;
  if (n < 0) return;
  const c = computeOverlay(bars);
  const time = bars[n].time as UTCTimestamp;
  const push = (arr: ISeriesApi<'Line'>[], series: (number | null)[][]) =>
    series.forEach((s, k) => { if (s[n] !== null) arr[k].update({ time, value: s[n] as number }); });
  push(ov.ma, c.ma);
  push(ov.ema, c.ema);
  push(ov.boll, c.boll);
  cacheOverlayLast(ov, c);
}

const lastOf = (s: (number | null)[]) => {
  const v = s[s.length - 1];
  return v === null || v === undefined ? undefined : v;
};

/** 一次算齐副图要的所有序列，setData 和实时 update 共用 */
function computeAll(bars: Bar[]) {
  const closes = bars.map(b => b.close);
  const { dif, dea, hist } = macdSeries(closes);
  return { dif, dea, hist, rsis: RSI_PERIODS.map(p => rsiSeries(closes, p)) };
}

type Computed = ReturnType<typeof computeAll>;

function cacheLast(ind: IndSeries, c: Computed) {
  ind.last = { dif: lastOf(c.dif), dea: lastOf(c.dea), hist: lastOf(c.hist), rsi: c.rsis.map(lastOf) };
}

/** 在 pane 左上角挂一条读数。v5 暴露 getHTMLElement 正是为了这种叠加内容 */
function makeLegend(host: HTMLElement | null): HTMLDivElement | null {
  if (!host) return null;
  // pane 元素默认 static，不改成 relative 的话 legend 会飞到更外层的定位祖先上
  if (getComputedStyle(host).position === 'static') host.style.position = 'relative';
  const el = document.createElement('div');
  el.style.cssText = 'position:absolute;left:10px;top:2px;z-index:3;pointer-events:none;'
    + 'font:11px/1.6 ui-monospace,Consolas,monospace;font-weight:700;white-space:nowrap';
  host.appendChild(el);
  return el;
}

/**
 * 副图 legend 的懒创建。
 *
 * 不能在 addSeries 之后立刻建：pane 的 DOM(paneWidget) 是渲染流程里 _syncGuiWithModel
 * 才创建的，addSeries 只建了 pane 的 model，此刻 getHTMLElement() 还返回 null。
 * 所以每次刷读数时补一次，第一次拉到历史数据时图表早已 paint 过，必然建得上。
 */
function ensureLegends(ind: IndSeries) {
  const panes = ind.chart.panes();
  if (ind.macdPane >= 0 && !ind.macdLegend) ind.macdLegend = makeLegend(panes[ind.macdPane]?.getHTMLElement() ?? null);
  if (ind.rsiPane >= 0 && !ind.rsiLegend) ind.rsiLegend = makeLegend(panes[ind.rsiPane]?.getHTMLElement() ?? null);
}

/**
 * 刷新两条读数。param 落在某根 bar 上就显示那根的值（跟随十字线），
 * 否则回落到最新值 —— 不悬停时读数不能空着。
 */
function renderLegends(ind: IndSeries, param: MouseEventParams | null) {
  ensureLegends(ind);
  const compact = isCompact(ind.chart);
  const hovering = param?.time != null;
  const pick = (s: ISeriesApi<'Line'> | ISeriesApi<'Histogram'>, fallback: number | undefined) => {
    if (!hovering) return fallback;
    const d = param?.seriesData.get(s);
    return d && 'value' in d ? (d.value as number) : undefined;
  };

  if (ind.macdLegend && ind.hist && ind.dif && ind.dea) {
    const h = pick(ind.hist, ind.last.hist);
    const hc = h === undefined ? LEGEND_DIM : (h >= 0 ? '#089981' : '#f23645');
    ind.macdLegend.style.fontSize = compact ? '10px' : '11px';
    // 窄屏砍掉参数前缀：那截占 88px，手机上留着会把 MACD 值挤出可视区（nowrap 直接裁掉）
    ind.macdLegend.innerHTML =
      (compact ? '' : `<span style="color:${LEGEND_DIM};margin-right:10px">MACD(12,26,9)</span>`)
      + legendCell(DIF_COLOR, 'DIF', pick(ind.dif, ind.last.dif), ind.decimals)
      + legendCell(DEA_COLOR, 'DEA', pick(ind.dea, ind.last.dea), ind.decimals)
      + legendCell(hc, 'MACD', h, ind.decimals);
  }
  if (ind.rsiLegend && ind.rsi.length) {
    ind.rsiLegend.style.fontSize = compact ? '10px' : '11px';
    ind.rsiLegend.innerHTML = RSI_PERIODS
      .map((p, k) => legendCell(RSI_COLORS[k], `RSI(${p})`, pick(ind.rsi[k], ind.last.rsi[k]), 2))
      .join('');
  }
}

const BOLL_LABELS = ['UP', 'MID', 'LOW'];
const OVERLAY_COLORS: Record<OverlayKey, string[]> = {
  ma: MA_COLORS, ema: EMA_COLORS, boll: [BOLL_BAND_COLOR, BOLL_MID_COLOR, BOLL_BAND_COLOR],
};

/**
 * 图表上方工具条里的读数区。开着的组摊开显示三个值（悬停跟随十字线，不悬停显示最新值），
 * 关着的留空——开关是工具条上的按钮，不再兼任显示入口。
 */
function renderOverlayLegend(
  ov: OverlaySeries,
  refs: Record<OverlayKey, HTMLSpanElement | null>,
  on: Record<OverlayKey, boolean>,
  param: MouseEventParams | null,
  decimals: number,
  compact = false,
) {
  const hovering = param?.time != null;
  const pick = (s: ISeriesApi<'Line'>, fallback: number | undefined) => {
    if (!hovering) return fallback;
    const d = param?.seriesData.get(s);
    return d && 'value' in d ? (d.value as number) : undefined;
  };
  const labels: Record<OverlayKey, string[]> = {
    ma: MA_PERIODS.map(p => `MA${p}`),
    ema: MA_PERIODS.map(p => `EMA${p}`),
    boll: BOLL_LABELS,
  };

  for (const key of ['ma', 'ema', 'boll'] as OverlayKey[]) {
    const el = refs[key];
    if (!el) continue;
    el.style.fontSize = compact ? '10px' : '11px';
    if (!on[key]) { el.innerHTML = ''; continue; }
    el.innerHTML = ov[key]
      .map((s, k) => legendCell(OVERLAY_COLORS[key][k], labels[key][k], pick(s, ov.last[key][k]), decimals))
      .join('');
  }
}

/** 历史全量灌入副图（关掉的那组没有 series，跳过） */
function setIndicators(ind: IndSeries, bars: Bar[]) {
  const c = computeAll(bars);
  if (ind.hist) {
    ind.hist.setData(bars.flatMap((b, i) => c.hist[i] === null ? []
      : [{
        time: b.time as UTCTimestamp,
        value: c.hist[i] as number,
        color: histColor(c.hist[i] as number, i > 0 ? c.hist[i - 1] : null),
      }]));
  }
  ind.dif?.setData(toLine(bars, c.dif));
  ind.dea?.setData(toLine(bars, c.dea));
  ind.rsi.forEach((s, k) => s.setData(toLine(bars, c.rsis[k])));
  cacheLast(ind, c);
}

/**
 * 只刷新最后一根的指标值。
 * 未收盘那根 close 在变，但 EMA/RSI 都是从历史向前递推、前面的点早已定型，
 * close 变化只波及递推链末端那一个值 —— 所以全量重算后只 update 末点是精确的，不是近似。
 * (500 根纯算术 <1ms；不做增量状态维护，那反而在同一根 bar 被反复重写时会算错。)
 */
function updateIndicatorsLast(ind: IndSeries, bars: Bar[]) {
  const n = bars.length - 1;
  if (n < 0) return;
  const c = computeAll(bars);
  const time = bars[n].time as UTCTimestamp;
  if (ind.hist && c.hist[n] !== null) {
    ind.hist.update({
      time, value: c.hist[n] as number,
      color: histColor(c.hist[n] as number, n > 0 ? c.hist[n - 1] : null),
    });
  }
  if (ind.dif && c.dif[n] !== null) ind.dif.update({ time, value: c.dif[n] as number });
  if (ind.dea && c.dea[n] !== null) ind.dea.update({ time, value: c.dea[n] as number });
  ind.rsi.forEach((s, k) => { if (c.rsis[k][n] !== null) s.update({ time, value: c.rsis[k][n] as number }); });
  cacheLast(ind, c);
}

/**
 * 实时蜡烛图 + 成交量柱 + 悬停气泡。
 * 历史走 REST(含量/额)；当前根两种驱动二选一：
 * - streamLive=true（默认，合约）：走 {@link useKlineStream}(后端实时广播 o/h/l/c/v/q)
 * - streamLive=false（现货/bstock，后端不广播其K线）：由外部 tick(价格流)更新最后一根的 c/h/l
 */
// 桶宽即对齐口径：floor(ts/bucket)*bucket 落到 UTC 整点，与币安各周期的开盘时刻一致
// （4h→UTC 00/04/08/12/16/20，1d→UTC 00:00），所以 tick 驱动落桶不会错位。
const BUCKET_MS = { '5m': 300_000, '15m': 900_000, '1h': 3_600_000, '4h': 14_400_000, '1d': 86_400_000 } as const;
type Interval = keyof typeof BUCKET_MS;

/**
 * 一个当前仓位要画到图上的全部参考价：入场 / 多档止盈 / 多档止损 / 强平。
 * 页面把仓位映射成这个通用结构传进来，图表不认识业务实体 —— bstock 页没有合约也就不传。
 */
export interface PositionOverlay {
  id: number;
  /** 线标签前缀，如 "多 10x" / "空 25x"（双向持仓同 symbol 至多一多一空，天然不重名） */
  label: string;
  side: 'LONG' | 'SHORT';
  entry: number;
  tps: number[];
  sls: number[];
  /** 全仓的强平价是账户级动态估算，可能给不出 → null 不画 */
  liq: number | null;
}

/** 一笔历史成交要打到图上的信息。B=买入方向（开多/平空），S=卖出方向（开空/平多） */
export interface TradeMark {
  timeMs: number;
  side: 'B' | 'S';
  price: number;
}

// ========== 向左翻历史的三个阈值 ==========
/** 每次往回翻的根数，与首屏同量级 */
const PAGE_SIZE = 500;
/** 左边还剩这么多根就预取。默认视口 110 根≈留一屏缓冲，让加载在用户拖到墙之前就完成 */
const LOAD_THRESHOLD = 100;
/**
 * 内存上限。每次实时 tick 都要对全量 bars 重算 11 条指标序列
 * （见 updateIndicatorsLast 的注释：故意不做增量，否则同一根被反复重写时会算错），
 * 5000 根≈2-5ms/tick 还无感，上万就开始掉帧。
 * 覆盖范围：5m≈17天 / 15m≈52天 / 1h≈208天 / 4h≈2.3年 / 1d≈13.7年。
 */
const MAX_BARS = 5000;

/**
 * 手机纵向滑动交还给页面滚动（否则想下滑页面却在拖图表）；横向平移/捏合缩放保留。
 * 提到模块级是因为画线拖拽期间要临时把 handleScroll 整个关掉，松手后得原样恢复这一份。
 */
const SCROLL_OPTS: DeepPartial<HandleScrollOptions> =
  { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false };

/** 触屏设备判定：竖屏全屏的"转横屏"提示只该出现在真能转的设备上（桌面竖屏显示器转不了） */
const IS_TOUCH = window.matchMedia('(pointer: coarse)').matches;

/** symbol → 新闻标签：BTCUSDT 映射 BTC，美股/商品代码在词表内的直接同名；词表外无标签=不挂新闻轨 */
export function newsTagForSymbol(symbol: string): string | undefined {
  if (symbol === 'BTCUSDT') return 'BTC';
  return ['OIL', 'GOLD', 'COIN', 'MSTR', 'TSLA', 'NVDA'].includes(symbol) ? symbol : undefined;
}

/** 快讯是外部内容，进 innerHTML 前必须转义（标题/正文/URL 都不可信） */
const esc = (s: string) => s.replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c] as string));
const clip = (s: string, n: number) => (s.length > n ? s.slice(0, n) + '…' : s);

export function CandleChart({ symbol, interval, limit = 300, visibleBars = 110, klinesFn = futuresApi.klines, streamLive = true, tick = null, indicators = false, onIntervalChange, positionOverlays, tradeMarks, newsTag }: { symbol: string; interval: Interval; limit?: number; visibleBars?: number; klinesFn?: (symbol: string, interval: string, limit: number, endTime?: number) => Promise<number[][]>; streamLive?: boolean; tick?: { price: number; ts: number } | null; indicators?: boolean; onIntervalChange?: (i: Interval) => void; positionOverlays?: PositionOverlay[]; tradeMarks?: TradeMark[]; newsTag?: string }) {
  const { t } = useTranslation('market');
  const isDark = useIsDark();
  const rootRef = useRef<HTMLDivElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartDivRef = useRef<HTMLDivElement>(null);
  const tipRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const volRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const indRef = useRef<IndSeries | null>(null);
  const ovRef = useRef<OverlaySeries | null>(null);
  // 默认全关走裸K：三组九条线画满会糊成一团，要看哪组点图表上方工具条的按钮开
  const [overlays, setOverlays] = useState<Record<OverlayKey, boolean>>({ ma: false, ema: false, boll: false });
  const overlaysRef = useRef(overlays);
  const maLegendRef = useRef<HTMLSpanElement>(null);
  const emaLegendRef = useRef<HTMLSpanElement>(null);
  const bollLegendRef = useRef<HTMLSpanElement>(null);
  const barsRef = useRef<Bar[]>([]);
  const idxRef = useRef<Map<number, number>>(new Map());
  const readyRef = useRef(false);
  const hintRef = useRef<HTMLDivElement>(null);
  // 翻历史的两道闸：in-flight 锁挡住 setData 自己触发的那次 range 变化（漏了就是无限自激狂发请求），
  // 枯竭标记挡住"币安没有更早数据了还一直问"
  const loadingRef = useRef(false);
  const exhaustedRef = useRef(false);
  const hoverRef = useRef<{ time: number | null; x: number; y: number }>({ time: null, x: 0, y: 0 });

  // 仓位参考线：总开关记 localStorage（跨会话保持），单仓位显隐是会话内临时选择不落盘。
  // chartEpoch 由建图 effect 每次重建后 bump —— 参考线画在蜡烛 series 上，图一重建线就
  // 随旧图销毁了，画线 effect 必须跟着重跑，否则切周期后线全丢
  const [showPosLines, setShowPosLines] = useState(() => localStorage.getItem('wiib-chart-pos-lines') !== '0');
  const [hiddenPosIds, setHiddenPosIds] = useState<ReadonlySet<number>>(new Set());
  const [chartEpoch, setChartEpoch] = useState(0);

  // 副图开关：MACD/RSI 各自可关。关的那组压根不建 series/pane（省算力也省高度），
  // 切换走建图 effect 重建 —— 与切周期同一条路径，不为省一次重绘再造第二套增删 pane 逻辑
  const [subs, setSubs] = useState(() => ({
    macd: localStorage.getItem('wiib-chart-sub-macd') !== '0',
    rsi: localStorage.getItem('wiib-chart-sub-rsi') !== '0',
  }));

  // 历史成交 B/S 标记：默认关（打开一次记住）。marksByTimeRef 供点击弹窗按 bar 查成交
  const [showMarks, setShowMarks] = useState(() => localStorage.getItem('wiib-chart-trade-marks') === '1');
  const marksByTimeRef = useRef<Map<number, { b: number[]; s: number[] }>>(new Map());
  const markTipRef = useRef<HTMLDivElement>(null);
  // 新闻标记：默认开（关一次记住）。globe 画在主图画布上（NewsMarkersLayer），随蜡烛同帧移动
  const [showNews, setShowNews] = useState(() => localStorage.getItem('wiib-chart-news') !== '0');
  const newsLayerRef = useRef<NewsMarkersLayer | null>(null);
  const newsTipRef = useRef<HTMLDivElement>(null);
  const cdRef = useRef<HTMLDivElement>(null);
  /** 仓位参考线的悬浮小签（写在线上、贴着价格轴左侧），由 250ms 循环随缩放平移重新定位 */
  const posLabelElsRef = useRef<{ el: HTMLDivElement; price: number }[]>([]);
  const isDarkRef = useRef(isDark);
  const decimals = getCoinPriceDecimals(symbol);
  const base = symbol.replace('USDT', '');

  const live = useKlineStream(symbol, interval);
  const fs = useFullscreen(rootRef);
  const {
    attach: attachDrawings, tool, setTool, magnet, setMagnet, hiddenAll, setHiddenAll,
    selected: hasSelection, count: drawCount, trash, textEdit, commitText, cancelText,
  } = useDrawings();

  // 竖屏全屏会把 K 线纵向拉成细长条（画布 ~390×800，价格轴自动铺满高度）。
  // Android 在 useFullscreen 里直接锁横屏；iOS 没有 lock API，只能提示用户自己转 ——
  // matchMedia 自带监听，转过去提示自动消失，图表随既有的 ResizeObserver 重排
  const [portrait, setPortrait] = useState(() => window.matchMedia('(orientation: portrait)').matches);
  useEffect(() => {
    const mq = window.matchMedia('(orientation: portrait)');
    const onChange = (e: MediaQueryListEvent) => setPortrait(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  // 三个 span 的当前节点。读数高频刷新，走 DOM 直改而不是 setState，免得鼠标一动就整树重渲染
  const legendRefs = useCallback(() => ({
    ma: maLegendRef.current, ema: emaLegendRef.current, boll: bollLegendRef.current,
  }), []);

  // 显示/定位气泡（用 ref，避免闭包读到过期 isDark/props）。
  // 【钉对侧，不跟光标】看右半边的 K 线，面板停在左侧；看左半边，面板停在右侧（贴价格轴内侧）——
  // 面板永远不挡正在看的那几根蜡烛。纵向跟随光标居中，夹在图内。
  const showTip = (bar: Bar, px: number, py: number) => {
    const tip = tipRef.current, wrap = wrapRef.current; if (!tip || !wrap) return;
    tip.innerHTML = tooltipHtml(bar, barsRef.current, idxRef.current, decimals, base, interval);
    tip.style.display = 'block';
    const W = wrap.clientWidth, H = wrap.clientHeight, tw = tip.offsetWidth, th = tip.offsetHeight;
    const axisW = chartRef.current?.priceScale('right').width() ?? 56;
    const x = px > W / 2 ? 8 : W - axisW - tw - 8;
    const y = Math.min(Math.max(4, py - th / 2), H - th - 6);
    tip.style.left = Math.max(4, x) + 'px';
    tip.style.top = y + 'px';
  };
  const showTipRef = useRef(showTip);
  // 事件回调只在交互时读取，提交后同步最新值即可（render 期写 ref 违反 react-hooks/refs）
  useEffect(() => { isDarkRef.current = isDark; showTipRef.current = showTip; });

  // 建图 + 拉历史（symbol/interval 变则重建）
  useEffect(() => {
    const wrap = wrapRef.current, host = chartDivRef.current;
    if (!wrap || !host) return;
    // 异步回调回来时图可能已被 cleanup 销毁（切了 symbol/interval），对死图 setData 会抛
    let disposed = false;
    const dark = isDarkRef.current;
    const grid = dark ? '#181b21' : '#f1f1ee', border = dark ? '#23262e' : '#e4e4df', text = dark ? '#878b96' : '#71737b';

    const chart = createChart(host, {
      width: host.clientWidth, height: host.clientHeight,
      // attributionLogo: v5 默认 true 会在右下角画 TradingView logo，v4 没有，关掉保持原样
      // panes.separatorColor: v5 默认 #2B2B43 深蓝，亮色模式下很扎眼，跟着网格线走
      layout: {
        background: { color: 'transparent' }, textColor: text, fontSize: 11, attributionLogo: false,
        panes: { separatorColor: grid, separatorHoverColor: 'rgba(128,128,128,.25)', enableResize: true },
      },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      crosshair: { mode: CrosshairMode.Normal },
      handleScroll: SCROLL_OPTS,
      // 读数条已移到图表外的工具条，顶部只留常规呼吸空间
      rightPriceScale: { borderColor: border, scaleMargins: { top: 0.06, bottom: 0.26 } },
      timeScale: { borderColor: border, timeVisible: true, secondsVisible: false, rightOffset: 5 },
    });
    chartRef.current = chart;

    const candle = chart.addSeries(CandlestickSeries, {
      upColor: '#089981', downColor: '#f23645', borderUpColor: '#089981', borderDownColor: '#f23645',
      wickUpColor: '#089981', wickDownColor: '#f23645',
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
      // 轴上的最新价标签关掉：由 cdRef 那个"价格+倒计时"合体框顶替（虚线最新价线保留）
      lastValueVisible: false,
    });
    candleRef.current = candle;
    setChartEpoch(e => e + 1);   // 通知仓位参考线 effect：series 换新的了，重画

    const vol = chart.addSeries(HistogramSeries, { priceScaleId: '', priceFormat: { type: 'volume' }, lastValueVisible: false, priceLineVisible: false });
    vol.priceScale().applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });   // 量柱压底部 18%
    volRef.current = vol;

    // 画线图层。挂蜡烛 series 而不是 pane —— 只有 series primitive 有
    // priceAxisViews/timeAxisViews，水平线的价格轴标签、趋势线端点的时间标签白拿。
    // attach/detach 必须在本 effect 内成对做：若另开同依赖的 effect，React 会先跑
    // 这里的 cleanup(chart.remove())、再跑那边的，那时 series 已死，detachPrimitive 要炸。
    // bars/idx 走 getter：图层活得比任何一帧都久，实时 tick 和翻历史都会换掉 ref 里的数组。
    const detachDrawings = attachDrawings({
      chart, series: candle, host, symbol, decimals, scrollOpts: SCROLL_OPTS,
      ctx: {
        bars: () => barsRef.current,
        idx: () => idxRef.current,
        bucketSec: BUCKET_MS[interval] / 1000,
        timeScale: chart.timeScale(),
        series: candle,
      } satisfies ChartCtx,
      fmtTime: t => fmtBarTime(barDate(t), interval),
    });

    // 主图叠加：MA / EMA / BOLL 都挂 pane 0，跟蜡烛共用价格轴。
    // 三组一律建出来，显不显示走 visible，切换时不用重建 series 重灌数据
    if (indicators) {
      const on = overlaysRef.current;
      const thin = { lineWidth: 1 as const, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false };
      const ov: OverlaySeries = {
        ma: MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, { ...thin, color: MA_COLORS[k], visible: on.ma })),
        ema: MA_PERIODS.map((_, k) => chart.addSeries(LineSeries, { ...thin, color: EMA_COLORS[k], visible: on.ema })),
        boll: [0, 1, 2].map(k => chart.addSeries(LineSeries, {
          ...thin,
          color: k === 1 ? BOLL_MID_COLOR : BOLL_BAND_COLOR,
          lineStyle: k === 1 ? LineStyle.Solid : LineStyle.Dashed,   // 中轨实线，上下轨虚线
          visible: on.boll,
        })),
        last: { ma: [], ema: [], boll: [] },
      };
      ovRef.current = ov;
    }

    // 副图：MACD/RSI 各自可开关，pane 序号动态分配（addSeries 第三参数就是 pane 序号，
    // v5 自带时间轴/十字线联动）。只开 RSI 时它顶到 pane 1，不给关掉的 MACD 留空档
    if (indicators && (subs.macd || subs.rsi)) {
      const line = { lineWidth: 1 as const, priceLineVisible: false, lastValueVisible: false };
      let paneIdx = 1;
      const macdPane = subs.macd ? paneIdx++ : -1;
      const rsiPane = subs.rsi ? paneIdx++ : -1;

      const hist = subs.macd
        ? chart.addSeries(HistogramSeries, { priceLineVisible: false, lastValueVisible: false }, macdPane) : undefined;
      const dif = subs.macd ? chart.addSeries(LineSeries, { ...line, color: DIF_COLOR }, macdPane) : undefined;
      const dea = subs.macd ? chart.addSeries(LineSeries, { ...line, color: DEA_COLOR }, macdPane) : undefined;
      // RSI 天然 0-100，锁死纵轴免得自适应缩放把 70/30 线挤出视野
      const rsi = subs.rsi ? RSI_PERIODS.map((_, k) => chart.addSeries(LineSeries, {
        ...line, color: RSI_COLORS[k],
        autoscaleInfoProvider: () => ({ priceRange: { minValue: 0, maxValue: 100 } }),
      }, rsiPane)) : [];
      if (rsi.length) {
        for (const price of [70, 30]) {
          rsi[0].createPriceLine({ price, color: REF_LINE, lineWidth: 1, lineStyle: LineStyle.Dashed, axisLabelVisible: false, title: '' });
        }
      }

      const panes = chart.panes();
      panes[0].setStretchFactor(3);      // 主图:每个副图 = 3:1
      // 顶层 rightPriceScale 的 scaleMargins(bottom .26) 是给主图量柱留的，会连累副图；这里按 pane 覆盖掉
      if (macdPane >= 0) {
        panes[macdPane].setStretchFactor(1);
        panes[macdPane].priceScale('right').applyOptions({ scaleMargins: { top: 0.22, bottom: 0.12 } });  // top 留给 legend
      }
      if (rsiPane >= 0) {
        panes[rsiPane].setStretchFactor(1);
        panes[rsiPane].priceScale('right').applyOptions({ scaleMargins: { top: 0.22, bottom: 0.08 } });
      }
      indRef.current = {
        chart, hist, dif, dea, rsi, macdPane, rsiPane, decimals,
        macdLegend: macdPane >= 0 ? makeLegend(panes[macdPane].getHTMLElement()) : null,
        rsiLegend: rsiPane >= 0 ? makeLegend(panes[rsiPane].getHTMLElement()) : null,
        last: { rsi: [] },
      };
    }

    chart.subscribeCrosshairMove((param: MouseEventParams) => {
      // 读数条先更新：它跟气泡不同，移出图表也要留着（回落到最新值），不能被下面的 early return 跳过
      if (indRef.current) renderLegends(indRef.current, param);
      if (ovRef.current) renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, param, decimals, isCompact(chart));
      const tip = tipRef.current; if (!tip) return;
      const t = param.time as number | undefined;
      if (t == null || !param.point) { hoverRef.current.time = null; tip.style.display = 'none'; return; }
      const i = idxRef.current.get(t);
      if (i == null) { tip.style.display = 'none'; return; }
      hoverRef.current = { time: t, x: param.point.x, y: param.point.y };
      showTipRef.current(barsRef.current[i], param.point.x, param.point.y);
    });

    // 点击带 B/S 角标的那根 K 线 → 弹出该根内的逐笔成交价；点空白处收起。
    // 按 bar 的时间桶查而不是抠标记的像素命中 —— 点中蜡烛任意位置都算，手机上尤其重要
    chart.subscribeClick((param: MouseEventParams) => {
      const tipEl = markTipRef.current; if (!tipEl) return;
      const t = param.time as number | undefined;
      const g = t != null ? marksByTimeRef.current.get(t) : undefined;
      if (!g || !param.point) { tipEl.style.display = 'none'; return; }
      const row = (side: string, col: string, prices: number[]) => prices.map(p =>
        `<div style="display:flex;gap:16px;justify-content:space-between"><span style="color:${col};font-weight:700">${side}</span><span style="color:#1f2328;font-weight:700">${fmtNum(p, decimals)}</span></div>`).join('');
      // 弹窗每次点击现拼，词表走 i18n 实例（建图 effect 不该因为切语言整个重建）
      tipEl.innerHTML =
        `<div style="color:#6b7280;font-weight:700;margin-bottom:4px;padding-bottom:4px;border-bottom:1px solid rgba(0,0,0,.1)">${i18n.t('market:chart.fillsAt', { time: fmtBarTime(barDate(t as number), interval) })}</div>`
        + row(i18n.t('market:chart.buy'), '#089981', g.b) + row(i18n.t('market:chart.sell'), '#f23645', g.s);
      tipEl.style.left = `${Math.min(param.point.x + 12, host.clientWidth - 150)}px`;
      tipEl.style.top = `${Math.min(param.point.y + 12, host.clientHeight - 30 * (g.b.length + g.s.length) - 40)}px`;
      tipEl.style.display = 'block';
    });

    /** 全量重灌蜡烛+量柱+指标。首屏和前插历史共用——LWC 只能 append 不能 prepend，前插只能整条重灌 */
    const paintAll = (bars: Bar[]) => {
      candle.setData(bars.map(b => ({ time: b.time as UTCTimestamp, open: b.open, high: b.high, low: b.low, close: b.close })));
      vol.setData(bars.map(b => ({ time: b.time as UTCTimestamp, value: b.volume, color: b.close >= b.open ? VOL_UP : VOL_DOWN })));
      // 指标必须全量重算：MA/BOLL 是滑动窗口老值不变，但 EMA/RSI/MACD 是从最早那根递推的，
      // 前面接上历史后种子位置变了，接缝往后几百根的值都会跟着变（再远指数衰减到看不见）
      if (indRef.current) { setIndicators(indRef.current, bars); renderLegends(indRef.current, null); }
      if (ovRef.current) {
        setOverlayData(ovRef.current, bars);
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, isCompact(chart));
      }
    };

    // ========== 向左翻历史 ==========
    // 提示走 DOM 直改：走 setState 会把整个图表子树连带重渲染
    let hintTimer: ReturnType<typeof setTimeout> | undefined;
    const showHint = (text: string, autoHideMs = 2000) => {
      const el = hintRef.current; if (!el) return;
      clearTimeout(hintTimer);
      el.textContent = text;
      el.style.display = 'block';
      if (autoHideMs) hintTimer = setTimeout(() => { el.style.display = 'none'; }, autoHideMs);
    };
    const hideHint = () => { clearTimeout(hintTimer); if (hintRef.current) hintRef.current.style.display = 'none'; };

    const loadMore = () => {
      if (loadingRef.current || exhaustedRef.current || !readyRef.current || !barsRef.current.length) return;
      if (barsRef.current.length >= MAX_BARS) { exhaustedRef.current = true; showHint(i18n.t('market:chart.limitReached')); return; }

      loadingRef.current = true;
      showHint(i18n.t('market:chart.loadingHistory'), 0);
      // endTime = 现有最早那根开盘前 1ms；后端按 endTime 缓存 1h（闭合 bar 不可变），多人翻同一页共享同一个 key
      klinesFn(symbol, interval, PAGE_SIZE, barsRef.current[0].openMs - 1).then(raw => {
        if (disposed) return;
        // 去重：币安边界可能回一根重叠的，LWC 遇到重复时间会抛
        const oldest = barsRef.current[0].time;
        const older = raw.map(toBar).filter(b => b.time < oldest);
        if (!older.length) { exhaustedRef.current = true; showHint(i18n.t('market:chart.earliest')); return; }

        const merged = older.concat(barsRef.current);
        const idx = new Map<number, number>();
        merged.forEach((b, i) => idx.set(b.time, i));
        barsRef.current = merged; idxRef.current = idx;

        // 重灌把 logical index 整体右移了 older.length 格，视口不补回去画面就弹走那么多根
        const before = chart.timeScale().getVisibleLogicalRange();
        paintAll(merged);
        if (before) {
          chart.timeScale().setVisibleLogicalRange({ from: before.from + older.length, to: before.to + older.length });
        }
        hideHint();
      }).catch(() => { if (!disposed) hideHint(); })
        // disposed 时新一轮 effect 已经重置过锁了，这里别再动，否则会把新请求的锁误清
        .finally(() => { if (!disposed) loadingRef.current = false; });
    };

    chart.timeScale().subscribeVisibleLogicalRangeChange(r => {
      if (r && r.from < LOAD_THRESHOLD) loadMore();
    });

    readyRef.current = false;
    loadingRef.current = false;
    exhaustedRef.current = false;
    klinesFn(symbol, interval, limit).then(raw => {
      if (disposed) return;
      const bars = raw.map(toBar);
      const idx = new Map<number, number>();
      bars.forEach((b, i) => idx.set(b.time, i));
      barsRef.current = bars; idxRef.current = idx;
      paintAll(bars);
      // 默认只看最近 visibleBars 根（fitContent 会把全量挤进视口，蜡烛小成一条线）；往左拖/缩放仍可看全历史
      if (bars.length > visibleBars) {
        chart.timeScale().setVisibleLogicalRange({ from: bars.length - visibleBars, to: bars.length + 5 });
      } else {
        chart.timeScale().fitContent();
      }
      exhaustedRef.current = raw.length < limit;   // 首屏就没拉满 = 币安只有这么多，别再往回问
      readyRef.current = true;
    }).catch(() => { /* 历史失败仍可靠实时累积 */ });

    const ro = new ResizeObserver(() => {
      chart.applyOptions({ width: host.clientWidth, height: host.clientHeight });
      // 旋屏/拖窗口会让 compact 判定翻转，读数条得跟着重排，否则要等下一个 tick 才变
      if (indRef.current) renderLegends(indRef.current, null);
      if (ovRef.current) {
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, isCompact(chart));
      }
    });
    ro.observe(host);

    return () => {
      // hint 是 JSX 节点、不随图表销毁重建：切 symbol/interval 时若正挂着"载入历史…"，
      // 在飞的请求会因 disposed 直接 return 而走不到 hideHint，不在这里收就永远留在新图上
      disposed = true; hideHint();
      detachDrawings();                    // 必须赶在 chart.remove() 前面
      ro.disconnect(); chart.remove();
      chartRef.current = null; candleRef.current = null; volRef.current = null;
      indRef.current = null; ovRef.current = null;
      readyRef.current = false; barsRef.current = []; idxRef.current = new Map();
      loadingRef.current = false; exhaustedRef.current = false;
    };
  }, [symbol, interval, limit, visibleBars, decimals, klinesFn, indicators, subs, legendRefs, attachDrawings]);

  // 指标开关：只切 visible，不重建 series；切完立刻刷读数（展开的组要马上有值）
  useEffect(() => {
    overlaysRef.current = overlays;
    const ov = ovRef.current; if (!ov) return;
    ov.ma.forEach(s => s.applyOptions({ visible: overlays.ma }));
    ov.ema.forEach(s => s.applyOptions({ visible: overlays.ema }));
    ov.boll.forEach(s => s.applyOptions({ visible: overlays.boll }));
    renderOverlayLegend(ov, legendRefs(), overlays, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
  }, [overlays, decimals, legendRefs]);

  // 仓位参考线：入场实线（多=涨色/空=跌色）、TP 虚线、SL 疏点线、强平橙色大虚线。
  // 多空双开靠色系区分：一个仓位的整组线共用其方向色，强平线例外——那是危险信号，统一橙色。
  // 【信息写在线上，不落 y 轴】轴标签一多就和刻度、最新价框互相盖；这里每条线配一个
  // 悬浮小签（「多10x 入场 63000」）贴在价格轴左侧的线尾上，定位由 250ms 循环维护。
  useEffect(() => {
    const series = candleRef.current, wrap = wrapRef.current;
    if (!series || !wrap || !showPosLines || !positionOverlays?.length) return;
    const lines: IPriceLine[] = [];
    const labels: { el: HTMLDivElement; price: number }[] = [];
    for (const p of positionOverlays) {
      if (hiddenPosIds.has(p.id)) continue;
      const col = p.side === 'LONG' ? '#0abf95' : '#ff5a68';
      const add = (price: number | null | undefined, title: string, lineStyle: LineStyle,
                   color = col, lineWidth: 1 | 2 = 1) => {
        if (price == null || !(price > 0)) return;
        lines.push(series.createPriceLine({ price, color, lineWidth, lineStyle, axisLabelVisible: false, title: '' }));
        const el = document.createElement('div');
        el.textContent = `${title} ${fmtNum(price, decimals)}`;
        el.style.cssText = 'position:absolute;display:none;transform:translateY(-50%);z-index:4;'
          + 'pointer-events:none;padding:0 4px;border-radius:3px;'
          + `background:${color};color:#fff;font:700 9.5px/1.6 ui-monospace,Consolas,monospace;white-space:nowrap`;
        wrap.appendChild(el);
        labels.push({ el, price });
      };
      add(p.entry, `${p.label} ${t('chart.entry')}`, LineStyle.Solid, col, 2);
      p.tps.forEach((tp, i) => add(tp, `${p.label} TP${p.tps.length > 1 ? i + 1 : ''}`, LineStyle.Dashed));
      p.sls.forEach((s, i) => add(s, `${p.label} SL${p.sls.length > 1 ? i + 1 : ''}`, LineStyle.SparseDotted));
      add(p.liq, `${p.label} ${t('chart.liq')}`, LineStyle.LargeDashed, '#f97316');
    }
    posLabelElsRef.current = labels;
    return () => {
      posLabelElsRef.current = [];
      labels.forEach(l => l.el.remove());
      // 开关/数据变时挨个摘掉重画；图整体重建时 series 已死、removePriceLine 会抛，吞掉即可
      try { lines.forEach(l => series.removePriceLine(l)); } catch { /* chart disposed */ }
    };
    // t 进依赖：切语言时 t 换新引用，参考线小签（"多10x 入场 63000"）跟着重画
  }, [positionOverlays, showPosLines, hiddenPosIds, decimals, chartEpoch, t]);

  // 历史成交 B/S 标记：同一根 K 线内聚合成一个角标（B3S2 这种），点开看逐笔价格。
  // 全买=涨色、全卖=跌色、混合=主色；文本自带方向语义，shape 缩到 0 只留字
  useEffect(() => {
    const series = candleRef.current;
    if (!series || !showMarks || !tradeMarks?.length) { marksByTimeRef.current = new Map(); return; }

    const bucketMs = BUCKET_MS[interval];
    const byTime = new Map<number, { b: number[]; s: number[] }>();
    for (const m of tradeMarks) {
      const time = toBarTime(Math.floor(m.timeMs / bucketMs) * bucketMs);
      const g = byTime.get(time) ?? { b: [], s: [] };
      (m.side === 'B' ? g.b : g.s).push(m.price);
      byTime.set(time, g);
    }
    marksByTimeRef.current = byTime;

    // 笔数走上标角标：B³S²（canvas 文本没有富文本，Unicode 上标数字顶上）
    const SUP = ['⁰', '¹', '²', '³', '⁴', '⁵', '⁶', '⁷', '⁸', '⁹'];
    const sup = (n: number) => n > 1 ? String(n).split('').map(d => SUP[+d]).join('') : '';
    const markers: SeriesMarker<UTCTimestamp>[] = [...byTime.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([time, g]) => ({
        time: time as UTCTimestamp,
        position: 'aboveBar',
        color: g.b.length && g.s.length ? '#f97316' : g.b.length ? '#0abf95' : '#ff5a68',
        shape: 'square',
        size: 0,
        text: (g.b.length ? 'B' + sup(g.b.length) : '') + (g.s.length ? 'S' + sup(g.s.length) : ''),
      }));
    const plugin = createSeriesMarkers(series, markers);

    return () => {
      marksByTimeRef.current = new Map();
      if (markTipRef.current) markTipRef.current.style.display = 'none';
      // 图整体重建时 series 已死，detach 会抛，吞掉即可
      try { plugin.detach(); } catch { /* chart disposed */ }
    };
  }, [tradeMarks, showMarks, interval, chartEpoch]);

  // 新闻标记：打标快讯按 K 线时间桶聚合，globe 悬在所属那根上方，点开看内容。
  // 语义是"这根K线覆盖的时间段内发生过什么新闻"——按发稿时刻定位，不承诺行情因果。
  // 标记画在主图画布上（NewsMarkersLayer 挂蜡烛 series）：与蜡烛同帧渲染，平移缩放零延迟；
  // 弹窗仍是 DOM（富文本+可点链接，画布画不了），点击命中在 pointerdown 里主动 pick
  useEffect(() => {
    const wrap = wrapRef.current, candle = candleRef.current;
    if (!newsTag || !showNews || !wrap || !candle) return;
    let disposed = false;
    const bucketMs = BUCKET_MS[interval];
    /** time → 该桶的快讯组，点击标记时按命中的时间桶取内容 */
    const groups = new Map<number, NewsEventItem[]>();
    const layer = new NewsMarkersLayer(time => {
      const i = idxRef.current.get(time);
      return i == null ? null : barsRef.current[i].high;
    });
    layer.dark = isDarkRef.current;
    candle.attachPrimitive(layer);
    newsLayerRef.current = layer;

    const fmtClock = (ms: number) => new Date(ms).toLocaleString('zh-CN',
      { timeZone: 'Asia/Singapore', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    const showPopup = (rect: { x: number; y: number; w: number }, events: NewsEventItem[]) => {
      const tip = newsTipRef.current; if (!tip || !events.length) return;
      tip.innerHTML = events.map(e =>
        '<div style="padding:6px 0;border-bottom:1px solid rgba(0,0,0,.07)">'
        + `<div style="color:#6b7280;font-weight:700;margin-bottom:2px">${fmtClock(e.publishedAt)} · ${esc(e.tags)}</div>`
        + `<div style="color:#1f2328;font-weight:700;margin-bottom:2px">${esc(e.title)}</div>`
        + `<div style="color:#374151">${esc(clip(e.content ?? '', 160))}</div>`
        + (e.url ? `<a href="${esc(e.url)}" target="_blank" rel="noopener noreferrer" style="color:#2962ff;font-weight:700">${i18n.t('market:chart.newsSource')}</a>` : '')
        + '</div>').join('');
      tip.style.display = 'block';
      // 内容定了再量尺寸：横向对中标记并夹在图内，纵向优先弹标记上方、顶部放不下翻到下方
      const W = wrap.clientWidth, tw = tip.offsetWidth, th = tip.offsetHeight;
      const ix = rect.x + rect.w / 2, iy = rect.y;
      tip.style.left = `${Math.min(Math.max(4, ix - tw / 2), W - tw - 4)}px`;
      tip.style.top = `${iy - th - 8 >= 4 ? iy - th - 8 : iy + 26}px`;
    };

    // 窗口按内存上限的最远可翻历史算：翻到底标记也都在；服务端上限 500 条倒序保最近
    quantApi.newsEvents(newsTag, Date.now() - bucketMs * MAX_BARS, Date.now() + bucketMs).then(events => {
      if (disposed || !events.length) return;
      for (const e of events) {
        const time = toBarTime(Math.floor(e.publishedAt / bucketMs) * bucketMs);
        const g = groups.get(time) ?? [];
        g.push(e);
        groups.set(time, g);
      }
      for (const g of groups.values()) g.sort((a, b) => a.publishedAt - b.publishedAt);
      layer.markers = [...groups.entries()].map(([time, g]) => ({ time, count: g.length }));
      layer.update();
    }).catch(() => { /* 未登录/接口失败：没有标记而已，图表照常 */ });

    // 点击命中：capture 在 document 上——点中标记时截住事件（LWC 的拖拽别跟着起步），
    // 点在标记与弹窗之外的任何地方都收起弹窗
    const onDown = (ev: PointerEvent) => {
      const tip = newsTipRef.current;
      const target = ev.target as Node;
      if (tip && tip.style.display !== 'none' && tip.contains(target)) return;   // 弹窗内（链接等）放行
      const paneCanvas = chartRef.current?.panes()[0]?.getHTMLElement()?.querySelector('canvas');
      const r = paneCanvas?.getBoundingClientRect();
      const hit = r ? layer.pick(ev.clientX - r.left, ev.clientY - r.top) : null;
      if (hit !== null) {
        ev.preventDefault();
        ev.stopPropagation();
        const rect = layer.rects.get(hit);
        if (rect) showPopup(rect, groups.get(hit) ?? []);
        return;
      }
      if (tip && tip.style.display !== 'none') tip.style.display = 'none';
    };
    document.addEventListener('pointerdown', onDown, true);
    return () => {
      disposed = true;
      document.removeEventListener('pointerdown', onDown, true);
      newsLayerRef.current = null;
      // 图整体重建时 series 已死，detach 会抛，吞掉即可（同成交标记的清理）
      try { candle.detachPrimitive(layer); } catch { /* chart disposed */ }
      if (newsTipRef.current) newsTipRef.current.style.display = 'none';
    };
  }, [newsTag, showNews, interval, chartEpoch]);

  // 「最新价 + 收盘倒计时」合体框：顶在价格轴上原生最新价标签的位置（原生标签已关），
  // 上行价格、下行倒计时，一个框解决"倒计时和价格分家"。底色跟当根蜡烛的涨跌走。
  // 250ms 循环重取 Y 坐标与文案，价格跳动/缩放平移都跟得上；顺带把仓位参考线的悬浮小签
  // 一起重定位（它们同样要随缩放走，各开一个定时器纯属浪费）。
  // 休市/断流时倒计时行自动消失（一个停摆的倒计时比没有更误导），价格行保留。
  useEffect(() => {
    const el = cdRef.current; if (!el) return;
    const bucketMs = BUCKET_MS[interval];
    const render = () => {
      const chart = chartRef.current, candle = candleRef.current;
      const last = barsRef.current[barsRef.current.length - 1];
      const axisW = chart ? chart.priceScale('right').width() : 0;

      // ---- 仓位参考线小签：--------多10x 入场 63000----│y轴│ ----
      // 价格超出 y 轴可视范围时 priceToCoordinate 不返回 null 而是给界外坐标，
      // 小签会飘到主图 pane 外（盖工具条/副图），按 pane 0 高度裁掉
      const paneH = chart ? chart.paneSize(0).height : 0;
      for (const { el: label, price } of posLabelElsRef.current) {
        const y = candle?.priceToCoordinate(price);
        if (y == null || y < 0 || y > paneH) { label.style.display = 'none'; continue; }
        label.style.top = `${y}px`;
        label.style.right = `${axisW + 4}px`;
        label.style.display = 'block';
      }

      if (!chart || !candle || !last) { el.style.display = 'none'; return; }
      const y = candle.priceToCoordinate(last.close);
      if (y == null) { el.style.display = 'none'; return; }

      const remain = last.openMs + bucketMs - Date.now();
      let cd = '';
      if (remain > 0 && remain <= bucketMs) {
        const s = Math.floor(remain / 1000);
        const pad = (n: number) => String(n).padStart(2, '0');
        const h = Math.floor(s / 3600);
        cd = h > 0 ? `${h}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`
                   : `${pad(Math.floor(s / 60))}:${pad(s % 60)}`;
      }
      el.style.background = last.close >= last.open ? '#089981' : '#f23645';
      el.innerHTML =
        `<div style="font:700 11px/1.4 ui-monospace,Consolas,monospace;font-variant-numeric:tabular-nums">${fmtNum(last.close, decimals)}</div>`
        + (cd ? `<div style="margin-top:1px;padding-top:1px;border-top:1px solid rgba(255,255,255,.28);`
              + `font:600 9px/1.3 ui-monospace,Consolas,monospace;letter-spacing:.05em;color:rgba(255,255,255,.85)">${cd}</div>` : '');
      el.style.minWidth = `${axisW}px`;
      el.style.display = 'block';
      // 内容定了再量高度，价格行精确压在价格线的延长线上（框心 ≈ 价格行中心）
      el.style.top = `${y - el.offsetHeight / 2}px`;
    };
    render();
    const timer = setInterval(render, 250);
    return () => { clearInterval(timer); el.style.display = 'none'; };
  }, [interval, decimals, chartEpoch]);

  // 主题切换：只改颜色，不重建
  useEffect(() => {
    const chart = chartRef.current; if (!chart) return;
    const grid = isDark ? '#181b21' : '#f1f1ee', border = isDark ? '#23262e' : '#e4e4df', text = isDark ? '#878b96' : '#71737b';
    chart.applyOptions({
      layout: { textColor: text, panes: { separatorColor: grid } },
      grid: { vertLines: { color: grid }, horzLines: { color: grid } },
      rightPriceScale: { borderColor: border }, timeScale: { borderColor: border },
    });
    const news = newsLayerRef.current;
    if (news) { news.dark = isDark; news.update(); }
  }, [isDark]);

  // 外部价格 tick 驱动（streamLive=false）：桶对齐后更新/追加最后一根，量额保持历史值（价格流无量数据）
  useEffect(() => {
    if (streamLive || !tick || tick.price <= 0 || !readyRef.current) return;
    const candle = candleRef.current, vol = volRef.current; if (!candle || !vol) return;
    const bucketMs = BUCKET_MS[interval];
    const openMs = Math.floor(tick.ts / bucketMs) * bucketMs;
    const time = toBarTime(openMs);
    const last = barsRef.current[barsRef.current.length - 1];
    if (last && time < last.time) return;
    const i = idxRef.current.get(time);
    let bar: Bar;
    if (i == null) {
      bar = { time, openMs, open: tick.price, high: tick.price, low: tick.price, close: tick.price, volume: 0, quote: 0 };
      idxRef.current.set(time, barsRef.current.length);
      barsRef.current.push(bar);
    } else {
      const prev = barsRef.current[i];
      bar = { ...prev, close: tick.price, high: Math.max(prev.high, tick.price), low: Math.min(prev.low, tick.price) };
      barsRef.current[i] = bar;
    }
    candle.update({ time: bar.time as UTCTimestamp, open: bar.open, high: bar.high, low: bar.low, close: bar.close });
    vol.update({ time: bar.time as UTCTimestamp, value: bar.volume, color: bar.close >= bar.open ? VOL_UP : VOL_DOWN });
    if (indRef.current) {
      updateIndicatorsLast(indRef.current, barsRef.current);
      // 没悬停才刷读数，否则会把用户正盯着的那根值冲掉
      if (hoverRef.current.time === null) renderLegends(indRef.current, null);
    }
    if (ovRef.current) {
      updateOverlayLast(ovRef.current, barsRef.current);
      if (hoverRef.current.time === null) {
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
      }
    }
    if (hoverRef.current.time === bar.time) showTipRef.current(bar, hoverRef.current.x, hoverRef.current.y);
  }, [tick, streamLive, interval, decimals, legendRefs]);

  // 实时：当前根原地更新（蜡烛 + 量柱 + 悬停时刷新气泡）
  useEffect(() => {
    if (!streamLive || !live || !readyRef.current) return;
    const candle = candleRef.current, vol = volRef.current; if (!candle || !vol) return;
    const time = toBarTime(live.t);
    // 防乱序：忽略比最后一根更早的(重连/迟到)消息，否则 LWC update(time<lastTime) 会抛异常
    const last = barsRef.current[barsRef.current.length - 1];
    if (last && time < last.time) return;
    const bar: Bar = { time, openMs: live.t, open: live.o, high: live.h, low: live.l, close: live.c, volume: live.v, quote: live.q };
    const i = idxRef.current.get(time);
    if (i == null) { idxRef.current.set(time, barsRef.current.length); barsRef.current.push(bar); }
    else barsRef.current[i] = bar;
    candle.update({ time: time as UTCTimestamp, open: bar.open, high: bar.high, low: bar.low, close: bar.close });
    vol.update({ time: time as UTCTimestamp, value: bar.volume, color: bar.close >= bar.open ? VOL_UP : VOL_DOWN });
    if (indRef.current) {
      updateIndicatorsLast(indRef.current, barsRef.current);
      // 没悬停才刷读数，否则会把用户正盯着的那根值冲掉
      if (hoverRef.current.time === null) renderLegends(indRef.current, null);
    }
    if (ovRef.current) {
      updateOverlayLast(ovRef.current, barsRef.current);
      if (hoverRef.current.time === null) {
        renderOverlayLegend(ovRef.current, legendRefs(), overlaysRef.current, null, decimals, chartRef.current ? isCompact(chartRef.current) : false);
      }
    }
    if (hoverRef.current.time === time) showTipRef.current(bar, hoverRef.current.x, hoverRef.current.y);
  }, [live, streamLive, decimals, legendRefs]);

  const toggle = (k: OverlayKey) => setOverlays(prev => ({ ...prev, [k]: !prev[k] }));

  // 开关按钮样式对齐周期切换组（激活=顶部主色内阴影）
  const chipCls = (on: boolean) =>
    `px-2.5 py-1 text-[11px] font-semibold transition-colors cursor-pointer ${
      on ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
         : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`;
  /** 同一套视觉的图标版，画线/磁吸/删除/全屏共用 */
  const iconCls = (on: boolean) =>
    `px-2 py-1.5 flex items-center justify-center transition-colors cursor-pointer ${
      on ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
         : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`;
  const group = 'flex rounded-md border border-border overflow-hidden divide-x divide-border';

  return (
    // 全屏用的是这一层：原生模式靠 :fullscreen 的 UA 样式铺满，iPhone Safari 没有元素级
    // 全屏则退成 fixed。两种都只改类名不改 DOM 结构，图表不会被 React 卸载重建。
    <div ref={rootRef} className={cn(
      'w-full h-full flex flex-col',
      fs.active && 'bg-background p-2',
      fs.cssMode && 'fixed inset-0 z-50',
    )}>
      {/* 工具条（图表外）。整条常显：代币化美股页不开 indicators，但画线和全屏照样要能用，
          所以只有 MA/EMA/BOLL 那组和读数跟着 indicators 走。
          读数 span 由 renderOverlayLegend 走 DOM 直改（悬停跟随十字线），高频刷新不过 React */}
      <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 px-2 md:px-1 pb-1.5">
        {/* 周期组只在全屏时出现：非全屏页面自己有周期 tab，这里再放一份是重复；
            全屏只把 rootRef 送进 fullscreen，页面那排按钮看不见，切周期全靠这组。
            切换走父级回调改 props，组件不卸载，全屏状态不丢 */}
        {fs.active && onIntervalChange && (
          <div className={group}>
            {(Object.keys(BUCKET_MS) as Interval[]).map(k => (
              <button key={k} type="button" onClick={() => onIntervalChange(k)}
                      className={`num ${chipCls(interval === k)}`}>
                {k}
              </button>
            ))}
          </div>
        )}
        {indicators && (
          <div className={group}>
            {(['ma', 'ema', 'boll'] as OverlayKey[]).map(k => (
              <button key={k} type="button" onClick={() => toggle(k)} className={chipCls(overlays[k])}>
                {k.toUpperCase()}
              </button>
            ))}
          </div>
        )}

        {/* 副图开关：切换重建图表（同切周期一条路径），状态各自记 localStorage */}
        {indicators && (
          <div className={group}>
            {(['macd', 'rsi'] as const).map(k => (
              <button key={k} type="button" className={chipCls(subs[k])}
                      onClick={() => setSubs(prev => {
                        const next = { ...prev, [k]: !prev[k] };
                        localStorage.setItem('wiib-chart-sub-' + k, next[k] ? '1' : '0');
                        return next;
                      })}>
                {k.toUpperCase()}
              </button>
            ))}
          </div>
        )}

        <DrawToolPicker tool={tool} onSelect={setTool} />

        {/* 历史成交标记开关：只有页面传了成交数据才出现 */}
        {tradeMarks != null && (
          <div className={group}>
            <button type="button"
                    onClick={() => {
                      const v = !showMarks;
                      setShowMarks(v);
                      localStorage.setItem('wiib-chart-trade-marks', v ? '1' : '0');
                    }}
                    className={iconCls(showMarks)} title={t('chart.marksTitle')}>
              <History className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* 新闻标记开关：只有词表内标的（传了 newsTag）才出现 */}
        {newsTag != null && (
          <div className={group}>
            <button type="button"
                    onClick={() => {
                      const v = !showNews;
                      setShowNews(v);
                      localStorage.setItem('wiib-chart-news', v ? '1' : '0');
                    }}
                    className={iconCls(showNews)} title={t('chart.newsTitle')}>
              <Globe className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* 仓位参考线开关：只有页面传了仓位数据才出现（现货/代币化美股页没有）。
            双开时每个仓位一个 chip，激活色跟方向色走，可单独藏掉某一边 */}
        {positionOverlays != null && (
          <div className={group}>
            <button type="button"
                    onClick={() => {
                      const v = !showPosLines;
                      setShowPosLines(v);
                      localStorage.setItem('wiib-chart-pos-lines', v ? '1' : '0');
                    }}
                    className={iconCls(showPosLines)} title={t('chart.posLinesTitle')}>
              <Layers className="w-3.5 h-3.5" />
            </button>
            {showPosLines && positionOverlays.length > 1 && positionOverlays.map(p => (
              <button key={p.id} type="button" className={chipCls(!hiddenPosIds.has(p.id))}
                      style={hiddenPosIds.has(p.id) ? undefined
                        : { color: p.side === 'LONG' ? '#0abf95' : '#ff5a68' }}
                      onClick={() => setHiddenPosIds(prev => {
                        const next = new Set(prev);
                        if (next.has(p.id)) next.delete(p.id); else next.add(p.id);
                        return next;
                      })}>
                {p.label}
              </button>
            ))}
          </div>
        )}

        <div className={group}>
          <button type="button" onClick={() => setMagnet(!magnet)} className={iconCls(magnet)}
                  title={magnet ? t('chart.magnetOn') : t('chart.magnetOff')}>
            <Magnet className="w-3.5 h-3.5" />
          </button>
          <button type="button" onClick={() => setHiddenAll(!hiddenAll)} disabled={!drawCount}
                  title={hiddenAll ? t('chart.drawingsHidden') : t('chart.drawingsHide')}
                  className={`${iconCls(hiddenAll)} disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground`}>
            {hiddenAll ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          </button>
          <button type="button" onClick={trash} disabled={!hasSelection && !drawCount}
                  title={hasSelection ? t('chart.deleteSelected') : t('chart.clearAll')}
                  className={`${iconCls(false)} disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground`}>
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>

        {indicators && (
          <div className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5"
               style={{ font: '700 11px/1.6 ui-monospace, Consolas, monospace' }}>
            <span ref={maLegendRef} />
            <span ref={emaLegendRef} />
            <span ref={bollLegendRef} />
          </div>
        )}

        <button type="button" onClick={fs.toggle} title={fs.active ? t('chart.exitFullscreen') : t('chart.fullscreen')}
                className={`ml-auto rounded-md border border-border ${iconCls(false)}`}>
          {fs.active ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
        </button>
      </div>

      <div ref={wrapRef} className="relative w-full flex-1 min-h-0">
        <div ref={chartDivRef} className="absolute inset-0" />
        {/* 文字标注输入。Esc 会先把锚点清掉，所以随后 unmount 触发的 blur→commit 是空转 */}
        {textEdit && (
          /* 透明浮层：文字直接浮在图上，所见即所得（提交后的标注就长这样）。
             只留一条虚线下划线当"这里在输入"的提示，亮暗主题各配可读的字色+反色描影 */
          <input autoFocus placeholder={t('chart.textPlaceholder')}
                 onKeyDown={e => {
                   if (e.key === 'Enter') commitText(e.currentTarget.value);
                   else if (e.key === 'Escape') cancelText();
                 }}
                 onBlur={e => commitText(e.currentTarget.value)}
                 style={{
                   position: 'absolute', left: textEdit.x, top: textEdit.y - 12, zIndex: 6, width: 200,
                   padding: '2px 0', border: 'none', outline: 'none',
                   background: 'transparent', borderBottom: '1px dashed rgba(41,98,255,.75)',
                   color: isDark ? '#e6e8ee' : '#17181a', caretColor: '#2962ff',
                   textShadow: isDark ? '0 1px 3px rgba(0,0,0,.9)' : '0 1px 3px rgba(255,255,255,.95)',
                   font: '600 12px/1.5 ui-monospace, Consolas, monospace',
                 }} />
        )}
        {/* 竖屏全屏的形状提示：Android 会被 orientation.lock 直接转过去（这条最多闪一下），
            iOS 靠它请用户动手。转到横屏 matchMedia 翻面，提示自动消失 */}
        {fs.active && portrait && IS_TOUCH && (
          <div className="absolute left-1/2 top-2 -translate-x-1/2 z-[5] px-2.5 py-1 rounded-md border border-border bg-background/90 text-[11px] font-semibold text-muted-foreground pointer-events-none whitespace-nowrap">
            {t('chart.rotate')}
          </div>
        )}
        {/* 翻历史提示（载入中 / 到底）。主图 pane 左上角是空的：叠加指标的读数条在图表外的工具条上 */}
        <div ref={hintRef} style={{
          position: 'absolute', left: 10, top: 6, display: 'none', pointerEvents: 'none', zIndex: 4,
          background: 'rgba(13,14,18,.82)', border: '1px solid #23262e', borderRadius: 6, padding: '2px 8px',
          font: '600 11px/1.5 ui-monospace, Consolas, monospace', color: '#a6abb6',
        }} />
        {/* 两块浮动面板统一灰白半透明+毛玻璃：黑底面板压在蜡烛上太沉 */}
        <div ref={tipRef} style={{
          position: 'absolute', display: 'none', pointerEvents: 'none', zIndex: 5,
          background: 'rgba(246,246,244,.88)', backdropFilter: 'blur(6px)',
          border: '1px solid rgba(0,0,0,.08)', borderRadius: 8, padding: '8px 11px',
          font: '12px/1.6 ui-monospace, Consolas, monospace', minWidth: 154, boxShadow: '0 6px 20px rgba(0,0,0,.18)',
        }} />
        {/* B/S 标记的点击弹窗：逐笔成交价（subscribeClick 填充） */}
        <div ref={markTipRef} style={{
          position: 'absolute', display: 'none', pointerEvents: 'none', zIndex: 6,
          background: 'rgba(246,246,244,.9)', backdropFilter: 'blur(6px)',
          border: '1px solid rgba(0,0,0,.08)', borderRadius: 8, padding: '7px 10px',
          font: '12px/1.7 ui-monospace, Consolas, monospace', minWidth: 130, boxShadow: '0 6px 20px rgba(0,0,0,.18)',
        }} />
        {/* 新闻图标的点击弹窗：时间+标题+摘要+源链接。链接要能点，pointerEvents 保持默认 */}
        <div ref={newsTipRef} style={{
          position: 'absolute', display: 'none', zIndex: 6,
          background: 'rgba(246,246,244,.94)', backdropFilter: 'blur(6px)',
          border: '1px solid rgba(0,0,0,.08)', borderRadius: 8, padding: '2px 12px',
          font: '12px/1.55 ui-monospace, Consolas, monospace', width: 280, maxHeight: 240,
          overflowY: 'auto', boxShadow: '0 6px 20px rgba(0,0,0,.18)',
        }} />
        {/* 「最新价 + 收盘倒计时」合体框：顶替原生最新价轴标签。
            左侧圆角贴轴（右缘与图表齐平）、价格行字号对齐轴刻度、倒计时行细线分隔，
            底色/文案/位置由 250ms 循环维护 */}
        <div ref={cdRef} style={{
          position: 'absolute', display: 'none', pointerEvents: 'none', zIndex: 4, right: 0,
          color: '#fff', textAlign: 'center', boxSizing: 'border-box', padding: '2px 5px',
          borderRadius: '4px 0 0 4px', boxShadow: '0 1px 6px rgba(0,0,0,.3)',
        }} />
      </div>
    </div>
  );
}
