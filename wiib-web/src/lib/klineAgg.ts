/**
 * K线周期选项与 5m 行聚合：回测/复盘图表共用。
 * 底层数据一律 5m（撮合/拉取口径不变），切周期是纯前端聚合显示。
 */

export interface IvOption {
  label: string;
  min: number;
}

export const IV_OPTIONS: IvOption[] = [
  { label: '5m', min: 5 }, { label: '15m', min: 15 }, { label: '1h', min: 60 },
  { label: '4h', min: 240 }, { label: '1d', min: 1440 },
];

export const ivLabel = (min: number) => IV_OPTIONS.find(o => o.min === min)?.label ?? `${min}m`;

/** 5m 行 [openTime,o,h,l,c,v] 聚合成 ivMin 周期；桶按 UTC 对齐（与币安日线同口径） */
export function aggregateBars(rows: number[][], ivMin: number): number[][] {
  if (ivMin <= 5) return rows;
  const ivMs = ivMin * 60_000;
  const out: number[][] = [];
  for (const r of rows) {
    const bucket = Math.floor(r[0] / ivMs) * ivMs;
    const last = out[out.length - 1];
    if (last !== undefined && last[0] === bucket) {
      if (r[2] > last[2]) last[2] = r[2];
      if (r[3] < last[3]) last[3] = r[3];
      last[4] = r[4];
      last[5] += r[5];
    } else {
      out.push([bucket, r[1], r[2], r[3], r[4], r[5]]);
    }
  }
  return out;
}

/** 找时间 t 落在哪根 bar：最后一根 openTime ≤ t 的下标（二分） */
export function barIndexAt(bars: number[][], t: number): number {
  let lo = 0, hi = bars.length - 1, ans = 0;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (bars[mid][0] <= t) { ans = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return ans;
}
