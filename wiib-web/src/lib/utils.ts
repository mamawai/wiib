import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// ---- 全站统一格式化口径：数字千分位；时间固定新加坡时区（UTC+8） ----

/** 千分位 + 固定小数位；string 自动 parseFloat；null/NaN 返回 '-'。 */
export function fmtNum(n: number | string | null | undefined, decimals = 2): string {
  const v = typeof n === 'string' ? parseFloat(n) : n;
  if (v == null || !Number.isFinite(v)) return '-';
  return v.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/**
 * 新加坡时间 yyyy-MM-dd（不传参就是"今天"）。日历/网格切日、按日查接口走这里。
 * <p>
 * 用 en-CA 是因为它的短日期格式恰好就是 ISO 的 yyyy-MM-dd，省一轮手工补零；
 * 不能图省事用 toISOString().slice(0,10)——那是 UTC，东八区早上 8 点前整体退到前一天。
 */
export function fmtDate(ts: number | string | Date = Date.now()): string {
  return new Date(ts).toLocaleDateString('en-CA', { timeZone: 'Asia/Singapore' });
}

/** 新加坡时间 MM/DD HH:mm（withSeconds=true 时带秒），列表/卡片时间戳统一走这里。 */
export function fmtDateTime(ts: number | string | Date, withSeconds = false): string {
  return new Date(ts).toLocaleString('zh-CN', {
    timeZone: 'Asia/Singapore',
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' as const } : {}),
    hour12: false,
  });
}

/** 新加坡时间 HH:mm（withSeconds=true 时带秒），日内图表轴/tooltip 走这里。 */
export function fmtTime(ts: number | string | Date, withSeconds = false): string {
  return new Date(ts).toLocaleTimeString('en-GB', {
    timeZone: 'Asia/Singapore',
    hour: '2-digit', minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' as const } : {}),
    hour12: false,
  });
}

/**
 * 相对时间："刚刚 / N分钟前 / N小时前 / N天前"，超过 7 天回落到 {@link fmtDateTime} 的绝对时间。
 * 通知列表用——"3分钟前"比"07-21 14:23"更快让人判断这事新不新鲜。
 */
export function fmtRelative(ts: number | string | Date): string {
  const then = new Date(ts).getTime();
  if (!Number.isFinite(then)) return '-';
  const diff = Date.now() - then;
  // 时钟漂移/服务端时间超前时 diff 为负，按"刚刚"处理，不显示"-1分钟前"
  if (diff < 60_000) return '刚刚';
  if (diff < 3600_000) return Math.floor(diff / 60_000) + '分钟前';
  if (diff < 86400_000) return Math.floor(diff / 3600_000) + '小时前';
  if (diff < 7 * 86400_000) return Math.floor(diff / 86400_000) + '天前';
  return fmtDateTime(ts);
}

/**
 * 时长："3天4时 / 5时12分 / 8分30秒 / 42秒"，最多两级单位。
 * 持仓时长用——精确到毫秒没人看，"拿了3天"和"拿了8分钟"的区别才是要传达的。
 */
export function fmtDuration(from: number | string | Date, to: number | string | Date): string {
  const ms = new Date(to).getTime() - new Date(from).getTime();
  if (!Number.isFinite(ms)) return '-';
  // 时钟漂移能让平仓时间早于开仓，负数按 0 处理，不显示"-3分钟"
  const s = Math.max(0, Math.floor(ms / 1000));
  const d = Math.floor(s / 86400);
  const h = Math.floor(s / 3600) % 24;
  const m = Math.floor(s / 60) % 60;
  if (d > 0) return h > 0 ? `${d}天${h}时` : `${d}天`;
  if (h > 0) return m > 0 ? `${h}时${m}分` : `${h}时`;
  if (m > 0) return s % 60 > 0 ? `${m}分${s % 60}秒` : `${m}分`;
  return `${s}秒`;
}

/** token 数缩写：12480 → 12.5k。一行小字里放得下，不带尾随空格，拼接由调用方管。 */
export function fmtTokens(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
}

/** 大额缩写：≥1亿 → X.XX亿，≥1万 → X.XX万，其余两位小数；null/NaN 返回 '-'。 */
export function fmtMoney(n: number | null | undefined): string {
  if (n == null || !Number.isFinite(n)) return '-';
  if (Math.abs(n) >= 1e8) return (n / 1e8).toFixed(2) + '亿';
  if (Math.abs(n) >= 1e4) return (n / 1e4).toFixed(2) + '万';
  return n.toFixed(2);
}

