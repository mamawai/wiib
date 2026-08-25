import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowRight, ArrowUpRight } from 'lucide-react';
import { cn, fmtDateTime, fmtDuration, fmtNum } from '../../lib/utils';
import type { TradeDecisionRef, TradeRecordView } from '../../types';
import { PlanBlock } from './PlanBlock';

/**
 * 了结方式徽章色：止盈/止损是计划兑现，主动平仓是模型的手，强平是事故。
 * 键是后端下发的语言无关码（ReviewMaterialAssembler.closeMannerKey），文案另查词表。
 */
const CLOSE_MANNER_TONE: Record<string, string> = {
  takeProfit: 'bg-gain/15 text-gain', stopLoss: 'bg-loss/15 text-loss',
  manual: 'bg-primary/15 text-primary', liquidated: 'bg-loss/25 text-loss',
};

/**
 * 单笔已了结交易：头行（币种·多空·了结方式·入场→出场·盈亏）→ 开/平时刻 → 计划（论点/失效条件/修订史）
 * → 开仓/平仓决策的跳转链接。决策全文不在这里重复——时间线才是它的家，onJump 把人带过去。
 */
export function TradeCard({ r, onJump }: { r: TradeRecordView; onJump: (d: TradeDecisionRef) => void }) {
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
      {(r.openDecision || r.closeDecision) && (
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5 leading-relaxed">
          {r.openDecision && <DecisionLink label={t('trade.openDecision')} d={r.openDecision} onJump={onJump} />}
          {r.closeDecision && <DecisionLink label={t('trade.closeDecision')} d={r.closeDecision} onJump={onJump} />}
          {/* 平仓那一轮的一句话理由：止损/止盈带走没有这一轮，头行的了结方式徽章已经说明 */}
          {r.closeDecision?.reason && <span className="text-muted-foreground">—— {r.closeDecision.reason}</span>}
        </div>
      )}
    </div>
  );
}

function DecisionLink({ label, d, onJump }: { label: string; d: TradeDecisionRef; onJump: (d: TradeDecisionRef) => void }) {
  return (
    <button type="button" onClick={() => onJump(d)}
            className="inline-flex items-center gap-0.5 font-bold text-primary hover:underline">
      {label} · <span className="num">{fmtDateTime(d.wakeTime)}</span><ArrowRight className="w-3 h-3" />
    </button>
  );
}
