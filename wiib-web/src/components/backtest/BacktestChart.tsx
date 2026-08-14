import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  createChart, createSeriesMarkers, CrosshairMode, CandlestickSeries,
  type DeepPartial, type HandleScrollOptions, type IChartApi, type ISeriesApi,
  type ISeriesMarkersPluginApi, type SeriesMarker, type Time, type UTCTimestamp,
} from 'lightweight-charts';
import { Eye, EyeOff, Magnet, Minus, MousePointer2, Slash, Trash2, Type } from 'lucide-react';
import { useIsDark } from '../../hooks/useIsDark';
import { useDrawings, type Tool } from '../chart/useDrawings';
import type { ChartCtx, OhlcBar } from '../../lib/chartDrawings';
import { fmtDateTime } from '../../lib/utils';

/** 与 CandleChart 同款时区约定：横轴按 UTC+8 显示且 bar 边界对齐 */
const TZ = -8 * 3600;
const toBarTime = (ms: number) => (Math.floor(ms / 1000) - TZ) as UTCTimestamp;
/** 与 CandleChart 同款：手机纵向滑动交还给页面滚动，横向平移/捏合缩放保留 */
const SCROLL_OPTS: DeepPartial<HandleScrollOptions> =
  { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false };

/** 通用成交标记：模式1传回测 trades，模式2传复盘成交（未平仓时 close* 缺省只画开仓箭头） */
export interface ChartTradeMark {
  openBarIndex: number;
  openTime: number;          // ms
  side: 'LONG' | 'SHORT';
  closeBarIndex?: number;
  closeTime?: number;
  pnl?: number;
  /** 出场标记文字（模式1=出场原因，模式2=平仓/爆仓） */
  exitLabel?: string;
}

interface Props {
  /** 全量 K 线（含预热/上下文段），行 = [openTime, open, high, low, close, volume] */
  bars: number[][];
  marks: ChartTradeMark[];
  /** 显示到的 bar 数（回放游标）；= bars.length 即全量 */
  cursor: number;
  /** 画线存档按 symbol 走，与实盘 Coin 图共用同一套（锚点是绝对时间，天然对齐） */
  symbol: string;
  decimals?: number;
  /** 不传：桌面 420，小屏 min(52vh, 460) */
  height?: number;
  /** 盲测：传回放段首根 openTime(ms)，时间轴/十字线/画线标签显示 D{n} HH:mm 相对时间 */
  blindBaseMs?: number | null;
  /** bar 周期秒数（画线磁吸/命中检测的桶宽），bars 聚合到几分钟就传几分钟；缺省 5m */
  bucketSec?: number;
}

/** 画线工具条按钮（与 CandleChart 同套图标语义） */
const TOOL_BTNS: { k: Tool; icon: ReactNode; title: string }[] = [
  { k: null, icon: <MousePointer2 className="w-3.5 h-3.5" />, title: '选择/拖拽（Esc 取消选中，Del 删除）' },
  { k: 'trend', icon: <Slash className="w-3.5 h-3.5" />, title: '趋势线：点两下定两端' },
  { k: 'hline', icon: <Minus className="w-3.5 h-3.5" />, title: '水平线：点一下即成' },
  { k: 'fib', icon: <span className="text-[10px] font-extrabold leading-none tracking-tight">FIB</span>, title: '斐波那契回撤：点两下定 0/1 两端' },
  { k: 'text', icon: <Type className="w-3.5 h-3.5" />, title: '文字标注：点一下再输入' },
];

/** 行 → LWC 蜡烛点 */
function toCandle(row: number[]) {
  return { time: toBarTime(row[0]), open: row[1], high: row[2], low: row[3], close: row[4] };
}

/** 行 → 画线层 OhlcBar（time 为图表口径的秒） */
function toOhlc(row: number[]): OhlcBar {
  return { time: toBarTime(row[0]), open: row[1], high: row[2], low: row[3], close: row[4] };
}

/** 盲测相对时间：D{第几天} HH:mm（HH:mm 为 UTC+8 时刻，不泄露日期） */
function blindLabel(shiftedSec: number, baseShiftedSec: number): string {
  const day = Math.floor((shiftedSec - baseShiftedSec) / 86_400) + 1;
  const d = new Date(shiftedSec * 1000);
  const hh = String(d.getUTCHours()).padStart(2, '0');
  const mm = String(d.getUTCMinutes()).padStart(2, '0');
  return `D${day} ${hh}:${mm}`;
}

/**
 * 回测/复盘通用蜡烛图：进出场 markers + 前端回放游标 + 画线工具（全站 DrawingLayer 复用）。
 * 游标小步前进走 update() 增量追加，跳变/回退走 setData() 重切——两条路径都不重建图表。
 */
export function BacktestChart({ bars, marks, cursor, symbol, decimals = 2, height, blindBaseMs, bucketSec = 300 }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const markersRef = useRef<ISeriesMarkersPluginApi<Time> | null>(null);
  const drawnRef = useRef(0);           // 已画到的 bar 数
  const lastBarsRef = useRef<number[][] | null>(null);   // bars 换引用（新任务/新分段）必须走全量重切
  const markerCountRef = useRef(-1);    // 上次 setMarkers 的条数，变了才重设
  // 画线层的只读上下文：随游标同步维护（图层活得比每帧都久，必须走 ref getter）
  const ohlcRef = useRef<OhlcBar[]>([]);
  const idxRef = useRef<Map<number, number>>(new Map());
  const blindBaseRef = useRef<number | null>(null);
  blindBaseRef.current = blindBaseMs ?? null;
  const isDark = useIsDark();

  const {
    attach: attachDrawings, tool, setTool, magnet, setMagnet, hiddenAll, setHiddenAll,
    selected: hasSelection, count: drawCount, trash, textEdit, commitText, cancelText,
  } = useDrawings();

  // 小屏（手机竖屏）默认矮一点，给下方操作按钮留出手指空间
  const [autoHeight] = useState(() => window.innerWidth < 768
    ? Math.min(Math.round(window.innerHeight * 0.52), 460) : 420);
  const h = height ?? autoHeight;

  /** 图表口径秒 → 轴/十字线标签。盲测走相对天序号，正常走真实时间 */
  const fmtShifted = (sec: number) => {
    const base = blindBaseRef.current;
    if (base != null) return blindLabel(sec, Math.floor(base / 1000) - TZ);
    return fmtDateTime((sec + TZ) * 1000);
  };

  // markers 预排序：进场按开仓 bar、出场按平仓 bar，游标推进时按可见数量切片
  const allMarkers = useMemo(() => {
    const gain = isDark ? '#0abf95' : '#089981';
    const loss = isDark ? '#ff5a68' : '#f23645';
    const out: { atBar: number; marker: SeriesMarker<Time> }[] = [];
    for (const t of marks) {
      const isLong = t.side === 'LONG';
      out.push({
        atBar: t.openBarIndex,
        marker: {
          time: toBarTime(t.openTime), position: isLong ? 'belowBar' : 'aboveBar',
          shape: isLong ? 'arrowUp' : 'arrowDown', color: isLong ? gain : loss,
          text: isLong ? '多' : '空',
        },
      });
      if (t.closeBarIndex != null && t.closeTime != null) {
        out.push({
          atBar: t.closeBarIndex,
          marker: {
            time: toBarTime(t.closeTime), position: isLong ? 'aboveBar' : 'belowBar',
            shape: 'circle', color: (t.pnl ?? 0) >= 0 ? gain : loss,
            text: t.exitLabel,
          },
        });
      }
    }
    return out.sort((a, b) => a.atBar - b.atBar);
  }, [marks, isDark]);

  // 建图（主题/高度/币种变化时重建，颜色 token 才能生效；画线层 attach/detach 同一 effect 成对做）
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = createChart(el, {
      width: el.clientWidth,
      height: h,
      layout: {
        background: { color: 'transparent' },
        textColor: isDark ? '#878b96' : '#71737b',
        fontSize: 11,
        attributionLogo: false,
      },
      grid: {
        vertLines: { color: isDark ? 'rgba(135,139,150,.08)' : 'rgba(113,115,123,.10)' },
        horzLines: { color: isDark ? 'rgba(135,139,150,.08)' : 'rgba(113,115,123,.10)' },
      },
      crosshair: { mode: CrosshairMode.Normal },
      handleScroll: SCROLL_OPTS,
      rightPriceScale: { borderVisible: false },
      timeScale: {
        borderVisible: false, timeVisible: true, secondsVisible: false,
        // 盲测时间脱敏在 tick 一层做：真实日期不上轴
        tickMarkFormatter: (time: Time) => {
          const base = blindBaseRef.current;
          return base == null ? null : blindLabel(time as number, Math.floor(base / 1000) - TZ);
        },
      },
      localization: {
        timeFormatter: (time: Time) => fmtShifted(time as number),
      },
    });
    const series = chart.addSeries(CandlestickSeries, {
      upColor: isDark ? '#0abf95' : '#089981',
      downColor: isDark ? '#ff5a68' : '#f23645',
      borderVisible: false,
      wickUpColor: isDark ? '#0abf95' : '#089981',
      wickDownColor: isDark ? '#ff5a68' : '#f23645',
      priceFormat: { type: 'price', precision: decimals, minMove: 1 / 10 ** decimals },
    });
    chartRef.current = chart;
    seriesRef.current = series;
    markersRef.current = createSeriesMarkers(series, []);
    drawnRef.current = 0;
    markerCountRef.current = -1;
    ohlcRef.current = [];
    idxRef.current = new Map();

    const detachDrawings = attachDrawings({
      chart, series, host: el, symbol, decimals, scrollOpts: SCROLL_OPTS,
      ctx: {
        bars: () => ohlcRef.current,
        idx: () => idxRef.current,
        bucketSec,
        timeScale: chart.timeScale(),
        series,
      } satisfies ChartCtx,
      fmtTime: fmtShifted,
    });

    const onResize = () => chart.applyOptions({ width: el.clientWidth });
    const ro = new ResizeObserver(onResize);
    ro.observe(el);
    return () => {
      ro.disconnect();
      detachDrawings();
      chart.remove();
      chartRef.current = null;
      seriesRef.current = null;
      markersRef.current = null;
    };
    // fmtShifted/attachDrawings 稳定（ref/useCallback），不进依赖；bucketSec 变化（切周期）需重建图重挂画线层
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDark, h, symbol, decimals, bucketSec]);

  // 游标应用：小步前进 update 追加；回退/跳变 setData 重切。画线层的 bars/idx 同步维护
  useEffect(() => {
    const series = seriesRef.current;
    const chart = chartRef.current;
    if (!series || !chart) return;
    const target = Math.min(Math.max(cursor, 0), bars.length);
    const drawn = drawnRef.current;
    const sameBars = lastBarsRef.current === bars;

    if (sameBars && target === drawn && drawn !== 0) {
      // 数据没动
    } else if (sameBars && target > drawn && target - drawn <= 600 && drawn > 0) {
      for (let i = drawn; i < target; i++) {
        series.update(toCandle(bars[i]));
        const o = toOhlc(bars[i]);
        idxRef.current.set(o.time, ohlcRef.current.length);
        ohlcRef.current.push(o);
      }
    } else {
      series.setData(bars.slice(0, target).map(toCandle));
      const ohlc: OhlcBar[] = new Array(target);
      const idx = new Map<number, number>();
      for (let i = 0; i < target; i++) {
        ohlc[i] = toOhlc(bars[i]);
        idx.set(ohlc[i].time, i);
      }
      ohlcRef.current = ohlc;
      idxRef.current = idx;
      // 只在"从空到有"那一下自适应视野；分段追加/回放重切都保留用户当前缩放
      if (drawn === 0 && target > 0) chart.timeScale().fitContent();
    }
    drawnRef.current = target;
    lastBarsRef.current = bars;

    // 可见 markers：开/平仓 bar 已入画面才显示
    const visible: SeriesMarker<Time>[] = [];
    for (const m of allMarkers) {
      if (m.atBar < target) visible.push(m.marker);
      else break;
    }
    if (visible.length !== markerCountRef.current) {
      markersRef.current?.setMarkers(visible);
      markerCountRef.current = visible.length;
    }
  }, [bars, cursor, allMarkers]);

  const iconCls = (on: boolean) =>
    `px-2 py-1.5 flex items-center justify-center transition-colors cursor-pointer ${
      on ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
         : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`;

  return (
    <div className="space-y-1.5">
      {/* 画线工具行 */}
      <div className="flex items-center gap-2 flex-wrap">
        <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
          {TOOL_BTNS.map(b => (
            <button key={b.k ?? 'pick'} type="button" title={b.title}
              onClick={() => setTool(b.k)} className={iconCls(tool === b.k)}>
              {b.icon}
            </button>
          ))}
        </div>
        <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border">
          <button type="button" onClick={() => setMagnet(!magnet)} className={iconCls(magnet)}
            title={magnet ? '磁吸开：端点自动贴住最近的开/高/低/收' : '磁吸关：自由落点'}>
            <Magnet className="w-3.5 h-3.5" />
          </button>
          <button type="button" onClick={() => setHiddenAll(!hiddenAll)} disabled={!drawCount}
            title={hiddenAll ? '画线已隐藏，点击恢复显示' : '隐藏当前所有画线（不删除）'}
            className={`${iconCls(hiddenAll)} disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground`}>
            {hiddenAll ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
          </button>
          <button type="button" onClick={trash} disabled={!hasSelection && !drawCount}
            title={hasSelection ? '删除选中（Del）' : '清空本币种全部画线'}
            className={`${iconCls(false)} disabled:opacity-30 disabled:cursor-default disabled:hover:bg-transparent disabled:hover:text-muted-foreground`}>
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* 图表主体（relative：文字标注输入浮层的定位基准） */}
      <div className="relative w-full" style={{ height: h }}>
        <div ref={containerRef} className="absolute inset-0" />
        {textEdit && (
          <input autoFocus placeholder="标注文字，回车确认"
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
      </div>
    </div>
  );
}
