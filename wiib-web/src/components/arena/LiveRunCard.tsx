import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Activity } from 'lucide-react';
import { useTraderLive } from '../../hooks/useTraderLive';
import { cn } from '../../lib/utils';
import { WakeTraceView } from './WakeTraceView';

/**
 * 详情页的现场卡：唤醒进行中才出现，头部脉冲点 + 已用秒/预算每秒走字，主体是逐帧长出来的过程视图。
 * 现场流的 hook 挂在这张卡里：token 帧只重渲染这张卡，不带着整页的曲线和时间线一起刷。
 * 一轮结束只把 endedAt 交给 onEnded，页面据此重拉；running=false 整卡不渲染（过程归时间线卡的"过程"按钮）
 */
export function LiveRunCard({ traderId, onEnded, className }: {
  traderId: number; onEnded: (endedAt: number) => void; className?: string;
}) {
  const { t } = useTranslation('ai');
  const { live, running, endedAt } = useTraderLive(traderId);
  useEffect(() => {
    if (endedAt) onEnded(endedAt);
  }, [endedAt, onEnded]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [running]);
  if (!live || !running) return null;
  const used = Math.max(0, Math.round((now - live.startedAt) / 1000));
  return (
    <div className={cn('rounded-lg pt-card p-4 flex flex-col gap-3', className)}>
      <div className="flex items-center gap-2">
        <Activity className="w-3 h-3 text-primary" />
        <span className="microlabel">{t('live.title')}</span>
        <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
        {/* 超预算标红：后端到点会催模型收尾 */}
        <span className={cn('ml-auto num text-[11px]', used > live.budgetSeconds ? 'text-loss' : 'text-muted-foreground')}>
          {t('live.budget', { used, budget: live.budgetSeconds })}
        </span>
      </div>
      <WakeTraceView trace={live} running />
    </div>
  );
}
