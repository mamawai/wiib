/**
 * 新闻 globe 标记层：以 ISeriesPrimitive 挂在蜡烛 series 上，直接画进主图画布——
 * 与蜡烛同一条渲染管线，平移缩放零延迟（旧实现是 DOM 覆盖层靠 250ms 轮询定位，
 * 拖图时肉眼可见拖影）；文字与图形随画布按 devicePixelRatio 渲染，清晰度与轴标签同级
 * （旧实现是 11px SVG + 8px HTML 角标，低分屏上发糊）。
 *
 * 点击命中不走 LWC 的 hover：CandleChart 在 pointerdown 里主动 {@link pick}
 * （触摸端没有 hover 前置态，套路同画线层 DrawingLayer）；hitTest 只负责悬停变手型。
 */
import type {
  IChartApi, IPrimitivePaneRenderer, IPrimitivePaneView, ISeriesApi,
  ISeriesPrimitive, PrimitiveHoveredItem, SeriesAttachedParameter, SeriesType, Time, UTCTimestamp,
} from 'lightweight-charts';
import type { CanvasRenderingTarget2D } from 'fancy-canvas';

/** 一个标记：一根 K 线时间桶内的快讯聚合（count>1 时画数字角标） */
export interface NewsMarker { time: number; count: number; }

const FONT = '700 10px system-ui, sans-serif';
/** 角标高度；globe 独占区宽度也用它（正方形区域内画圆） */
const CHIP_H = 18;
/** globe 半径 */
const R = 4.5;

export class NewsMarkersLayer implements ISeriesPrimitive<Time> {
  /** 标记集合，CandleChart 拉完快讯后赋值并调 update() */
  markers: NewsMarker[] = [];
  /** 角标配色：纸底 + 灰描边灰图形，由 CandleChart 从 token 灌进来，切主题改完调 update() */
  palette = { fg: '#7a7e88', border: 'rgba(122,126,136,.45)', bg: '#fafaf7' };
  /** 每帧实测的角标矩形（pane 坐标，time → 矩形）：点击命中与弹窗定位都读它 */
  readonly rects = new Map<number, { x: number; y: number; w: number; h: number }>();

  chartApi: IChartApi | null = null;
  seriesApi: ISeriesApi<SeriesType> | null = null;
  /** time → 该桶 bar 的最高价；桶里没 bar 返回 null（休市快讯没有可依附的蜡烛，不画） */
  readonly highOf: (time: number) => number | null;

  private _requestUpdate?: () => void;
  // 固定同一个数组引用：typings 明说库内部按引用做缓存，每帧新建会打掉缓存
  private readonly _views: IPrimitivePaneView[];

  constructor(highOf: (time: number) => number | null) {
    this.highOf = highOf;
    this._views = [new PaneView(this)];
  }

  attached(p: SeriesAttachedParameter<Time, SeriesType>) {
    this.chartApi = p.chart;
    this.seriesApi = p.series;
    this._requestUpdate = p.requestUpdate;
  }

  detached() {
    this.chartApi = null;
    this.seriesApi = null;
    this._requestUpdate = undefined;
  }

  /** 改完 markers/palette 调它触发重绘 */
  update() {
    this._requestUpdate?.();
  }

  paneViews() {
    return this._views;
  }

  /** pane 坐标 → 命中的标记时间桶；没中 null。留 2px 容差，18px 的靶子指尖也点得中 */
  pick(x: number, y: number): number | null {
    for (const [time, r] of this.rects) {
      if (x >= r.x - 2 && x <= r.x + r.w + 2 && y >= r.y - 2 && y <= r.y + r.h + 2) {
        return time;
      }
    }
    return null;
  }

  /** 悬停变手型：告诉用户这个标记点得动（点击本体在 CandleChart 的 pointerdown 里） */
  hitTest(x: number, y: number): PrimitiveHoveredItem | null {
    const time = this.pick(x, y);
    if (time === null) return null;
    return { externalId: `news:${time}`, zOrder: 'top', cursorStyle: 'pointer', hitTestPriority: 2 };
  }
}

class PaneView implements IPrimitivePaneView {
  private readonly _r: PaneRenderer;

  constructor(layer: NewsMarkersLayer) {
    this._r = new PaneRenderer(layer);
  }

  zOrder() {
    return 'top' as const;    // 压在蜡烛与指标线之上，别被淹在线里
  }

  renderer() {
    return this._r;
  }
}

class PaneRenderer implements IPrimitivePaneRenderer {
  private readonly _layer: NewsMarkersLayer;

  constructor(layer: NewsMarkersLayer) {
    this._layer = layer;
  }

  draw(target: CanvasRenderingTarget2D) {
    target.useMediaCoordinateSpace(({ context: c, mediaSize }) => {
      const L = this._layer;
      L.rects.clear();
      const chart = L.chartApi, series = L.seriesApi;
      if (!chart || !series || !L.markers.length) return;
      const ts = chart.timeScale();
      const { fg, border, bg } = L.palette;

      c.save();
      c.font = FONT;
      c.textBaseline = 'middle';
      c.textAlign = 'left';
      for (const m of L.markers) {
        const x = ts.timeToCoordinate(m.time as UTCTimestamp);
        if (x === null || x < -20 || x > mediaSize.width + 20) continue;
        const hi = L.highOf(m.time);
        if (hi === null) continue;
        const yHigh = series.priceToCoordinate(hi);
        if (yHigh === null) continue;

        const label = m.count > 1 ? String(m.count) : '';
        const w = label ? CHIP_H + c.measureText(label).width + 6 : CHIP_H;
        const x0 = Math.round(x - w / 2);
        // 悬在最高价上方 8px，纵向夹在 pane 内（价格出可视范围时坐标是界外值，不夹会飘出主图）
        const y0 = Math.round(Math.min(Math.max(4, yHigh - CHIP_H - 8), mediaSize.height - CHIP_H - 4));

        // 连接杆：角标底到蜡烛高点，标记归属哪根一目了然
        const stemTop = y0 + CHIP_H;
        if (yHigh - stemTop > 2) {
          c.strokeStyle = border;
          c.lineWidth = 1;
          c.beginPath();
          c.moveTo(x, stemTop);
          c.lineTo(x, Math.min(yHigh - 1, stemTop + 6));
          c.stroke();
        }

        // 底：纸底 + 1px 灰边，直角（全站不出圆角）
        c.beginPath();
        c.rect(x0, y0, w, CHIP_H);
        c.fillStyle = bg;
        c.fill();
        c.strokeStyle = border;
        c.lineWidth = 1;
        c.stroke();

        // globe：圆 + 竖椭圆经线 + 赤道横线（lucide globe 的画布摹写）
        const cx = x0 + CHIP_H / 2, cy = y0 + CHIP_H / 2;
        c.strokeStyle = fg;
        c.lineWidth = 1.2;
        c.beginPath();
        c.arc(cx, cy, R, 0, Math.PI * 2);
        c.stroke();
        c.beginPath();
        c.ellipse(cx, cy, R * 0.42, R, 0, 0, Math.PI * 2);
        c.stroke();
        c.beginPath();
        c.moveTo(cx - R, cy);
        c.lineTo(cx + R, cy);
        c.stroke();

        // 条数：直接排在 globe 右侧（旧版是 8px 悬浮角标，小到发糊）
        if (label) {
          c.fillStyle = fg;
          c.fillText(label, x0 + CHIP_H - 1, cy + 0.5);
        }
        L.rects.set(m.time, { x: x0, y: y0, w, h: CHIP_H });
      }
      c.restore();
    });
  }
}
