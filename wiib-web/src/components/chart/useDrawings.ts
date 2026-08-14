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

/** 触屏设备：画线改走"中心锚点拖动+点击固定"模式（参考币安 App），手指不再直接点图落点 */
const IS_COARSE = window.matchMedia('(pointer: coarse)').matches;

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
  /** 隐藏全部画线（只切可见性不删数据；不持久化，进页面默认显示） */
  const [hiddenAll, setHiddenAll] = useState(false);
  /** 文字标注输入浮层的位置(相对 host)，null=没在输入 */
  const [textEdit, setTextEdit] = useState<{ x: number; y: number } | null>(null);

  const liveRef = useRef<Live | null>(null);
  const toolRef = useRef<Tool>(null);
  const magnetRef = useRef(true);
  const hiddenRef = useRef(false);
  const dragRef = useRef<Drag | null>(null);
  /** 拖拽期挂在 window 上的那对监听，存下来才摘得掉（add/remove 必须同一个函数对象） */
  const dragHandlersRef = useRef<{ move: (e: PointerEvent) => void; up: () => void } | null>(null);
  /** trend/fib 已落的第一点，等第二点 */
  const firstRef = useRef<Anchor | null>(null);
  const textAnchorRef = useRef<Anchor | null>(null);
  /** 移动端中心锚点会话：el=锚点 DOM，ptIndex=正在放第几个点，x/y=锚点中心(host 坐标) */
  const mobileRef = useRef<{ el: HTMLDivElement; ptIndex: 0 | 1; x: number; y: number } | null>(null);

  useEffect(() => { magnetRef.current = magnet; }, [magnet]);

  // 眼睛开关：同步图层 + 清选中都在事件回调里做（不进 effect，避免级联渲染）
  const setHiddenAllSync = useCallback((v: boolean) => {
    setHiddenAll(v);
    hiddenRef.current = v;
    const live = liveRef.current;
    if (!live) return;
    live.layer.hidden = v;
    if (v && live.layer.selectedId) {
      live.layer.selectedId = null;   // 看不见的线不该保持选中态
      setSelected(false);
    }
    live.layer.update();
  }, []);

  // ---------- 基础换算 ----------
  // 下面这些 helper 一律 useCallback([])：它们只读 ref 和稳定 setter，本就没有响应式依赖。
  // 稳定是硬要求 —— 指针handler 要靠同一个函数对象 add/removeEventListener 配对，
  // 一旦每次渲染换新引用，卸载时就摘不掉旧监听。

  /** clientX/Y → pane 局部坐标。inside=false 表示落在副图/价格轴上，不归画线管 */
  const localPt = useCallback((live: Live, clientX: number, clientY: number) => {
    if (!live.paneBox) {
      const el = live.chart.panes()[0]?.getHTMLElement() ?? null;
      // pane 的 DOM 是渲染流程里才建的(同 CandleChart makeLegend 的注释)，所以懒取
      live.paneBox = el?.querySelector('canvas') ?? el;
    }
    const r = live.paneBox?.getBoundingClientRect();
    if (!r) return null;
    const x = clientX - r.left, y = clientY - r.top;
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

  // ---------- 移动端中心锚点放置（参考币安 App）----------
  //
  // 触屏上选中画线工具后：屏幕中心出现一个锚点，用户**只能拖这个锚点**（图表已锁死），
  // 拖到位后轻点一下即固定当前点；两点工具在固定处再生成第二个锚点重复一次；
  // 全部固定后页面中央提示"已完成"并退回选择模式。

  /** 锚点元素中心(host 坐标) → 图层锚点（带磁吸）。悬在副图/价格轴上返回 null */
  const mobileAnchorAt = useCallback((live: Live, hostX: number, hostY: number) => {
    const hr = live.host.getBoundingClientRect();
    const pt = localPt(live, hr.left + hostX, hr.top + hostY);
    if (!pt || !pt.inside) return null;
    return anchorAt(live, pt.x, pt.y);
  }, [localPt, anchorAt]);

  const destroyMobileAnchor = useCallback(() => {
    const m = mobileRef.current;
    if (!m) return;
    m.el.remove();
    mobileRef.current = null;
  }, []);

  /** 中央"已完成"提示：淡入停留后自删，纯装饰不进 React 树 */
  const flashDone = useCallback((host: HTMLElement) => {
    const tip = document.createElement('div');
    tip.textContent = '已完成';
    Object.assign(tip.style, {
      position: 'absolute', left: '50%', top: '50%', transform: 'translate(-50%,-50%)',
      zIndex: '8', padding: '8px 18px', borderRadius: '10px',
      background: 'rgba(23,24,26,.82)', color: '#fff',
      font: '700 14px/1 system-ui, sans-serif', letterSpacing: '.05em',
      opacity: '0', transition: 'opacity .18s ease', pointerEvents: 'none',
    } as Partial<CSSStyleDeclaration>);
    host.appendChild(tip);
    requestAnimationFrame(() => { tip.style.opacity = '1'; });
    setTimeout(() => {
      tip.style.opacity = '0';
      setTimeout(() => tip.remove(), 220);
    }, 900);
  }, []);

  // spawn ↔ confirm 互相引用（第二个点由 confirm 再 spawn），走 ref 断环
  const spawnMobileRef = useRef<(live: Live, ptIndex: 0 | 1, atX?: number, atY?: number) => void>(() => {});

  const spawnMobileAnchor = useCallback((live: Live, ptIndex: 0 | 1, atX?: number, atY?: number) => {
    destroyMobileAnchor();
    const host = live.host;
    const el = document.createElement('div');
    // 40×40 触区：外圈环 + 中心点 + 四向短十字线，够手指抓也不挡蜡烛
    Object.assign(el.style, {
      position: 'absolute', left: '0', top: '0', width: '40px', height: '40px',
      zIndex: '7', touchAction: 'none', cursor: 'grab',
    } as Partial<CSSStyleDeclaration>);
    el.innerHTML =
      '<div style="position:absolute;inset:4px;border:2px solid #2962ff;border-radius:50%;background:rgba(41,98,255,.10);box-shadow:0 1px 6px rgba(0,0,0,.3)"></div>'
      + '<div style="position:absolute;left:50%;top:50%;width:5px;height:5px;margin:-2.5px 0 0 -2.5px;border-radius:50%;background:#2962ff"></div>'
      + '<div style="position:absolute;left:50%;top:-6px;width:2px;height:9px;margin-left:-1px;background:#2962ff"></div>'
      + '<div style="position:absolute;left:50%;bottom:-6px;width:2px;height:9px;margin-left:-1px;background:#2962ff"></div>'
      + '<div style="position:absolute;top:50%;left:-6px;height:2px;width:9px;margin-top:-1px;background:#2962ff"></div>'
      + '<div style="position:absolute;top:50%;right:-6px;height:2px;width:9px;margin-top:-1px;background:#2962ff"></div>';
    const x0 = atX ?? host.clientWidth / 2;
    const y0 = atY ?? host.clientHeight / 2;
    const place = (x: number, y: number) => { el.style.transform = `translate(${x - 20}px, ${y - 20}px)`; };
    place(x0, y0);
    host.appendChild(el);
    const m = { el, ptIndex, x: x0, y: y0 };
    mobileRef.current = m;

    /** 锚点位置变化 → 磁吸提示 + 第二点预览线实时跟随 */
    const sync = () => {
      const r = mobileAnchorAt(live, m.x, m.y);
      const L = live.layer;
      L.snap = r?.snapped ? r.a : null;
      if (r && m.ptIndex === 1 && L.pending) L.pending.pts[1] = r.a;
      L.update();
    };
    sync();

    let dragging = false, moved = false, startCX = 0, startCY = 0, baseX = 0, baseY = 0;
    el.addEventListener('pointerdown', ev => {
      ev.preventDefault();
      ev.stopPropagation();
      el.setPointerCapture(ev.pointerId);
      dragging = true;
      moved = false;
      startCX = ev.clientX;
      startCY = ev.clientY;
      baseX = m.x;
      baseY = m.y;
    });
    el.addEventListener('pointermove', ev => {
      if (!dragging) return;
      const dx = ev.clientX - startCX, dy = ev.clientY - startCY;
      if (Math.hypot(dx, dy) > 6) moved = true;   // 超过抖动阈值才算拖动，避免"点一下"被误判
      m.x = Math.min(Math.max(baseX + dx, 0), host.clientWidth);
      m.y = Math.min(Math.max(baseY + dy, 0), host.clientHeight);
      place(m.x, m.y);
      sync();
    });
    const finishDrag = () => {
      if (!dragging) return;
      dragging = false;
      if (moved) return;                          // 拖动松手：只定位不固定
      // 轻点 = 固定当前点
      const r = mobileAnchorAt(live, m.x, m.y);
      if (!r) return;                             // 悬在价格轴/副图上，点了不算
      const t = toolRef.current;
      if (!t) { destroyMobileAnchor(); return; }
      if (t === 'hline') {
        commit(live, 'hline', [r.a]);
        destroyMobileAnchor();
        flashDone(host);
        endDraw(live);
        return;
      }
      if (t === 'text') {
        textAnchorRef.current = r.a;
        setTextEdit({ x: m.x, y: m.y });
        destroyMobileAnchor();
        endDraw(live);
        return;
      }
      if (m.ptIndex === 0) {                      // 第一点固定：原位生成第二锚点接着拖
        firstRef.current = r.a;
        live.layer.pending = { id: '_pending', kind: t, pts: [r.a, r.a], color: DRAW_COLOR };
        live.layer.update();
        spawnMobileRef.current(live, 1, m.x, m.y);
      } else {
        commit(live, t, [firstRef.current!, r.a]);
        destroyMobileAnchor();
        flashDone(host);
        endDraw(live);
      }
    };
    el.addEventListener('pointerup', finishDrag);
    el.addEventListener('pointercancel', () => { dragging = false; });
  }, [destroyMobileAnchor, mobileAnchorAt, commit, endDraw, flashDone]);

  useEffect(() => { spawnMobileRef.current = spawnMobileAnchor; }, [spawnMobileAnchor]);

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
    const pt = localPt(live, e.clientX, e.clientY);
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
    if (mobileRef.current) return;   // 移动端锚点模式：预览由锚点拖动驱动，host 上的手指移动不管
    const pt = localPt(live, e.clientX, e.clientY);
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
    if (mobileRef.current) return;   // 移动端锚点模式：落点只认锚点的"拖动+点击固定"，不认 host 直点
    const pt = localPt(live, e.clientX, e.clientY);
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
    layer.hidden = hiddenRef.current;   // 换 symbol/周期重挂时保持当前眼睛状态
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
      destroyMobileAnchor();
      a.host.style.touchAction = '';
      a.host.style.cursor = '';
      a.series.detachPrimitive(layer);
      liveRef.current = null;
    };
  }, [onDown, onMove, onKey, stopDrag, destroyMobileAnchor]);

  // 工具切换：锁图表、改光标、关掉命中判定（画新线时不该被旧线抢走光标）
  useEffect(() => {
    toolRef.current = tool;
    const live = liveRef.current;
    if (!live) return;
    live.layer.interactive = tool === null;
    live.host.style.cursor = tool ? 'crosshair' : '';
    lock(live, tool !== null);
    if (!tool) { firstRef.current = null; live.layer.pending = null; live.layer.snap = null; }
    // 触屏：进入绘制模式即弹中心锚点；退出（含 Esc/完成）即收
    if (IS_COARSE && tool) spawnMobileAnchor(live, 0);
    if (!tool) destroyMobileAnchor();
    live.layer.update();
  }, [tool, lock, spawnMobileAnchor, destroyMobileAnchor]);

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

  /** 工具条入口：选画线工具时若线被藏着，自动把眼睛打开（画完看不见太诡异） */
  const selectTool = useCallback((t: Tool) => {
    if (t !== null && hiddenRef.current) setHiddenAllSync(false);
    setTool(t);
  }, [setHiddenAllSync]);

  return {
    attach,
    tool, setTool: selectTool,
    magnet, setMagnet,
    hiddenAll, setHiddenAll: setHiddenAllSync,
    /** 当前有选中的图形 → 🗑 按钮是"删选中"，否则是"清空全部" */
    selected,
    count,
    trash,
    textEdit, commitText, cancelText,
  };
}
