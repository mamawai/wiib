import type { ReactNode } from 'react';
import { cn } from '../../lib/utils';

export interface StripCell {
  label: ReactNode;
  value: ReactNode;
  /** 数值配色，涨跌用 text-gain / text-loss；不传就是常规前景色 */
  tone?: string;
}

/**
 * 记分牌下面那条仪表条：一格一个指标，微标签在上、数在下。
 * PC 六格一行等宽，窄屏三格两行——格子之间的分隔线靠 gap-px 露出底色，换行也不用另写横线。
 */
export function ScoreStrip({ cells }: { cells: StripCell[] }) {
  return (
    <div className="rounded-lg pt-card overflow-hidden">
      <div className="grid grid-cols-3 md:grid-cols-6 gap-px bg-border">
        {cells.map((c, i) => (
          <div key={i} className="bg-card px-3 py-2 min-w-0">
            <div className="microlabel truncate">{c.label}</div>
            <b className={cn('num block mt-0.5 text-[13px] font-extrabold leading-tight whitespace-nowrap', c.tone)}>
              {c.value}
            </b>
          </div>
        ))}
      </div>
    </div>
  );
}
