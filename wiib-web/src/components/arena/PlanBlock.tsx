import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { ClipboardList } from 'lucide-react';
import { fmtDateTime, fmtNum } from '../../lib/utils';
import type { AiTraderPlanView, PlanRevision } from '../../types';

/** 持仓的交易计划卡：论点/失效条件/原始快照 + 修订历史——退出纪律的公开凭证。 */
export function PlanBlock({ plan }: { plan: AiTraderPlanView }) {
  const { t } = useTranslation('ai');
  const revisions = useMemo<PlanRevision[]>(() => {
    try {
      return plan.revisionsJson ? JSON.parse(plan.revisionsJson) as PlanRevision[] : [];
    } catch {
      return [];
    }
  }, [plan.revisionsJson]);

  return (
    <div className="mt-1.5 rounded border border-primary/25 bg-primary/5 px-2.5 py-2 space-y-1 text-[11px] leading-relaxed">
      <div className="flex items-center gap-1.5 flex-wrap">
        <ClipboardList className="w-3 h-3 text-primary" />
        <span className="font-black text-primary">{t('plan.title')}</span>
        {plan.playType && <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-primary/15 text-primary">{plan.playType}</span>}
        <span className="text-muted-foreground/80 num ml-auto">{t('plan.setAt', { time: fmtDateTime(plan.openedWakeTime) })}</span>
      </div>
      {plan.signalsUsed && <p className="text-muted-foreground">{t('plan.basis', { s: plan.signalsUsed })}</p>}
      <p><span className="font-bold text-foreground">{t('plan.invalidation')}</span>{plan.invalidationCondition}</p>
      {/* 间距走 gap 不用空格字符：末项缺席时不会拖着个尾巴，窄屏也能换行 */}
      <p className="text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
        {plan.entryPrice != null && <span>{t('plan.entry')} {fmtNum(plan.entryPrice)}</span>}
        {plan.stopLossPrice != null && <span>{t('plan.origSl')} {fmtNum(plan.stopLossPrice)}</span>}
        {plan.takeProfitPrice != null && <span>{t('plan.target')} {fmtNum(plan.takeProfitPrice)}</span>}
      </p>
      {revisions.length > 0 && (
        <div className="border-t border-primary/15 pt-1 space-y-0.5">
          {revisions.map((r, i) => (
            <p key={i} className="text-muted-foreground">
              <span className="num text-muted-foreground/70">{fmtDateTime(r.time)}</span>
              <span className="font-bold text-foreground/80 mx-1">{r.type}</span>
              {r.change && <span className="num">{r.change}</span>}
              {r.reason && <span> —— {r.reason}</span>}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
