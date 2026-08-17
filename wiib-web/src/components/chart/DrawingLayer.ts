/**
 * 画线图层：以 ISeriesPrimitive 挂到蜡烛 series 上，负责"画出来"和"点得中"。
 *
 * 挂 series 而不是 pane，图就是为了 priceAxisViews/timeAxisViews ——
 * IPanePrimitive 没有这两个钩子，水平线的价格轴标签、趋势线端点的时间轴标签
 * 就得自己在画布上模拟，费力还对不齐刻度。
 *
 * 状态（drawings/selectedId/pending/snap/cursor）是公开可变字段，由 useDrawings 直接改，
 * 改完调 update() 触发重绘。不走不可变数据：这些字段每次鼠标移动都在变，
 * 每帧新建数组只会让 GC 压力白涨。
 */
import type {
  IPrimitivePaneRenderer, IPrimitivePaneView, ISeriesPrimitive, ISeriesPrimitiveAxisView,
  PrimitiveHoveredItem, SeriesAttachedParameter, SeriesType, Time,
} from 'lightweight-charts';
import type { CanvasRenderingTarget2D } from 'fancy-canvas';
import {
  anchorToPoint, coordToTime, distToSegment, FIB_COLORS, FIB_LEVELS, fmtDuration, GAIN_COLOR, HIT_HANDLE,
  HIT_LINE, LOSS_COLOR, pointInPoly, POSITION_RR, rayEnd, timeToLogical,
  type Anchor, type ChartCtx, type Drawing,
} from '../../lib/chartDrawings';

/** 手柄半边长(px) */
const HANDLE = 4;
const FONT = '600 11px ui-monospace, Consolas, monospace';
/** 标签底色固定深色 —— 与 CandleChart 的悬停气泡同一套路，亮/暗主题下都读得清 */
const CHIP_FG = '#e6e8ee';
/** 斐波各档纵向间距小于这个就藏标签（手机竖屏主图只占 3/5 高度，7 条会糊成一坨） */
const FIB_LABEL_MIN_GAP = 13;
/** 面状图形（矩形/通道/区间）的淡填充：只是"这一块"的提示，不能盖住蜡烛 */
const AREA_FILL = 'rgba(41,98,255,.08)';

/** 命中结果：pt=-1 命中线身/内部(拖整体)，>=0 命中第几个锚点(拖端点) */
export interface Pick { id: string; pt: number; }

export interface LayerOpts {
  decimals: number;
  /** bar time → 人读时间，时间轴标签用；由 CandleChart 传入以复用它的周期化格式 */
  fmtTime: (t: number) => string;
}

type Pt = { x: number; y: number };

// ========== 画布小工具 ==========

/** 端点手柄：白底彩边的小方块，压在线上也看得见 */
function handle(c: CanvasRenderingContext2D, x: number, y: number, color: string) {
  c.fillStyle = '#fff';
  c.strokeStyle = color;
  c.lineWidth = 1.5;
  c.beginPath();
  c.rect(x - HANDLE, y - HANDLE, HANDLE * 2, HANDLE * 2);
  c.fill();
  c.stroke();
}

/**
 * 圆角矩形。roundRect 是 Safari 16.4 才有的，老 iOS 上直接调会抛异常，
 * 一抛就把整个 draw 打断、蜡烛都不画了 —— 退化成直角远好过整张图空白。
 */
function box(c: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  c.beginPath();
  if (c.roundRect) c.roundRect(x, y, w, h, r);
  else c.rect(x, y, w, h);
}

/**
 * 小标签。半透明中性灰底 + 降过不透明度的文字：
 * 斐波七档标签常年挂在图上，存在感必须低 —— 深底实字会把蜡烛压得喘不过气。
 * 中性灰不挑主题，亮/暗底上都只是淡淡一层。align='right' 时 x 是右边界。
 */
function chip(c: CanvasRenderingContext2D, x: number, y: number, text: string, fg = CHIP_FG,
              align: 'left' | 'right' | 'center' = 'left') {
  c.font = FONT;
  const w = c.measureText(text).width + 8, h = 15;
  const left = align === 'right' ? x - w : align === 'center' ? x - w / 2 : x;
  c.save();
  c.fillStyle = 'rgba(127,131,142,.18)';
  box(c, left, y - h / 2, w, h, 3);
  c.fill();
  c.globalAlpha = .72;
  c.fillStyle = fg;
  c.textBaseline = 'middle';
  c.textAlign = 'left';
  c.fillText(text, left + 4, y + .5);
  c.restore();
}

/** 线段 */
function seg(c: CanvasRenderingContext2D, a: Pt, b: Pt) {
  c.beginPath();
  c.moveTo(a.x, a.y);
  c.lineTo(b.x, b.y);
  c.stroke();
}

/** 用 rgba 写不同透明度的同色：'#rrggbb' → 'rgba(r,g,b,a)' */
function alpha(hex: string, a: number): string {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

// ========== 价格轴 / 时间轴标签 ==========

/**
 * 轴标签共用实现。coordinate() 每帧现算，所以缩放平移时标签自己会跟着走，
 * 视图对象不用重建 —— 只有"标签集合本身变了"才需要换数组（见 DrawingLayer 里的 sig 比对）。
 */
class AxisView implements ISeriesPrimitiveAxisView {
  private _coord: () => number | null;
  private _text: () => string;
  private _color: string;
  constructor(coord: () => number | null, text: () => string, color: string) {
    this._coord = coord; this._text = text; this._color = color;
  }
  coordinate() { return this._coord() ?? -1000; }   // 算不出就丢到画布外，等价于不显示
  text() { return this._text(); }
  textColor() { return '#fff'; }
  backColor() { return this._color; }
}

// ========== 渲染 ==========

class PaneRenderer implements IPrimitivePaneRenderer {
  private _layer: DrawingLayer;
  constructor(layer: DrawingLayer) { this._layer = layer; }

  draw(target: CanvasRenderingTarget2D) {
    target.useMediaCoordinateSpace(({ context: c, mediaSize }) => {
      const L = this._layer;
      L.width = mediaSize.width;
      L.height = mediaSize.height;
      c.save();
      if (!L.hidden) {   // 眼睛开关：只藏不删（选画线工具时 useDrawings 会自动取消隐藏）
        for (const d of L.drawings) this._one(c, d, d.id === L.selectedId, false);
        if (L.pending) this._one(c, L.pending, false, true);
      }
      this._cursor(c);   // 触屏落点十字：不受眼睛开关影响，正在画的时候必须看得见
      this._snap(c);
      c.restore();
    });
  }

  /**
   * 触屏绘制模式的落点十字：一横一竖贯穿整图的虚线 + 交叉点实心圆。
   * 手指在图上任意位置拖动移的是它（相对位移，手指不遮点），轻点固定 —— 见 useDrawings。
   */
  private _cursor(c: CanvasRenderingContext2D) {
    const L = this._layer;
    const cur = L.cursor;
    if (!cur) return;
    c.save();
    c.setLineDash([4, 4]);
    c.lineWidth = 1;
    c.strokeStyle = alpha(L.pending?.color ?? '#2962ff', .9);
    c.beginPath();
    c.moveTo(cur.x, 0); c.lineTo(cur.x, L.height);
    c.moveTo(0, cur.y); c.lineTo(L.width, cur.y);
    c.stroke();
    c.setLineDash([]);
    c.fillStyle = L.pending?.color ?? '#2962ff';
    c.beginPath();
    c.arc(cur.x, cur.y, 3, 0, Math.PI * 2);
    c.fill();
    c.restore();
  }

  /** 磁吸命中提示：一个空心圆，告诉用户"这一下会吸到这根的最高/最低/开/收" */
  private _snap(c: CanvasRenderingContext2D) {
    const L = this._layer;
    if (!L.snap) return;
    const p = anchorToPoint(L.snap, L.ctx);
    if (!p) return;
    c.setLineDash([]);
    c.strokeStyle = '#fff';
    c.lineWidth = 2;
    c.beginPath();
    c.arc(p.x, p.y, 4.5, 0, Math.PI * 2);
    c.stroke();
    c.strokeStyle = L.pending?.color ?? '#2962ff';
    c.lineWidth = 1;
    c.stroke();
  }

  private _one(c: CanvasRenderingContext2D, d: Drawing, sel: boolean, preview: boolean) {
    const L = this._layer;
    c.setLineDash(preview ? [5, 4] : []);           // 未落定的画虚线，跟已有图形区分
    c.lineWidth = sel ? 2 : 1.5;
    c.strokeStyle = d.color;

    if (d.kind === 'hline') { this._hline(c, d, sel); return; }
    if (d.kind === 'vline') { this._vline(c, d, sel); return; }

    const pts = d.pts.map(a => anchorToPoint(a, L.ctx));
    if (pts.some(p => p === null)) return;          // 图表还没布局好，这一帧跳过
    const p = pts as Pt[];

    switch (d.kind) {
      case 'trend': this._trend(c, d, p, sel); break;
      case 'ray': this._ray(c, d, p, sel); break;
      case 'channel': this._channel(c, d, p, sel); break;
      case 'rect': this._rect(c, d, p, sel); break;
      case 'fib': this._fib(c, d, p, sel); break;
      case 'long':
      case 'short': this._position(c, d, p, sel); break;
      case 'range': this._range(c, d, p, sel); break;
      default: this._text(c, d, p[0], sel);
    }
  }

  private _handles(c: CanvasRenderingContext2D, color: string, ...pts: Pt[]) {
    c.setLineDash([]);
    for (const q of pts) handle(c, q.x, q.y, color);
  }

  private _trend(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    seg(c, p[0], p[1]);
    if (sel) this._handles(c, d.color, p[0], p[1]);
  }

  /** 射线：从第一点经第二点一直延到画布边缘 */
  private _ray(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    const L = this._layer;
    seg(c, p[0], rayEnd(p[0], p[1], L.width, L.height));
    if (sel) this._handles(c, d.color, p[0], p[1]);
  }

  private _hline(c: CanvasRenderingContext2D, d: Drawing, sel: boolean) {
    const L = this._layer;
    const y = L.ctx.series.priceToCoordinate(d.pts[0].p);
    if (y === null) return;
    seg(c, { x: 0, y }, { x: L.width, y });
    // 手柄画在创建时点的那一格，给用户一个"这条线是我在这儿拉的"的锚
    if (!sel) return;
    const a = anchorToPoint(d.pts[0], L.ctx);
    if (a && a.x >= 0 && a.x <= L.width) this._handles(c, d.color, { x: a.x, y });
  }

  private _vline(c: CanvasRenderingContext2D, d: Drawing, sel: boolean) {
    const L = this._layer;
    const a = anchorToPoint(d.pts[0], L.ctx);
    if (!a) return;
    seg(c, { x: a.x, y: 0 }, { x: a.x, y: L.height });
    if (sel) this._handles(c, d.color, a);
  }

  /**
   * 平行通道：p0-p1 是基线，第二条线过 p2 与基线平行、裁到基线的 x 跨度内，两线之间淡填充，
   * 中间再来一条虚线中轴。三点都在像素空间算平行 —— 时间轴不等距/对数价格轴时
   * "价格空间的平行"画出来反而是歪的，用户眼睛看的是像素。
   */
  private _channel(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    seg(c, p[0], p[1]);
    if (p.length >= 3) {
      const q = channelPair(p);
      if (q) {
        const [q0, q1] = q;
        c.save();
        c.fillStyle = AREA_FILL;
        c.beginPath();
        c.moveTo(p[0].x, p[0].y); c.lineTo(p[1].x, p[1].y); c.lineTo(q1.x, q1.y); c.lineTo(q0.x, q0.y);
        c.closePath();
        c.fill();
        c.restore();
        seg(c, q0, q1);
        c.save();
        c.setLineDash([3, 4]);
        c.lineWidth = 1;
        seg(c, { x: (p[0].x + q0.x) / 2, y: (p[0].y + q0.y) / 2 }, { x: (p[1].x + q1.x) / 2, y: (p[1].y + q1.y) / 2 });
        c.restore();
      }
    }
    if (sel) this._handles(c, d.color, ...p);
  }

  private _rect(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    const x = Math.min(p[0].x, p[1].x), y = Math.min(p[0].y, p[1].y);
    const w = Math.abs(p[1].x - p[0].x), h = Math.abs(p[1].y - p[0].y);
    c.fillStyle = AREA_FILL;
    c.fillRect(x, y, w, h);
    c.strokeRect(x, y, w, h);
    if (sel) this._handles(c, d.color, p[0], p[1]);
  }

  /**
   * 斐波那契回撤：两点定 0/1 端，中间七档。
   * 各档在**价格空间**插值再转坐标，不在像素空间插 —— 对数价格轴下两者不等价。
   * 横向从两点左端一路延到画布右缘：回撤位主要是用来看后面怎么走的。
   */
  private _fib(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    const L = this._layer;
    const p0 = d.pts[0].p, p1 = d.pts[1].p;
    const x0 = Math.min(p[0].x, p[1].x), x1 = L.width;
    const ys: (number | null)[] = FIB_LEVELS.map(lv => L.ctx.series.priceToCoordinate(p0 + (p1 - p0) * lv));

    // 0.382~0.618 黄金区间淡填充：看盘时最常盯的就是这一段
    const gA = ys[2], gB = ys[4];
    if (gA !== null && gB !== null) {
      c.fillStyle = 'rgba(8,153,129,.07)';
      c.fillRect(x0, Math.min(gA, gB), x1 - x0, Math.abs(gB - gA));
    }

    // 相邻档挤在一起就只画线不画字，否则七个标签会互相盖住
    const valid = ys.filter((y): y is number => y !== null).sort((a, b) => a - b);
    const gap = valid.length < 2 ? Infinity
      : valid.slice(1).reduce((m, y, i) => Math.min(m, y - valid[i]), Infinity);
    const showLabel = sel || gap >= FIB_LABEL_MIN_GAP;

    c.lineWidth = sel ? 1.6 : 1.1;
    ys.forEach((y, i) => {
      if (y === null) return;
      c.strokeStyle = FIB_COLORS[i];
      seg(c, { x: x0, y }, { x: x1, y });
      if (showLabel) {
        const price = p0 + (p1 - p0) * FIB_LEVELS[i];
        chip(c, x0 + 4, y - 9, `${(FIB_LEVELS[i] * 100).toFixed(1)}% ${price.toFixed(L.opts.decimals)}`, FIB_COLORS[i]);
      }
    });

    if (sel) this._handles(c, d.color, p[0], p[1]);
  }

  /**
   * 多/空仓位：入场线 + 盈利区(绿) + 亏损区(红)，横跨 [入场 t, 止损 t]。
   * 右侧挂止盈/止损价及百分比、入场线上挂盈亏比。预览阶段只有两点时不画区间。
   */
  private _position(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    const L = this._layer;
    if (p.length < 3) { seg(c, p[0], p[1]); return; }
    const [entry, stop, target] = d.pts;
    const xL = Math.min(p[0].x, p[1].x), xR = Math.max(p[0].x, p[1].x);
    const yE = p[0].y, yS = p[1].y, yT = p[2].y;
    const dec = L.opts.decimals;
    const risk = Math.abs(entry.p - stop.p), reward = Math.abs(target.p - entry.p);
    const rr = risk > 0 ? reward / risk : POSITION_RR;
    const pct = (a: number) => entry.p > 0 ? `${a >= 0 ? '+' : ''}${(a / entry.p * 100).toFixed(2)}%` : '';

    c.save();
    c.setLineDash([]);
    c.fillStyle = alpha(GAIN_COLOR, .12);
    c.fillRect(xL, Math.min(yE, yT), xR - xL, Math.abs(yT - yE));
    c.fillStyle = alpha(LOSS_COLOR, .12);
    c.fillRect(xL, Math.min(yE, yS), xR - xL, Math.abs(yS - yE));
    c.lineWidth = 1;
    c.strokeStyle = GAIN_COLOR;
    seg(c, { x: xL, y: yT }, { x: xR, y: yT });
    c.strokeStyle = LOSS_COLOR;
    seg(c, { x: xL, y: yS }, { x: xR, y: yS });
    c.restore();
    c.lineWidth = sel ? 2 : 1.5;
    seg(c, { x: xL, y: yE }, { x: xR, y: yE });

    // 标签贴在各自区间的内侧（止盈/止损线朝入场线的那一边），右对齐到区间右缘，不飘出框外
    const inward = (y: number) => y + (yE >= y ? 9 : -9);
    chip(c, xR - 4, inward(yT), `止盈 ${target.p.toFixed(dec)} ${pct(target.p - entry.p)}`, GAIN_COLOR, 'right');
    chip(c, xR - 4, inward(yS), `止损 ${stop.p.toFixed(dec)} ${pct(stop.p - entry.p)}`, LOSS_COLOR, 'right');
    chip(c, xL + 4, yE - 9, `${d.kind === 'long' ? '多' : '空'} ${entry.p.toFixed(dec)} · RR ${rr.toFixed(2)}`, d.color);
    if (sel) this._handles(c, d.color, p[0], p[1], p[2]);
  }

  /** 价格区间：虚线框 + 一行读数（价差/涨跌幅/根数/时长），量一段行情用 */
  private _range(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    const L = this._layer;
    const x = Math.min(p[0].x, p[1].x), y = Math.min(p[0].y, p[1].y);
    const w = Math.abs(p[1].x - p[0].x), h = Math.abs(p[1].y - p[0].y);
    c.save();
    c.setLineDash([4, 3]);
    c.fillStyle = AREA_FILL;
    c.fillRect(x, y, w, h);
    c.strokeRect(x, y, w, h);
    c.restore();
    const [a, b] = d.pts;
    const dp = b.p - a.p;
    const l0 = timeToLogical(a.t, L.ctx), l1 = timeToLogical(b.t, L.ctx);
    const bars = l0 !== null && l1 !== null ? Math.abs(Math.round(l1 - l0)) : 0;
    const sign = dp >= 0 ? '+' : '';
    const text = `${sign}${dp.toFixed(L.opts.decimals)} (${sign}${a.p ? (dp / a.p * 100).toFixed(2) : '0.00'}%)`
      + ` · ${bars}根 · ${fmtDuration(b.t - a.t)}`;
    chip(c, x + w / 2, y - 10, text, d.color, 'center');
    if (sel) this._handles(c, d.color, p[0], p[1]);
  }

  /** 文字标注：锚点一个实心点，右侧接文本框。框的实测尺寸缓存下来给命中判定用 */
  private _text(c: CanvasRenderingContext2D, d: Drawing, p: Pt, sel: boolean) {
    const L = this._layer;
    const t = d.text ?? '';
    c.font = FONT;
    const w = c.measureText(t).width + 12, h = 19;
    const rect = { x: p.x + 9, y: p.y - h / 2, w, h };
    L.textBoxes.set(d.id, rect);   // 命中区照旧按整个文字框算，透明不等于点不中

    c.setLineDash([]);
    c.fillStyle = d.color;
    c.beginPath();
    c.arc(p.x, p.y, 3, 0, Math.PI * 2);
    c.fill();

    // 底透明：文字直接浮在图上（与输入阶段同观感），只在选中时画一圈虚线框提示命中区。
    // 描影兜可读性 —— 蓝字叠在同色系蜡烛上时靠这圈暗晕拉开层次，亮色主题下也只是淡淡一层
    if (sel) {
      c.strokeStyle = d.color;
      c.lineWidth = 1;
      c.setLineDash([4, 3]);
      box(c, rect.x, rect.y, w, h, 4);
      c.stroke();
      c.setLineDash([]);
    }

    c.fillStyle = d.color;
    c.textBaseline = 'middle';
    c.textAlign = 'left';
    c.shadowColor = 'rgba(0,0,0,.5)';
    c.shadowBlur = 3;
    c.fillText(t, rect.x + 6, p.y + .5);
    c.shadowBlur = 0;
    c.shadowColor = 'transparent';
  }
}

/**
 * 通道第二条线的两端：过 p2、方向同 p0→p1，裁到 [x0,x1]。
 * 基线竖直（dx=0）时改按 y 裁——否则除零。
 */
function channelPair(p: Pt[]): [Pt, Pt] | null {
  const dx = p[1].x - p[0].x, dy = p[1].y - p[0].y;
  if (dx === 0 && dy === 0) return null;
  if (Math.abs(dx) >= Math.abs(dy)) {
    const s0 = (p[0].x - p[2].x) / dx, s1 = (p[1].x - p[2].x) / dx;
    return [{ x: p[0].x, y: p[2].y + s0 * dy }, { x: p[1].x, y: p[2].y + s1 * dy }];
  }
  const s0 = (p[0].y - p[2].y) / dy, s1 = (p[1].y - p[2].y) / dy;
  return [{ x: p[2].x + s0 * dx, y: p[0].y }, { x: p[2].x + s1 * dx, y: p[1].y }];
}

class PaneView implements IPrimitivePaneView {
  private _r: PaneRenderer;
  constructor(layer: DrawingLayer) { this._r = new PaneRenderer(layer); }
  zOrder() { return 'top' as const; }        // 压在蜡烛和指标线之上
  renderer() { return this._r; }
}

// ========== 图层本体 ==========

export class DrawingLayer implements ISeriesPrimitive<Time> {
  drawings: Drawing[] = [];
  selectedId: string | null = null;
  /** 画到一半的预览图形（虚线渲染）；hline/vline/text 一击即成，用不到 */
  pending: Drawing | null = null;
  /** 当前磁吸命中的点，null=没吸中 */
  snap: { t: number; p: number } | null = null;
  /** 触屏绘制模式的落点十字（pane 像素坐标），null=不显示 */
  cursor: { x: number; y: number } | null = null;
  /**
   * 绘制模式下关掉 hitTest —— 正在画新线时不该因为划过旧线就把光标变成 move，
   * 更不该让 LWC 把旧线报成 hover 目标。
   */
  interactive = true;
  /** 隐藏全部画线（渲染/命中/轴标签一起藏）；数据不动，眼睛开关切回来原样恢复 */
  hidden = false;

  /** 最近一次绘制时的画布尺寸，水平线/垂直线/射线延伸到边缘要用 */
  width = 0;
  height = 0;
  /** 文字框实测矩形（id → 矩形），命中判定读它；中文宽度靠估算会差很多 */
  textBoxes = new Map<string, { x: number; y: number; w: number; h: number }>();

  private _views: IPrimitivePaneView[];
  private _requestUpdate?: () => void;
  private _priceViews: AxisView[] = [];
  private _priceSig = '';
  private _timeViews: AxisView[] = [];
  private _timeSig = '';

  ctx: ChartCtx;
  opts: LayerOpts;

  constructor(ctx: ChartCtx, opts: LayerOpts) {
    this.ctx = ctx;
    this.opts = opts;
    // 固定同一个数组引用：typings 明说库内部按引用做缓存，每帧新建会打掉缓存
    this._views = [new PaneView(this)];
  }

  attached(p: SeriesAttachedParameter<Time, SeriesType>) { this._requestUpdate = p.requestUpdate; }
  detached() { this._requestUpdate = undefined; }
  /** 改完状态调它触发重绘 */
  update() { this._requestUpdate?.(); }

  paneViews() { return this._views; }

  // ---- 命中判定 ----

  /**
   * 从最上层(最后画的)往下找第一个命中的。端点优先于线身：
   * 想拖端点时手柄区域会盖住线身，先判线身就永远拖不动端点。
   * 面状图形（矩形/通道/仓位/区间）点内部也算命中，拖整体。
   */
  pick(x: number, y: number): Pick | null {
    if (this.hidden) return null;   // 看不见的线不该点得中
    const near = (a: Pt, b: Pt) => distToSegment(x, y, a.x, a.y, b.x, b.y) <= HIT_LINE;
    for (let i = this.drawings.length - 1; i >= 0; i--) {
      const d = this.drawings[i];

      if (d.kind === 'hline') {
        const ly = this.ctx.series.priceToCoordinate(d.pts[0].p);
        if (ly !== null && Math.abs(y - ly) <= HIT_LINE) return { id: d.id, pt: -1 };
        continue;
      }

      const pts = d.pts.map(a => anchorToPoint(a, this.ctx));
      if (pts.some(p => p === null)) continue;
      const p = pts as Pt[];

      if (d.kind === 'vline') {
        if (Math.abs(x - p[0].x) <= HIT_LINE) return { id: d.id, pt: -1 };
        continue;
      }

      const onHandle = p.findIndex(q => Math.hypot(x - q.x, y - q.y) <= HIT_HANDLE);
      if (onHandle >= 0) return { id: d.id, pt: onHandle };

      const whole = { id: d.id, pt: -1 };
      switch (d.kind) {
        case 'trend':
          if (near(p[0], p[1])) return whole;
          break;
        case 'ray':
          if (near(p[0], rayEnd(p[0], p[1], this.width, this.height))) return whole;
          break;
        case 'channel': {
          if (near(p[0], p[1])) return whole;
          const q = p.length >= 3 ? channelPair(p) : null;
          if (q && (near(q[0], q[1]) || pointInPoly(x, y, [p[0], p[1], q[1], q[0]]))) return whole;
          break;
        }
        case 'rect':
        case 'range': {
          const x0 = Math.min(p[0].x, p[1].x) - HIT_LINE, x1 = Math.max(p[0].x, p[1].x) + HIT_LINE;
          const y0 = Math.min(p[0].y, p[1].y) - HIT_LINE, y1 = Math.max(p[0].y, p[1].y) + HIT_LINE;
          if (x >= x0 && x <= x1 && y >= y0 && y <= y1) return whole;
          break;
        }
        case 'long':
        case 'short': {
          if (p.length < 3) break;
          const x0 = Math.min(p[0].x, p[1].x) - HIT_LINE, x1 = Math.max(p[0].x, p[1].x) + HIT_LINE;
          const y0 = Math.min(p[0].y, p[1].y, p[2].y) - HIT_LINE, y1 = Math.max(p[0].y, p[1].y, p[2].y) + HIT_LINE;
          if (x >= x0 && x <= x1 && y >= y0 && y <= y1) return whole;
          break;
        }
        case 'fib': {
          const x0 = Math.min(p[0].x, p[1].x);
          if (x >= x0 - HIT_LINE && x <= this.width) {
            const p0 = d.pts[0].p, p1 = d.pts[1].p;
            const hit = FIB_LEVELS.some(lv => {
              const ly = this.ctx.series.priceToCoordinate(p0 + (p1 - p0) * lv);
              return ly !== null && Math.abs(y - ly) <= HIT_LINE;
            });
            if (hit) return whole;
          }
          break;
        }
        default: {
          const b = this.textBoxes.get(d.id);
          // 没画过就还没测量，退回锚点附近的小方块，至少能选中删掉
          if (b ? (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h)
                : Math.hypot(x - p[0].x, y - p[0].y) <= HIT_HANDLE) return whole;
        }
      }
    }
    return null;
  }

  hitTest(x: number, y: number): PrimitiveHoveredItem | null {
    if (!this.interactive) return null;
    const p = this.pick(x, y);
    if (!p) return null;
    return {
      externalId: p.pt >= 0 ? `${p.id}#${p.pt}` : p.id,
      zOrder: 'top',
      cursorStyle: p.pt >= 0 ? 'grab' : 'move',
      hitTestPriority: p.pt >= 0 ? 2 : 1,     // 端点是"点"型命中，优先级高于线型
    };
  }

  // ---- 轴标签 ----

  /**
   * 价格轴：水平线常显；选中的图形把各锚点价挂上去方便读准数（斐波七档、仓位三价各按自己的色）；
   * 触屏落点十字常显交叉点价格。
   */
  priceAxisViews(): readonly ISeriesPrimitiveAxisView[] {
    const items: { p: number; color: string }[] = [];
    for (const d of this.hidden ? [] : this.drawings) {
      if (d.kind === 'hline') items.push({ p: d.pts[0].p, color: d.color });
      else if (d.id !== this.selectedId) continue;
      else if (d.kind === 'fib') {
        const p0 = d.pts[0].p, p1 = d.pts[1].p;
        FIB_LEVELS.forEach((lv, i) => items.push({ p: p0 + (p1 - p0) * lv, color: FIB_COLORS[i] }));
      } else if (d.kind === 'long' || d.kind === 'short') {
        d.pts.forEach((a, i) => items.push({ p: a.p, color: i === 1 ? LOSS_COLOR : i === 2 ? GAIN_COLOR : d.color }));
      } else if (d.kind !== 'vline' && d.kind !== 'text') {
        d.pts.forEach(a => items.push({ p: a.p, color: d.color }));
      }
    }
    const specs = items.map(i => ({
      key: `${i.p}|${i.color}`,
      make: () => new AxisView(() => this.ctx.series.priceToCoordinate(i.p), () => i.p.toFixed(this.opts.decimals), i.color),
    }));
    if (this.cursor) {
      // 十字线的价读数随手指每帧变：坐标/文案都走 getter 现算，视图对象本身不用重建
      specs.push({
        key: 'cursor',
        make: () => new AxisView(
          () => this.cursor?.y ?? null,
          () => { const p = this.cursor ? this.ctx.series.coordinateToPrice(this.cursor.y) : null; return p === null ? '' : p.toFixed(this.opts.decimals); },
          '#2962ff'),
      });
    }
    return this._sync(specs, '_priceViews', '_priceSig');
  }

  /** 时间轴：垂直线常显；选中的多点图形挂各锚点时刻；触屏落点十字常显交叉点时刻 */
  timeAxisViews(): readonly ISeriesPrimitiveAxisView[] {
    const items: { a: Anchor; color: string }[] = [];
    for (const d of this.hidden ? [] : this.drawings) {
      if (d.kind === 'vline') items.push({ a: d.pts[0], color: d.color });
      else if (d.id === this.selectedId && d.kind !== 'hline' && d.kind !== 'text') {
        // 仓位的止盈点 t 恒等于止损点 t，去重
        for (const a of d.pts) if (!items.some(i => i.a.t === a.t && i.color === d.color)) items.push({ a, color: d.color });
      }
    }
    const specs = items.map(i => ({
      key: `${i.a.t}|${i.color}`,
      make: () => new AxisView(
        () => { const q = anchorToPoint(i.a, this.ctx); return q ? q.x : null; },
        () => this.opts.fmtTime(i.a.t), i.color,
      ),
    }));
    if (this.cursor) {
      specs.push({
        key: 'cursor',
        make: () => new AxisView(
          () => this.cursor?.x ?? null,
          () => { const t = this.cursor ? coordToTime(this.cursor.x, this.ctx) : null; return t === null ? '' : this.opts.fmtTime(t); },
          '#2962ff'),
      });
    }
    return this._sync(specs, '_timeViews', '_timeSig');
  }

  /**
   * 标签集合没变就返回同一个数组（同 paneViews 的理由）。
   * AxisView 的坐标是每帧现算的，所以缩放平移不需要重建。
   */
  private _sync(
    specs: { key: string; make: () => AxisView }[],
    field: '_priceViews' | '_timeViews',
    sigField: '_priceSig' | '_timeSig',
  ): AxisView[] {
    const sig = specs.map(s => s.key).join('~');
    if (sig !== this[sigField]) {
      this[sigField] = sig;
      this[field] = specs.map(s => s.make());
    }
    return this[field];
  }
}
