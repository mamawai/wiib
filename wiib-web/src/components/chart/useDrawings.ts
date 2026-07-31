/**
 * 画线交互层：工具选择 → 落点 → 选中 → 拖拽 → 删除 → 存盘。
 *
 * 两个必须说清楚的设计：
 *
 * 1. **为什么用 pointerdown 而不是 mousedown**
 *    LWC 内部绑的是 mousedown/touchstart，挂在它自己的 canvas 上。想"抢"手势，
 *    靠 stopPropagation 是抢不到的（事件类型都不同）。真正管用的是：规范保证
 *    pointerdown 先于 mousedown/touchstart 触发，所以在 pointerdown 里把
 *    handleScroll/handleScale 关掉，等 LWC 收到它那份事件时平移已经被禁了。
 *    手机上再补一手 touchAction:'none'，否则拖线会带着整页一起滚。
 *
 * 2. **为什么 attach 是手动调用而不是自己开 effect**
 *    图表本体在 CandleChart 的建图 effect 里创建/销毁。若这里另开一个同依赖的
 *    effect，React 会先跑建图 effect 的 cleanup(chart.remove())、再跑这里的，
 *    那时 series 已经死了，detachPrimitive 要炸。所以把 attach/detach 交给
 *    CandleChart 在同一个 effect 内按正确顺序调。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { DeepPartial, HandleScrollOptions, IChartApi, ISeriesApi } from 'lightweight-charts';
import { DrawingLayer } from './DrawingLayer';
import {
  coordToTime, DRAW_COLOR, loadDrawings, magnetPrice, newId, saveDrawings,
  type Anchor, type ChartCtx, type Drawing, type DrawingKind,
} from '../../lib/chartDrawings';

/** null = 选择模式（可选中/拖拽已有图形，图表照常平移缩放） */
export type Tool = DrawingKind | null;

export interface AttachArgs {
  chart: IChartApi;
  series: ISeriesApi<'Candlestick'>;
  /** 图表宿主 div(absolute inset-0)：指针事件挂它，它的 rect 也是文字浮层的定位基准 */
  host: HTMLDivElement;
  symbol: string;
  ctx: ChartCtx;
  decimals: number;
  fmtTime: (t: number) => string;
  /** 建图时那份滚动配置，拖拽结束后原样恢复 */
  scrollOpts: DeepPartial<HandleScrollOptions>;
}

interface Live extends AttachArgs {
  layer: DrawingLayer;
  /** pane0 的画布，clientX/Y → pane 局部坐标靠它；顺带把副图/价格轴天然排除在外 */
  paneBox: HTMLElement | null;
}

/** 拖拽会话。t0/p0 是按下那一刻的自由坐标，整体平移按它算增量 */
interface Drag { id: string; pt: number; t0: number; p0: number; orig: Anchor[]; }

export function useDrawings() {
  const [tool, setTool] = useState<Tool>(null);
  const [magnet, setMagnet] = useState(true);
  const [selected, setSelected] = useState(false);
  const [count, setCount] = useState(0);
  /** 文字标注输入浮层的位置(相对 host)，null=没在输入 */
  const [textEdit, setTextEdit] = useState<{ x: number; y: number } | null>(null);

  const liveRef = useRef<Live | null>(null);
  const toolRef = useRef<Tool>(null);
  const magnetRef = useRef(true);
  const dragRef = useRef<Drag | null>(null);
  /** 拖拽期挂在 window 上的那对监听，存下来才摘得掉（add/remove 必须同一个函数对象） */
  const dragHandlersRef = useRef<{ move: (e: PointerEvent) => void; up: () => void } | null>(null);
  /** trend/fib 已落的第一点，等第二点 */
  const firstRef = useRef<Anchor | null>(null);
  const textAnchorRef = useRef<Anchor | null>(null);

  useEffect(() => { magnetRef.current = magnet; }, [magnet]);

  // ---------- 基础换算 ----------
  // 下面这些 helper 一律 useCallback([])：它们只读 ref 和稳定 setter，本就没有响应式依赖。
  // 稳定是硬要求 —— 指针handler 要靠同一个函数对象 add/removeEventListener 配对，
  // 一旦每次渲染换新引用，卸载时就摘不掉旧监听。

  /** clientX/Y → pane 局部坐标。inside=false 表示落在副图/价格轴上，不归画线管 */
  const localPt = useCallback((live: Live, e: PointerEvent) => {
    if (!live.paneBox) {
      const el = live.chart.panes()[0]?.getHTMLElement() ?? null;
      // pane 的 DOM 是渲染流程里才建的(同 CandleChart makeLegend 的注释)，所以懒取
      live.paneBox = el?.querySelector('canvas') ?? el;
    }
    const r = live.paneBox?.getBoundingClientRect();
    if (!r) return null;
    const x = e.clientX - r.left, y = e.clientY - r.top;
    return { x, y, inside: x >= 0 && x <= r.width && y >= 0 && y <= r.height };
  }, []);

  /** 像素 → 锚点，磁吸开着就顺手吸到最近的 OHLC */
  const anchorAt = useCallback((live: Live, x: number, y: number): { a: Anchor; snapped: boolean } | null => {
    const t = coordToTime(x, live.ctx);
    if (t === null) return null;
    if (!magnetRef.current) {
      const p = live.ctx.series.coordinateToPrice(y);
      return p === null ? null : { a: { t, p }, snapped: false };
    }
    const m = magnetPrice(t, y, live.ctx);
    return m === null ? null : { a: { t, p: m.p }, snapped: m.snapped };
  }, []);

  /** 锁住图表自身的平移缩放，把手势让给画线；解锁时恢复建图时那份配置 */
  const lock = useCallback((live: Live, on: boolean) => {
    live.chart.applyOptions({ handleScroll: on ? false : live.scrollOpts, handleScale: !on });
    live.host.style.touchAction = on ? 'none' : '';
  }, []);

  const persist = useCallback((live: Live) => {
    saveDrawings(live.symbol, live.layer.drawings);
    setCount(live.layer.drawings.length);
  }, []);

  /** 一次绘制结束（无论落定还是取消）：清预览、退回选择模式 */
  const endDraw = useCallback((live: Live) => {
    firstRef.current = null;
    live.layer.pending = null;
    live.layer.snap = null;
    setTool(null);
    live.layer.update();
  }, []);

  const commit = useCallback((live: Live, kind: DrawingKind, pts: Anchor[], text?: string) => {
    const d: Drawing = { id: newId(), kind, pts, color: DRAW_COLOR, ...(text ? { text } : {}) };
    live.layer.drawings.push(d);
    live.layer.selectedId = d.id;
    setSelected(true);
    persist(live);
    live.layer.update();
  }, [persist]);

  const dropSelected = useCallback((live: Live) => {
    const L = live.layer;
    L.drawings = L.drawings.filter(d => d.id !== L.selectedId);
    L.selectedId = null;
    setSelected(false);
    persist(live);
    L.update();
  }, [persist]);

  // ---------- 指针事件 ----------

  /** 摘掉拖拽期挂在 window 上的三个监听。单独抽出来，省得 onWinUp 自引用 */
  const stopDrag = useCallback(() => {
    const h = dragHandlersRef.current;
    if (!h) return;
    window.removeEventListener('pointermove', h.move, true);
    window.removeEventListener('pointerup', h.up, true);
    window.removeEventListener('pointercancel', h.up, true);
    dragHandlersRef.current = null;
  }, []);

  const onWinMove = useCallback((e: PointerEvent) => {
    const live = liveRef.current, drag = dragRef.current;
    if (!live || !drag) return;
    const pt = localPt(live, e);
    if (!pt) return;
    const L = live.layer;
    const d = L.drawings.find(x => x.id === drag.id);
    if (!d) return;

    if (drag.pt >= 0) {
      const r = anchorAt(live, pt.x, pt.y);
      if (!r) return;
      d.pts[drag.pt] = r.a;
      L.snap = r.snapped ? r.a : null;
    } else {
      // 整体平移不吸附：每根都吸会拖得一跳一跳。横向仍按整根 bar 走(coordToTime 已取整)
      const t = coordToTime(pt.x, live.ctx), p = live.ctx.series.coordinateToPrice(pt.y);
      if (t === null || p === null) return;
      const dt = t - drag.t0, dp = p - drag.p0;
      d.pts = drag.orig.map(a => ({ t: a.t + dt, p: a.p + dp }));
      L.snap = null;
    }
    L.update();
  }, [localPt, anchorAt]);

  const onWinUp = useCallback(() => {
    stopDrag();
    const live = liveRef.current;
    const had = dragRef.current !== null;
    dragRef.current = null;
    if (!live) return;
    if (had) persist(live);
    live.layer.snap = null;
    lock(live, toolRef.current !== null);   // 还在绘制模式就继续锁着
    live.layer.update();
  }, [stopDrag, persist, lock]);

  /** 悬停预览：只在"选了工具"或"画到一半"时接管，其余交给 LWC 走十字线 */
  const onMove = useCallback((e: PointerEvent) => {
    const live = liveRef.current;
    if (!live || (!toolRef.current && !firstRef.current)) return;
    const pt = localPt(live, e);
    if (!pt) return;
    const L = live.layer;
    if (!pt.inside) { if (L.snap) { L.snap = null; L.update(); } return; }
    const r = anchorAt(live, pt.x, pt.y);
    if (!r) return;
    L.snap = r.snapped ? r.a : null;
    if (firstRef.current && L.pending) L.pending.pts[1] = r.a;
    L.update();
  }, [localPt, anchorAt]);

  const onDown = useCallback((e: PointerEvent) => {
    const live = liveRef.current;
    if (!live) return;
    const pt = localPt(live, e);
    if (!pt || !pt.inside) return;
    const L = live.layer, t = toolRef.current;

    if (t) {
      const r = anchorAt(live, pt.x, pt.y);
      if (!r) return;
      e.preventDefault();                      // 压掉兼容鼠标事件，LWC 的 mousedown 不会触发
      if (t === 'hline') { commit(live, 'hline', [r.a]); endDraw(live); return; }
      if (t === 'text') {
        const hr = live.host.getBoundingClientRect();
        textAnchorRef.current = r.a;
        setTextEdit({ x: e.clientX - hr.left, y: e.clientY - hr.top });
        endDraw(live);
        return;
      }
      if (!firstRef.current) {                 // trend/fib 第一点：起预览
        firstRef.current = r.a;
        L.pending = { id: '_pending', kind: t, pts: [r.a, r.a], color: DRAW_COLOR };
        L.snap = r.snapped ? r.a : null;
        L.update();
      } else {
        commit(live, t, [firstRef.current, r.a]);
        endDraw(live);
      }
      return;
    }

    // 选择模式：主动 hitTest 而不是等 LWC 的 hover —— 触摸端没有 hover 前置态
    const hit = L.pick(pt.x, pt.y);
    if (!hit) {
      if (L.selectedId) { L.selectedId = null; setSelected(false); L.update(); }
      return;                                  // 没点中就放行，图表照常平移缩放
    }
    e.preventDefault();
    lock(live, true);
    L.selectedId = hit.id;
    setSelected(true);
    const d = L.drawings.find(x => x.id === hit.id);
    const t0 = coordToTime(pt.x, live.ctx), p0 = live.ctx.series.coordinateToPrice(pt.y);
    dragRef.current = (d && t0 !== null && p0 !== null)
      ? { id: hit.id, pt: hit.pt, t0, p0, orig: d.pts.map(a => ({ ...a })) } : null;
    // 挂 window 而不是 host：手指/鼠标拖出图表范围也要跟得住
    const h = { move: onWinMove, up: onWinUp };
    dragHandlersRef.current = h;
    window.addEventListener('pointermove', h.move, true);
    window.addEventListener('pointerup', h.up, true);
    window.addEventListener('pointercancel', h.up, true);
    L.update();
  }, [localPt, anchorAt, lock, commit, endDraw, onWinMove, onWinUp]);

  const onKey = useCallback((e: KeyboardEvent) => {
    const live = liveRef.current;
    if (!live) return;
    const el = document.activeElement;
    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) return;   // 正在打字，别抢键
    if (e.key === 'Escape') {
      if (firstRef.current || toolRef.current) endDraw(live);
      else if (live.layer.selectedId) { live.layer.selectedId = null; setSelected(false); live.layer.update(); }
      return;
    }
    if ((e.key === 'Delete' || e.key === 'Backspace') && live.layer.selectedId) {
      e.preventDefault();
      dropSelected(live);
    }
  }, [endDraw, dropSelected]);

  // ---------- 挂载 / 卸载 ----------

  /** 由 CandleChart 在建图 effect 内调用，返回 detach（必须在 chart.remove() 之前调） */
  const attach = useCallback((a: AttachArgs) => {
    const layer = new DrawingLayer(a.ctx, { decimals: a.decimals, fmtTime: a.fmtTime });
    layer.drawings = loadDrawings(a.symbol);
    layer.interactive = true;
    a.series.attachPrimitive(layer);
    const live: Live = { ...a, layer, paneBox: null };
    liveRef.current = live;

    setTool(null);                     // 换 symbol/周期时不该还端着上一手的笔
    setSelected(false);
    setCount(layer.drawings.length);
    setTextEdit(null);
    textAnchorRef.current = null;
    firstRef.current = null;

    a.host.addEventListener('pointerdown', onDown, true);
    a.host.addEventListener('pointermove', onMove, true);
    window.addEventListener('keydown', onKey);

    return () => {
      a.host.removeEventListener('pointerdown', onDown, true);
      a.host.removeEventListener('pointermove', onMove, true);
      window.removeEventListener('keydown', onKey);
      // 卸载时可能正拖着：只摘监听，别走 onWinUp（那会去碰马上要被 remove 的 chart）
      stopDrag();
      dragRef.current = null;
      a.host.style.touchAction = '';
      a.host.style.cursor = '';
      a.series.detachPrimitive(layer);
      liveRef.current = null;
    };
  }, [onDown, onMove, onKey, stopDrag]);

  // 工具切换：锁图表、改光标、关掉命中判定（画新线时不该被旧线抢走光标）
  useEffect(() => {
    toolRef.current = tool;
    const live = liveRef.current;
    if (!live) return;
    live.layer.interactive = tool === null;
    live.host.style.cursor = tool ? 'crosshair' : '';
    lock(live, tool !== null);
    if (!tool) { firstRef.current = null; live.layer.pending = null; live.layer.snap = null; }
    live.layer.update();
  }, [tool, lock]);

  // ---------- 工具条动作 ----------

  /** 有选中删选中；没选中则清空全部（问一句，一键抹掉几十条太狠） */
  const trash = useCallback(() => {
    const live = liveRef.current;
    if (!live) return;
    const L = live.layer;
    if (L.selectedId) { dropSelected(live); return; }
    if (!L.drawings.length) return;
    if (!window.confirm(`清空 ${live.symbol} 的全部 ${L.drawings.length} 条画线？`)) return;
    L.drawings = [];
    persist(live);
    L.update();
  }, [dropSelected, persist]);

  const commitText = useCallback((v: string) => {
    const live = liveRef.current, a = textAnchorRef.current;
    setTextEdit(null);
    textAnchorRef.current = null;
    if (live && a && v.trim()) commit(live, 'text', [a], v.trim());
  }, [commit]);

  const cancelText = useCallback(() => {
    setTextEdit(null);
    textAnchorRef.current = null;
  }, []);

  return {
    attach,
    tool, setTool,
    magnet, setMagnet,
    /** 当前有选中的图形 → 🗑 按钮是"删选中"，否则是"清空全部" */
    selected,
    count,
    trash,
    textEdit, commitText, cancelText,
  };
}
