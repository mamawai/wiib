import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn, fmtDate } from '../lib/utils';

/** 一个格子要的最少信息。tradeCount 不传就不渲染笔数那行 */
export interface DailyGridCell {
  date: string; // yyyy-MM-dd
  pnl: number;
  tradeCount?: number;
}

interface Props {
  cells: DailyGridCell[];
  /** 当前月份 yyyy-MM，受控——首页翻月要去后端拉那个月的数据，月份状态只能在外面 */
  month: string;
  onMonthChange: (month: string) => void;
  selectedDate?: string;
  onSelectDate: (date: string) => void;
  /** 外壳样式交给调用方：这组件只管月历怎么画，不管它装在什么盒子里 */
  className?: string;
}

// 周一起头，存词表 key 不存文案（数组在组件外，拿不到 t）
const WEEKDAY_KEYS = ['grid.mon', 'grid.tue', 'grid.wed', 'grid.thu', 'grid.fri', 'grid.sat', 'grid.sun'];
// 热力分档（绿/红各4级，越深盈亏越大）；封顶 /70 保证格内数字可读
const GAIN_BG = ['bg-gain/15', 'bg-gain/30', 'bg-gain/50', 'bg-gain/70'];
const LOSS_BG = ['bg-loss/15', 'bg-loss/30', 'bg-loss/50', 'bg-loss/70'];

/** 格子就那么点宽，上千的数压成 1.2k 才塞得下 */
function fmtCell(v: number) {
  const a = Math.abs(v);
  return `${v >= 0 ? '+' : '-'}${a >= 1000 ? `${(a / 1000).toFixed(1)}k` : a.toFixed(1)}`;
}

/**
 * 日交易网格（月历热力图）。每格=一天：上日期、中当天净盈亏、下笔数；
 * 绿赚红亏、颜色深浅表盈亏大小。点有数据的格子下钻当天明细。
 * <p>
 * 今天单独一态：首页口径里当天还没落快照（盈亏在旁边的今日盈亏卡上），
 * 画成虚线"今"格而不是跟没数据的日子一样摆个灰点，免得被读成"今天白干了"。
 */
export function DailyGrid({ cells, month, onMonthChange, selectedDate, onSelectDate, className }: Props) {
  const { t } = useTranslation('home');

  const byDate = useMemo(() => {
    const m = new Map<string, DailyGridCell>();
    cells.forEach((c) => m.set(c.date, c));
    return m;
  }, [cells]);

  const today = fmtDate();
  const [year, mon] = month.split('-').map(Number);

  // 当月最大|盈亏|，用于热力分档
  const maxAbs = useMemo(() => {
    let mx = 0;
    cells.forEach((c) => { if (c.date.slice(0, 7) === month) mx = Math.max(mx, Math.abs(c.pnl)); });
    return mx || 1;
  }, [cells, month]);

  // 周一为首列的当月格子（前置空位对齐）
  const grid = useMemo(() => {
    const first = new Date(year, mon - 1, 1);
    const lead = (first.getDay() + 6) % 7; // 周一首列偏移
    const days = new Date(year, mon, 0).getDate();
    const arr: (string | null)[] = [];
    for (let i = 0; i < lead; i++) arr.push(null);
    for (let d = 1; d <= days; d++) {
      arr.push(`${year}-${String(mon).padStart(2, '0')}-${String(d).padStart(2, '0')}`);
    }
    return arr;
  }, [year, mon]);

  // 纯算术翻月，不绕 Date：构造出来的"某月1号本地时间"再转东八区可能跨月，翻着翻着就串了
  const shiftMonth = (delta: number) => {
    const total = year * 12 + (mon - 1) + delta;
    onMonthChange(`${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`);
  };

  const level = (absPnl: number) => Math.min(3, Math.floor((absPnl / maxAbs) * 4));

  return (
    <div className={className}>
      {/* 月份切换 */}
      <div className="flex items-center justify-between mb-3">
        <button onClick={() => shiftMonth(-1)} className="w-7 h-7 rounded-md border border-border hover:bg-surface-hover flex items-center justify-center text-muted-foreground hover:text-primary transition-colors cursor-pointer">
          <ChevronLeft className="w-4 h-4" />
        </button>
        <span className="num text-sm font-bold">{month}</span>
        <button onClick={() => shiftMonth(1)} className="w-7 h-7 rounded-md border border-border hover:bg-surface-hover flex items-center justify-center text-muted-foreground hover:text-primary transition-colors cursor-pointer">
          <ChevronRight className="w-4 h-4" />
        </button>
      </div>

      {/* 星期表头 */}
      <div className="grid grid-cols-7 gap-1 mb-1">
        {WEEKDAY_KEYS.map((k) => (
          <div key={k} className="text-center text-[10px] text-muted-foreground font-bold">{t(k)}</div>
        ))}
      </div>

      {/* 日格子 */}
      <div className="grid grid-cols-7 gap-1">
        {grid.map((date, i) => {
          if (!date) return <div key={`b${i}`} />;
          const cell = byDate.get(date);
          const day = Number(date.slice(8));
          const has = !!cell && (cell.pnl !== 0 || (cell.tradeCount ?? 0) > 0);
          const isToday = date === today;
          const future = date > today;
          const up = cell ? cell.pnl >= 0 : true;
          const heat = has ? (up ? GAIN_BG : LOSS_BG)[level(Math.abs(cell!.pnl))] : '';
          const selected = date === selectedDate;
          return (
            <button
              key={date}
              disabled={!has}
              onClick={() => has && onSelectDate(date)}
              className={cn(
                // 6/5 而不是正方形：一列 6 行，正方形会把卡撑得比左边净值卡高出一大截
                'aspect-6/5 rounded-lg p-1 flex flex-col items-center justify-center transition-all text-center',
                has ? 'cursor-pointer hover:ring-2 hover:ring-primary/40' : 'cursor-default',
                has ? heat : future ? 'bg-muted/5' : 'bg-muted/20',
                // 今天还没结算，虚线框把它跟"有数据"和"没数据"都区分开
                isToday && !has && 'border border-dashed border-primary/40 bg-primary/5',
                selected && 'ring-2 ring-primary',
              )}
            >
              <span className={cn('text-[9px] leading-none self-start pl-0.5',
                future ? 'text-muted-foreground/40' : 'text-muted-foreground')}>{day}</span>
              {has ? (
                <>
                  <span className={cn('text-[11px] font-black tabular-nums leading-tight', up ? 'text-gain' : 'text-loss')}>
                    {fmtCell(cell!.pnl)}
                  </span>
                  {cell!.tradeCount != null && (
                    <span className="text-[8px] text-muted-foreground leading-none">{t('grid.trades', { count: cell!.tradeCount })}</span>
                  )}
                </>
              ) : isToday ? (
                <span className="text-[10px] font-bold text-primary/70 leading-none">{t('grid.today')}</span>
              ) : (
                <span className={cn('text-xs', future ? 'text-transparent' : 'text-muted-foreground/30')}>·</span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}
