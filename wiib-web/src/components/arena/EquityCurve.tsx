import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { smoothPath } from '../../lib/smoothPath';
import { cn, fmtNum } from '../../lib/utils';
import type { TraderEquityPoint } from '../../types';

const H = 280;
const PAD = 8;
/** 末值标签高：20px 粗体 leading-none 一行 + 12px 小字一行（body 行高 1.45 ≈ 17.4），取 38 */
const TAG_H = 38;
/** 末值标签跟末点的间隙 */
const TAG_GAP = 24;

/**
 * 本局净值曲线：单调保形平滑曲线 + 面积 + 初始资金虚线基准。
 * 基准值一起参与 min/max，那条线一定落在图内；末值和首末日期挂在图外，等线画完再浮现。
 */
export function EquityCurve({ points, base = 10000, lastLabel, className }: {
  points: TraderEquityPoint[];
  /** 基准线的值，就是每局的注资额 */
  base?: number;
  /** 末值下面那行小字，如「最近一次唤醒 12:04」 */
  lastLabel?: string;
  className?: string;
}) {
  const { t, i18n } = useTranslation('ai');
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(0);

  // 宽度跟着容器走：两栏布局里侧栏一变宽这边就得重画
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setW(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const lang = i18n.resolvedLanguage ?? i18n.language;
  const axDay = (ts: number) => new Date(ts).toLocaleDateString(lang, { month: 'long', day: 'numeric' });

  const vals = points.map(p => p.equity);
  const min = Math.min(...vals, base);
  const max = Math.max(...vals, base);
  const y = (v: number) => PAD + (H - 2 * PAD) * (1 - (v - min) / (max - min || 1));
  const step = (w - 2 * PAD) / (points.length - 1);
  const ys = vals.map(y);
  const line = smoothPath(ys, PAD, step);
  const xEnd = PAD + step * (points.length - 1);
  const area = `${line} L${xEnd.toFixed(2)} ${H} L${PAD} ${H} Z`;
  const yBase = y(base);
  const yLast = ys[ys.length - 1];
  const last = points[points.length - 1];
  // 末点贴图底时标签会掉出容器压到下面那块，翻到点上方去
  const tagTop = yLast + TAG_GAP + TAG_H > H ? yLast - TAG_GAP - TAG_H : yLast + TAG_GAP;
  // 基准线贴顶时标签放线上方会出界，改挂线下方
  const baseTop = yBase - 18 >= 0 ? yBase - 18 : yBase + 6;

  return (
    <div ref={box} className={cn('relative h-[280px]', className)}>
      {w > 0 && points.length > 1 && (
        <>
          <svg viewBox={`0 0 ${w} ${H}`} className="reveal block w-full h-full overflow-visible">
            <path d={area} fill="var(--color-foreground)" fillOpacity=".05" />
            <line x1={0} x2={w} y1={yBase} y2={yBase} stroke="var(--color-muted-foreground)" strokeDasharray="2 5" />
            <path d={line} fill="none" stroke="var(--color-foreground)" strokeWidth="2" />
          </svg>
          <span className="reveal-late absolute left-0 text-[12px] mute" style={{ top: baseTop }}>
            {t('detail.initialLabel')}
          </span>
          <div className="reveal-late num absolute right-0 text-right" style={{ top: tagTop }}>
            <b className="block text-[20px] font-bold [font-stretch:75%] leading-none">{fmtNum(last.equity)}</b>
            {lastLabel && <span className="text-[12px] mute">{lastLabel}</span>}
          </div>
          <span className="absolute left-0 -bottom-5 text-[12px] mute">{axDay(points[0].wakeTime)}</span>
          <span className="absolute right-0 -bottom-5 text-[12px] mute">{axDay(last.wakeTime)}</span>
        </>
      )}
    </div>
  );
}
