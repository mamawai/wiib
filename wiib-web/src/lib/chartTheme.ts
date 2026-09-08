/**
 * 图表主题工具：轴/网格/提示框与全站 CSS 变量同源，
 * 亮暗双模式各取各的层次色，杜绝图表里硬编码白底 slate 灰。
 * <p>{@link chartUi} 给 ECharts，{@link lwcTheme} 给 lightweight-charts。
 */
import { CrosshairMode, LineStyle, type ChartOptions, type DeepPartial } from 'lightweight-charts';

/** 读 tailwind v4 @theme 变量（运行时随亮暗模式变化） */
export function cssVar(name: string, fallback: string): string {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

export interface ChartUi {
  /** 轴刻度文字 */
  axisLabel: string;
  /** 网格线/轴线（用全站边框色，保持 recessive） */
  gridLine: string;
  /** 卡片面色（tooltip 底） */
  card: string;
  /** 正文色 */
  fg: string;
  /** 平面 tooltip 外观（直接展开进 ECharts tooltip 配置） */
  tooltip: {
    backgroundColor: string;
    borderColor: string;
    borderWidth: number;
    padding: number[];
    textStyle: { color: string; fontSize: number; fontFamily: string };
    extraCssText: string;
  };
}

export function chartUi(isDark: boolean): ChartUi {
  const axisLabel = cssVar('--color-muted-foreground', isDark ? '#8b8e97' : '#7a7e88');
  const gridLine = cssVar('--color-border', isDark ? '#26282d' : '#e7e7e2');
  const card = cssVar('--color-card', isDark ? '#0f1012' : '#fafaf7');
  const fg = cssVar('--color-foreground', isDark ? '#f1f1ec' : '#121316');
  return {
    axisLabel,
    gridLine,
    card,
    fg,
    tooltip: {
      backgroundColor: card,
      borderColor: gridLine,
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: fg, fontSize: 11, fontFamily: cssVar('--font-sans', 'ui-sans-serif, sans-serif') },
      extraCssText: isDark
        ? 'box-shadow: 0 6px 16px rgba(0,0,0,.45); border-radius: 0;'
        : 'box-shadow: 0 4px 12px rgba(0,0,0,.10); border-radius: 0;',
    },
  };
}

/** lightweight-charts 那份主题：颜色现读 token，切主题时重新取一份 applyOptions 就够 */
export interface LwcTheme {
  fg: string;
  bg: string;
  mute: string;
  border: string;
  card2: string;
  gain: string;
  loss: string;
  warning: string;
  primary: string;
  /** 三条线的固定配色：MA / EMA / RSI 三档按序取，DIF/DEA 取前两个 */
  col3: [string, string, string];
  /** 直接喂 createChart / applyOptions */
  options: DeepPartial<ChartOptions>;
  /** 蜡烛四色：涨绿跌红、影线与描边同色、实心 */
  candle: {
    upColor: string; downColor: string;
    borderUpColor: string; borderDownColor: string;
    wickUpColor: string; wickDownColor: string;
  };
}

export function lwcTheme(): LwcTheme {
  const fg = cssVar('--color-foreground', '#121316');
  const bg = cssVar('--color-background', '#fafaf7');
  const mute = cssVar('--color-muted-foreground', '#7a7e88');
  const border = cssVar('--color-border', '#e7e7e2');
  const card2 = cssVar('--color-card-2', '#f0f0ec');
  const gain = cssVar('--color-gain', '#0b8a5c');
  const loss = cssVar('--color-loss', '#d63b2f');
  const warning = cssVar('--color-warning', '#c98a00');
  const primary = cssVar('--color-primary', '#f25f0a');
  const font = cssVar('--font-sans', 'ui-sans-serif, system-ui, sans-serif');
  // 十字线两条墨色虚线 + 墨底标签；落在副图上时标签自动显示那个 pane 的值
  const cross = { color: fg, width: 1 as const, style: LineStyle.Dashed, labelBackgroundColor: fg };
  return {
    fg, bg, mute, border, card2, gain, loss, warning, primary,
    col3: [primary, '#2f8fd6', '#7c5cff'],
    options: {
      layout: {
        background: { color: 'transparent' }, textColor: mute, fontSize: 11, fontFamily: font,
        // attributionLogo: v5 默认 true 会在右下角画 TradingView logo，关掉保持纸面干净
        attributionLogo: false,
        panes: { separatorColor: border, separatorHoverColor: mute, enableResize: true },
      },
      grid: { vertLines: { color: border }, horzLines: { color: border } },
      crosshair: { mode: CrosshairMode.Normal, vertLine: cross, horzLine: cross },
      rightPriceScale: { borderColor: border },
      timeScale: { borderColor: border },
    },
    candle: {
      upColor: gain, downColor: loss,
      borderUpColor: gain, borderDownColor: loss,
      wickUpColor: gain, wickDownColor: loss,
    },
  };
}

/** hex → rgba，渐变透明度用 */
export function rgba(hex: string, a: number): string {
  const m = hex.replace('#', '');
  const n = m.length === 3 ? m.split('').map((c) => c + c).join('') : m;
  const r = parseInt(n.slice(0, 2), 16);
  const g = parseInt(n.slice(2, 4), 16);
  const b = parseInt(n.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}
