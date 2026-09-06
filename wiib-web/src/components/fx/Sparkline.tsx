import { useId } from 'react';
import { cn } from '../../lib/utils';

/**
 * 单调三次插值的各点切线（Fritsch–Carlson）。x 等距，所以斜率直接取相邻差值。
 * <p>
 * 不用普通 Catmull-Rom 是因为它会过冲——净值曲线过冲等于凭空画出一个
 * 根本不存在的高点或低点，那是在撒谎。单调保形算法保证曲线不越过数据点。
 */
function tangents(ys: number[]): number[] {
  const n = ys.length;
  const d: number[] = [];
  for (let i = 0; i < n - 1; i++) d.push(ys[i + 1] - ys[i]);

  const m = [d[0]];
  // 局部极值（左右斜率异号）处切线压平，曲线才不会在拐点拱出去
  for (let i = 1; i < n - 1; i++) m.push(d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2);
  m.push(d[n - 2]);

  // 切线过陡会破坏单调，按 3 倍段斜率收缩封顶
  for (let i = 0; i < n - 1; i++) {
    if (d[i] === 0) { m[i] = 0; m[i + 1] = 0; continue; }
    const a = m[i] / d[i], b = m[i + 1] / d[i];
    const s = a * a + b * b;
    if (s > 9) {
      const t = 3 / Math.sqrt(s);
      m[i] = t * a * d[i];
      m[i + 1] = t * b * d[i];
    }
  }
  return m;
}

/**
 * 描线动画迷你走势：平滑曲线 + 面积渐变 + 入场画线 + 端点光点。
 * 不传 stroke 时按首尾涨跌自动取 gain/loss。
 * <p>
 * 想让数据刷新后重新描一遍线，调用方给个随数据变化的 key 即可（首页净值曲线就这么干）；
 * 行情表那种一屏几十条的小图别给，不然每次行情跳动整屏重画。
 */
export function Sparkline({ data, stroke, area = true, dot = true, className }: {
  data: number[];
  /** CSS 颜色值（可用 var(--color-*)） */
  stroke?: string;
  area?: boolean;
  /** 端点光点。大尺寸拉伸容器（如首页净值曲线）圆点会变形，建议关掉 */
  dot?: boolean;
  className?: string;
}) {
  const id = useId();
  if (data.length < 2) return null;

  const W = 100, H = 28, P = 2;
  const mn = Math.min(...data), mx = Math.max(...data);
  const step = W / (data.length - 1);
  const ys = data.map(d => H - P - ((d - mn) / (mx - mn || 1)) * (H - P * 2));

  // Hermite 转三次贝塞尔：控制点落在两端点各自的切线上，距端点 1/3 段宽
  const m = tangents(ys);
  let line = `M0 ${ys[0].toFixed(2)}`;
  for (let i = 0; i < ys.length - 1; i++) {
    const x0 = i * step, x1 = (i + 1) * step;
    line += ` C${(x0 + step / 3).toFixed(2)} ${(ys[i] + m[i] / 3).toFixed(2)},`
      + `${(x1 - step / 3).toFixed(2)} ${(ys[i + 1] - m[i + 1] / 3).toFixed(2)},`
      + `${x1.toFixed(2)} ${ys[i + 1].toFixed(2)}`;
  }

  const up = data[data.length - 1] >= data[0];
  const c = stroke ?? (up ? 'var(--color-gain)' : 'var(--color-loss)');
  const [lx, ly] = [W, ys[ys.length - 1]];

  return (
    /* 入场动画走 clip-path 从左往右揭示，见 index.css。
       不用 stroke-dasharray 那套：本组件为了线宽不被 preserveAspectRatio=none 拉变形，
       必须挂 vectorEffect=non-scaling-stroke，而它会让 dash 改按屏幕坐标算——
       user-space 的 pathLength 归一化当场失效，线要么全隐要么只画一半 */
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className={cn('reveal', className)}>
      {area && (
        <>
          <defs>
            <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor={c} stopOpacity=".16" />
              <stop offset="1" stopColor={c} stopOpacity="0" />
            </linearGradient>
          </defs>
          <path d={`${line} L${W} ${H} L0 ${H} Z`} fill={`url(#${id})`} />
        </>
      )}
      <path
        d={line} fill="none" stroke={c} strokeWidth="1.5"
        strokeLinecap="round" strokeLinejoin="round" vectorEffect="non-scaling-stroke"
      />
      {dot && <circle cx={lx} cy={ly} r="2" fill={c} className="reveal-late" />}
    </svg>
  );
}
