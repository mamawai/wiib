import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { DailyGridCell } from './DailyGrid';
import { useCountUp } from '../hooks/useCountUp';
import { useStagger } from '../hooks/useStagger';
import { cn, fmtDate, fmtSignedUsd } from '../lib/utils';

interface Props {
  cells: DailyGridCell[];
  /** 当前月份 yyyy-MM，受控——翻月要去后端拉那个月的数据，月份状态只能在外面 */
  month: string;
  onMonthChange: (month: string) => void;
  selectedDate?: string;
  onSelectDate: (date: string) => void;
  className?: string;
}

// 周一起头，存词表 key 不存文案（数组在组件外，拿不到 t）
const WEEKDAY_KEYS = ['grid.mon', 'grid.tue', 'grid.wed', 'grid.thu', 'grid.fri', 'grid.sat', 'grid.sun'];

const pad2 = (n: number) => String(n).padStart(2, '0');

/**
 * 首页月度盈亏格：一格一天，赚是墨色深浅、亏是橙色深浅，越深盈亏越大。
 * 点有数的格子下钻当天明细；当天及以前没快照的日子画虚线空格，未来的日子只占位。
 */
export function HomeMonthGrid({ cells, month, onMonthChange, selectedDate, onSelectDate, className }: Props) {
  const { t, i18n } = useTranslation('home');
  const gridRef = useStagger<HTMLDivElement>();

  const [year, mon] = month.split('-').map(Number);
  const today = fmtDate();

  const byDate = useMemo(() => new Map(cells.map(c => [c.date, c])), [cells]);
  // 当月最大 |盈亏|，格子颜色按它归一；整月都是 0 时给 1 兜住除法
  const maxAbs = useMemo(() => Math.max(...cells.map(c => Math.abs(c.pnl)), 0) || 1, [cells]);
  const total = useMemo(() => cells.reduce((s, c) => s + c.pnl, 0), [cells]);
  const totalRef = useCountUp<HTMLSpanElement>(total, fmtSignedUsd);

  const days = new Date(year, mon, 0).getDate();
  const lead = (new Date(year, mon - 1, 1).getDay() + 6) % 7;   // 周一首列偏移

  // 纯算术翻月，不绕 Date：构造出来的"某月1号本地时间"再转东八区可能跨月，翻着翻着就串了
  const shift = (delta: number) => {
    const n = year * 12 + (mon - 1) + delta;
    onMonthChange(`${Math.floor(n / 12)}-${pad2((n % 12) + 1)}`);
  };

  const label = new Date(year, mon - 1, 1)
    .toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, { year: 'numeric', month: 'long' });

  const arrow = 'inline-flex text-muted-foreground hover:text-foreground transition-colors cursor-pointer';
  const cellBase = 'h-9 flex items-end px-[5px] py-1 text-[11px] font-semibold';

  return (
    <div className={cn('flex flex-col', className)}>
      <div className="flex items-baseline justify-between mb-2">
        <b className="text-[15px] font-extrabold">{t('grid.title')}</b>
        <span className="flex items-center gap-2 text-[13px] text-muted-foreground">
          <button className={arrow} aria-label={t('grid.prevMonth')} onClick={() => shift(-1)}>
            <ChevronLeft className="w-[13px] h-[13px]" />
          </button>
          {label}
          <button className={arrow} aria-label={t('grid.nextMonth')} onClick={() => shift(1)}>
            <ChevronRight className="w-[13px] h-[13px]" />
          </button>
        </span>
      </div>

      <div ref={gridRef} className="grid grid-cols-7 gap-1">
        {WEEKDAY_KEYS.map(k => (
          <div key={k} className="text-[11px] text-muted-foreground text-center pb-0.5">{t(k)}</div>
        ))}
        {Array.from({ length: lead }, (_, i) => <div key={`b${i}`} className="h-9" />)}
        {Array.from({ length: days }, (_, i) => {
          const day = i + 1;
          const date = `${year}-${pad2(mon)}-${pad2(day)}`;
          const cell = byDate.get(date);
          if (!cell) {
            // 未来的日子只占位；今天和以前缺快照的画虚线空格
            return (
              <div key={date} className={cn(cellBase, date <= today && 'border border-dashed border-border text-muted-foreground')}>
                {date <= today ? day : ''}
              </div>
            );
          }
          const k = Math.abs(cell.pnl) / maxAbs;
          const mix = cell.pnl >= 0
            ? `color-mix(in oklab, var(--color-foreground) ${Math.round(14 + k * 86)}%, var(--color-card-2))`
            : `color-mix(in oklab, var(--color-primary) ${Math.round(18 + k * 82)}%, var(--color-card-2))`;
          return (
            <button
              key={date}
              style={{ background: mix }}
              title={t('grid.cellTitle', { day, pnl: fmtSignedUsd(cell.pnl, 0) })}
              onClick={() => onSelectDate(date)}
              className={cn(
                cellBase, 'cursor-pointer',
                k > .55 ? 'text-background' : 'text-foreground',
                date === selectedDate && 'outline-2 outline-primary',
              )}
            >
              {day}
            </button>
          );
        })}
      </div>

      <div className="flex justify-end mt-auto pt-2.5 text-[12px]">
        {/* 整月一格都没有时给「—」不给 +$0.00；滚数那颗一直挂着，没数就藏起来 */}
        <b className="num font-bold text-foreground whitespace-nowrap">
          <span ref={totalRef} className={cn(!cells.length && 'hidden')} />
          {!cells.length && '—'}
        </b>
      </div>
    </div>
  );
}
