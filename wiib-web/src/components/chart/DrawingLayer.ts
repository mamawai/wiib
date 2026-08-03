/**
 * 画线图层：以 ISeriesPrimitive 挂到蜡烛 series 上，负责"画出来"和"点得中"。
 *
 * 挂 series 而不是 pane，图就是为了 priceAxisViews/timeAxisViews ——
 * IPanePrimitive 没有这两个钩子，水平线的价格轴标签、趋势线端点的时间轴标签
 * 就得自己在画布上模拟，费力还对不齐刻度。
 *
 * 状态（drawings/selectedId/pending/snap）是公开可变字段，由 useDrawings 直接改，
 * 改完调 update() 触发重绘。不走不可变数据：这些字段每次鼠标移动都在变，
 * 每帧新建数组只会让 GC 压力白涨。
 */
import type {
  IPrimitivePaneRenderer, IPrimitivePaneView, ISeriesPrimitive, ISeriesPrimitiveAxisView,
  PrimitiveHoveredItem, SeriesAttachedParameter, SeriesType, Time,
} from 'lightweight-charts';
import type { CanvasRenderingTarget2D } from 'fancy-canvas';
import {
  anchorToPoint, distToSegment, FIB_COLORS, FIB_LEVELS, HIT_HANDLE, HIT_LINE,
  type ChartCtx, type Drawing,
} from '../../lib/chartDrawings';

/** 手柄半边长(px) */
const HANDLE = 4;
const FONT = '600 11px ui-monospace, Consolas, monospace';
/** 标签底色固定深色 —— 与 CandleChart 的悬停气泡同一套路，亮/暗主题下都读得清 */
const CHIP_FG = '#e6e8ee';
/** 斐波各档纵向间距小于这个就藏标签（手机竖屏主图只占 3/5 高度，7 条会糊成一坨） */
const FIB_LABEL_MIN_GAP = 13;

/** 命中结果：pt=-1 命中线身(拖整体)，>=0 命中第几个端点(拖端点) */
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
 * fib 档位小标签（目前唯一调用方）。半透明中性灰底 + 降过不透明度的文字：
 * 七档标签常年挂在图上，存在感必须低 —— 深底实字会把蜡烛压得喘不过气。
 * 中性灰不挑主题，亮/暗底上都只是淡淡一层。align='right' 时 x 是右边界。
 */
function chip(c: CanvasRenderingContext2D, x: number, y: number, text: string, fg = CHIP_FG, align: 'left' | 'right' = 'left') {
  c.font = FONT;
  const w = c.measureText(text).width + 8, h = 15;
  const left = align === 'right' ? x - w : x;
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

// ========== 价格轴 / 时间轴标签 ==========

/**
 * 轴标签共用实现。coordinate() 每帧现算，所以缩放平移时标签自己会跟着走，
 * 视图对象不用重建 —— 只有"标签集合本身变了"才需要换数组（见 DrawingLayer 里的 sig 比对）。
 */
class AxisView implements ISeriesPrimitiveAxisView {
  private _coord: () => number | null;
  private _text: string;
  private _color: string;
  constructor(coord: () => number | null, text: string, color: string) {
    this._coord = coord; this._text = text; this._color = color;
  }
  coordinate() { return this._coord() ?? -1000; }   // 算不出就丢到画布外，等价于不显示
  text() { return this._text; }
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
      c.save();
      for (const d of L.drawings) this._one(c, d, d.id === L.selectedId, false);
      if (L.pending) this._one(c, L.pending, false, true);
      this._snap(c);
      c.restore();
    });
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

    const pts = d.pts.map(a => anchorToPoint(a, L.ctx));
    if (pts.some(p => p === null)) return;          // 图表还没布局好，这一帧跳过
    const p = pts as Pt[];

    if (d.kind === 'trend') this._trend(c, d, p, sel);
    else if (d.kind === 'fib') this._fib(c, d, p, sel);
    else this._text(c, d, p[0], sel);
  }

  private _trend(c: CanvasRenderingContext2D, d: Drawing, p: Pt[], sel: boolean) {
    c.beginPath();
    c.moveTo(p[0].x, p[0].y);
    c.lineTo(p[1].x, p[1].y);
    c.stroke();
    if (sel) { c.setLineDash([]); handle(c, p[0].x, p[0].y, d.color); handle(c, p[1].x, p[1].y, d.color); }
  }

  private _hline(c: CanvasRenderingContext2D, d: Drawing, sel: boolean) {
    const L = this._layer;
    const y = L.ctx.series.priceToCoordinate(d.pts[0].p);
    if (y === null) return;
    c.beginPath();
    c.moveTo(0, y);
    c.lineTo(L.width, y);
    c.stroke();
    // 手柄画在创建时点的那一格，给用户一个"这条线是我在这儿拉的"的锚
    if (!sel) return;
    const a = anchorToPoint(d.pts[0], L.ctx);
    c.setLineDash([]);
    if (a && a.x >= 0 && a.x <= L.width) handle(c, a.x, y, d.color);
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
      c.beginPath();
      c.moveTo(x0, y);
      c.lineTo(x1, y);
      c.stroke();
      if (showLabel) {
        const price = p0 + (p1 - p0) * FIB_LEVELS[i];
        chip(c, x0 + 4, y - 9, `${(FIB_LEVELS[i] * 100).toFixed(1)}% ${price.toFixed(L.opts.decimals)}`, FIB_COLORS[i]);
      }
    });

    if (sel) { c.setLineDash([]); handle(c, p[0].x, p[0].y, d.color); handle(c, p[1].x, p[1].y, d.color); }
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
  /** 画到一半的预览图形（虚线渲染）；hline/text 一击即成，用不到 */
  pending: Drawing | null = null;
  /** 当前磁吸命中的点，null=没吸中 */
  snap: { t: number; p: number } | null = null;
  /**
   * 绘制模式下关掉 hitTest —— 正在画新线时不该因为划过旧线就把光标变成 move，
   * 更不该让 LWC 把旧线报成 hover 目标。
   */
  interactive = true;

  /** 最近一次绘制时的画布宽度，水平线/斐波延伸到右缘要用 */
  width = 0;
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
   */
  pick(x: number, y: number): Pick | null {
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

      const onHandle = p.findIndex(q => Math.hypot(x - q.x, y - q.y) <= HIT_HANDLE);
      if (onHandle >= 0) return { id: d.id, pt: onHandle };

      if (d.kind === 'trend') {
        if (distToSegment(x, y, p[0].x, p[0].y, p[1].x, p[1].y) <= HIT_LINE) return { id: d.id, pt: -1 };
      } else if (d.kind === 'fib') {
        const x0 = Math.min(p[0].x, p[1].x);
        if (x >= x0 - HIT_LINE && x <= this.width) {
          const p0 = d.pts[0].p, p1 = d.pts[1].p;
          const hit = FIB_LEVELS.some(lv => {
            const ly = this.ctx.series.priceToCoordinate(p0 + (p1 - p0) * lv);
            return ly !== null && Math.abs(y - ly) <= HIT_LINE;
          });
          if (hit) return { id: d.id, pt: -1 };
        }
      } else {
        const b = this.textBoxes.get(d.id);
        // 没画过就还没测量，退回锚点附近的小方块，至少能选中删掉
        if (b ? (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h)
              : Math.hypot(x - p[0].x, y - p[0].y) <= HIT_HANDLE) return { id: d.id, pt: -1 };
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

  /** 水平线常显价格；斐波选中时把七档价一并挂上去，方便读准数 */
  priceAxisViews(): readonly ISeriesPrimitiveAxisView[] {
    const items: { p: number; color: string }[] = [];
    for (const d of this.drawings) {
      if (d.kind === 'hline') items.push({ p: d.pts[0].p, color: d.color });
      else if (d.kind === 'fib' && d.id === this.selectedId) {
        const p0 = d.pts[0].p, p1 = d.pts[1].p;
        FIB_LEVELS.forEach((lv, i) => items.push({ p: p0 + (p1 - p0) * lv, color: FIB_COLORS[i] }));
      } else if (d.kind === 'trend' && d.id === this.selectedId) {
        d.pts.forEach(a => items.push({ p: a.p, color: d.color }));
      }
    }
    return this._sync(items.map(i => ({
      key: `${i.p}|${i.color}`,
      make: () => new AxisView(() => this.ctx.series.priceToCoordinate(i.p), i.p.toFixed(this.opts.decimals), i.color),
    })), '_priceViews', '_priceSig');
  }

  /** 选中的趋势线/斐波，两个端点的时刻挂到时间轴上 */
  timeAxisViews(): readonly ISeriesPrimitiveAxisView[] {
    const d = this.drawings.find(x => x.id === this.selectedId);
    const items = (d && (d.kind === 'trend' || d.kind === 'fib')) ? d.pts : [];
    return this._sync(items.map(a => ({
      key: `${a.t}|${d!.color}`,
      make: () => new AxisView(
        () => { const q = anchorToPoint(a, this.ctx); return q ? q.x : null; },
        this.opts.fmtTime(a.t), d!.color,
      ),
    })), '_timeViews', '_timeSig');
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
