import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowUpRight, MousePointerClick, Zap } from 'lucide-react';
import { cn, fmtDateTime, fmtDuration, fmtNum } from '../../lib/utils';
import type { TradeDecisionRef, TradeRecordView } from '../../types';
import { PlanBlock } from './PlanBlock';
import { ReasoningFold } from './ReasoningFold';

/**
 * 了结方式徽章色：止盈/止损是计划兑现，主动平仓是模型的手，强平是事故。
 * 键是后端下发的语言无关码（ReviewMaterialAssembler.closeMannerKey），文案另查词表。
 */
const CLOSE_MANNER_TONE: Record<string, string> = {
  takeProfit: 'bg-gain/15 text-gain', stopLoss: 'bg-loss/15 text-loss',
  manual: 'bg-primary/15 text-primary', liquidated: 'bg-loss/25 text-loss',
};

/** 单笔已了结交易：头行（币种·多空·了结方式·入场→出场·盈亏）→ 计划（论点/失效条件/修订史）→ 开仓/平仓决策折叠 */
export function TradeCard({ r }: { r: TradeRecordView }) {
  const { t } = useTranslation('ai');
  const isLong = r.side === 'LONG';
  const pnl = r.closedPnl;
  return (
    <div className="rounded-md border border-border bg-card p-2.5 text-[11px] space-y-1.5">
      <div className="flex items-center gap-2 flex-wrap">
        {isLong ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />}
        <span className="font-black text-xs">{r.symbol}</span>
        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', isLong ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
          {isLong ? t('term.long') : t('term.short')}{r.leverage != null && ` ${r.leverage}x`}
        </span>
        <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', CLOSE_MANNER_TONE[r.closeMannerKey] ?? 'bg-muted text-muted-foreground')}>
          {t(`trade.closeManner.${r.closeMannerKey}`)}
        </span>
        <span className="text-muted-foreground num">{fmtNum(r.entryPrice)} → {r.closedPrice != null ? fmtNum(r.closedPrice) : '—'}</span>
        <span className={cn('ml-auto num font-black', pnl == null ? 'text-muted-foreground' : pnl >= 0 ? 'text-gain' : 'text-loss')}>
          {pnl == null ? '—' : `${pnl >= 0 ? '+' : ''}${fmtNum(pnl)}`}
        </span>
      </div>
      <div className="text-muted-foreground num flex flex-wrap gap-x-3 gap-y-0.5">
        <span>{t('trade.openedAt', { time: fmtDateTime(r.openedAt) })}</span>
        <span>{t('trade.closedAt', { time: fmtDateTime(r.closedAt) })}</span>
        <span>{t('trade.held', { d: fmtDuration(r.openedAt, r.closedAt) })}</span>
      </div>
      {r.plan ? <PlanBlock plan={r.plan} /> : <p className="text-muted-foreground/70">{t('trade.noPlan')}</p>}
      {r.openDecision && <DecisionRefBlock label={t('trade.openDecision')} d={r.openDecision} />}
      {r.closeDecision && <DecisionRefBlock label={t('trade.closeDecision')} d={r.closeDecision} />}
    </div>
  );
}

/** 交易记录挂的那一轮决策：标签 + 时刻 + 一句话理由（平仓才有）+ 全文折叠 */
function DecisionRefBlock({ label, d }: { label: string; d: TradeDecisionRef }) {
  const { t } = useTranslation('ai');
  return (
    <div className="rounded border border-border/60 bg-card-2/40 px-2.5 py-2 space-y-1 leading-relaxed">
      <div className="flex items-center gap-1.5 flex-wrap">
        <span className="font-black">{label}</span>
        <span className="text-muted-foreground/80 num">{fmtDateTime(d.wakeTime)}</span>
        {d.kind === 'ALERT' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">
            <Zap className="w-3 h-3" />{t('decision.alertRound')}
          </span>
        )}
        {d.kind === 'MANUAL' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-teal-500/15 text-teal-600">
            <MousePointerClick className="w-3 h-3" />{t('decision.manualRound')}
          </span>
        )}
        {d.reason && <span className="text-muted-foreground">—— {d.reason}</span>}
      </div>
      <ReasoningFold reasoning={d.reasoning} />
    </div>
  );
}
