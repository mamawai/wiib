import { Dialog, DialogContent, DialogHeader } from './ui/dialog';
import { cn, fmtMoney } from '../lib/utils';
import type { AssetSnapshot } from '../types';

/** 五分类日差，字段直接来自快照 DTO，不用再算 */
const BUCKETS = [
  { key: 'dailyBstockProfit', label: '股票' },
  { key: 'dailyCryptoProfit', label: '币' },
  { key: 'dailyCommodityProfit', label: '大宗' },
  { key: 'dailyPredictionProfit', label: '预测' },
  { key: 'dailyGameProfit', label: '游戏' },
] as const;

interface Props {
  /** 选中的日期 yyyy-MM-dd；null 即关闭 */
  date: string | null;
  /** 当天快照。父组件手上已有整月数据，没必要为了一天再问一次后端 */
  snapshot: AssetSnapshot | null;
  onClose: () => void;
}

/**
 * 月度网格点某一天的下钻弹窗：当天赚亏多少，以及这个数由哪几类拼出来的。
 * 数据全部来自父组件已有的当月快照，弹窗自己不发请求。
 */
export function DayDetailModal({ date, snapshot, onClose }: Props) {
  if (!date) return null;

  const pnl = snapshot?.dailyProfit ?? 0;
  const up = pnl >= 0;
  const title = new Date(`${date}T00:00:00`).toLocaleDateString('zh-CN', {
    year: 'numeric', month: 'long', day: 'numeric', weekday: 'long',
  });

  return (
    <Dialog open onClose={onClose} className="max-w-sm">
      <DialogHeader className="pb-3 border-b border-border/40">
        <div className="text-xs text-muted-foreground">{title}</div>
        <div className="flex items-baseline gap-2 mt-1">
          <span className={cn('num text-2xl font-black', up ? 'text-gain' : 'text-loss')}>
            {up ? '+' : ''}{fmtMoney(pnl)}
          </span>
          {snapshot?.dailyProfitPct != null && (
            <span className={cn('num text-xs font-bold', up ? 'text-gain' : 'text-loss')}>
              {up ? '+' : ''}{snapshot.dailyProfitPct.toFixed(2)}%
            </span>
          )}
        </div>
        <div className="text-[11px] text-muted-foreground mt-0.5">当日盈亏 · 全账户 · 快照口径仅供参考</div>
      </DialogHeader>

      <DialogContent className="pt-3">
        <div className="space-y-2">
          {BUCKETS.map(({ key, label }) => {
            const v = snapshot?.[key] ?? 0;
            return (
              <div key={key} className="flex items-baseline justify-between">
                <span className="text-xs text-muted-foreground">{label}</span>
                {v === 0 ? (
                  <span className="num text-sm text-muted-foreground/40">—</span>
                ) : (
                  <span className={cn('num text-sm font-bold', v > 0 ? 'text-gain' : 'text-loss')}>
                    {v > 0 ? '+' : ''}{fmtMoney(v)}
                  </span>
                )}
              </div>
            );
          })}
        </div>
      </DialogContent>
    </Dialog>
  );
}
