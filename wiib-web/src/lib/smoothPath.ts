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
 * 等距 x 的 y 点列 → 平滑曲线路径（M + 一串三次贝塞尔 C）。x 从 x0 起，每段走 step。
 * <p>
 * Hermite 转三次贝塞尔：控制点落在两端点各自的切线上，距端点 1/3 段宽。
 */
export function smoothPath(ys: number[], x0: number, step: number): string {
  if (ys.length < 2) return '';
  const m = tangents(ys);
  let d = `M${x0.toFixed(2)} ${ys[0].toFixed(2)}`;
  for (let i = 0; i < ys.length - 1; i++) {
    const xa = x0 + i * step, xb = x0 + (i + 1) * step;
    d += ` C${(xa + step / 3).toFixed(2)} ${(ys[i] + m[i] / 3).toFixed(2)},`
      + `${(xb - step / 3).toFixed(2)} ${(ys[i + 1] - m[i + 1] / 3).toFixed(2)},`
      + `${xb.toFixed(2)} ${ys[i + 1].toFixed(2)}`;
  }
  return d;
}
