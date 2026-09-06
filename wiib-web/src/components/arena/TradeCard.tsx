import { useTranslation } from 'react-i18next';
import { ArrowRight, Eye, EyeOff } from 'lucide-react';
import { cn, fmtDateTime, fmtDuration, fmtNum } from '../../lib/utils';
import type { TradeDecisionRef, TradeRecordView } from '../../types';
import { PlanBlock } from './PlanBlock';

/**
 * 了结方式徽章的框色：止盈绿、止损/强平红、主动平仓素框（模型的手）。
 * 键是后端下发的语言无关码（ReviewMaterialAssembler.closeMannerKey），文案另查词表。
 */
const CLOSE_MANNER_CHIP: Record<string, string> = {
  takeProfit: 'up', stopLoss: 'dn', manual: '', liquidated: 'dn',
};

/**
 * 单笔已了结交易：头行（币种·多空·了结方式·入场→出场·盈亏）→ 开/平时刻 → 计划（论点/失效条件/修订史）
 * → 开仓/平仓决策的跳转链接。决策全文不在这里重复——时间线才是它的家，onJump 把人带过去。
 * onToggleStale 只有主人视角才传：忽略开关只治理自己 AI 的教材，公开记录不动。
 */
export function TradeCard({ r, onJump, onToggleStale }: {
  r: TradeRecordView;
  onJump: (d: TradeDecisionRef) => void;
  onToggleStale?: (r: TradeRecordView) => void;
}) {
  const { t } = useTranslation('ai');
  const isLong = r.side === 'LONG';
  const pnl = r.closedPnl;
  // stale 视觉与开关同门槛（仅主人）：忽略是主人对自家教材的私人治理，公开视角的已了结列表与常人无异
  const stale = r.plan?.stale === true && onToggleStale != null;
  return (
    <div className={cn('py-5 border-b border-border', stale && 'opacity-60')}>
      <div className="flex items-center gap-2.5 flex-wrap">
        <b className="text-[17px] font-bold">{r.symbol}</b>
        <span className={cn('chip fill', isLong ? 'up' : 'dn')}>
          {t(isLong ? 'term.long' : 'term.short')}{r.leverage != null && ` ${r.leverage}x`}
        </span>
        <span className={cn('chip', CLOSE_MANNER_CHIP[r.closeMannerKey] ?? 'mute')}>
          {t(`trade.closeManner.${r.closeMannerKey}`)}
        </span>
        {stale && <span className="chip mute">{t('trade.staleBadge')}</span>}
        <span className="num mute">{fmtNum(r.entryPrice)} → {r.closedPrice != null ? fmtNum(r.closedPrice) : '—'}</span>
        <b className={cn('ml-auto num text-[18px] font-bold', pnl == null ? 'mute' : pnl >= 0 ? 'up' : 'dn')}>
          {pnl == null ? '—' : `${pnl >= 0 ? '+' : ''}${fmtNum(pnl)}`}
        </b>
        {onToggleStale && r.plan && (
          <button type="button" className="btn xs" title={t('trade.staleHint')} onClick={() => onToggleStale(r)}>
            {stale ? <Eye className="ic" /> : <EyeOff className="ic" />}
            {stale ? t('trade.staleUnmark') : t('trade.staleMark')}
          </button>
        )}
      </div>
      <div className="flex flex-wrap gap-x-3 gap-y-1 mt-2 text-[13px] mute num">
        <span>{t('trade.openedAt', { time: fmtDateTime(r.openedAt) })}</span>
        <span>{t('trade.closedAt', { time: fmtDateTime(r.closedAt) })}</span>
        <span>{t('trade.held', { d: fmtDuration(r.openedAt, r.closedAt) })}</span>
      </div>
      {r.plan ? <PlanBlock plan={r.plan} embedded /> : <p className="text-[13px] mute mt-2">{t('trade.noPlan')}</p>}
      {(r.openDecision || r.closeDecision) && (
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1.5 mt-3 text-[13px]">
          {r.openDecision && <DecisionLink label={t('trade.openDecision')} d={r.openDecision} onJump={onJump} />}
          {r.closeDecision && <DecisionLink label={t('trade.closeDecision')} d={r.closeDecision} onJump={onJump} />}
          {/* 平仓那一轮的一句话理由：止损/止盈带走没有这一轮，头行的了结方式徽章已经说明 */}
          {r.closeDecision?.reason && <span className="mute">—— {r.closeDecision.reason}</span>}
        </div>
      )}
    </div>
  );
}

function DecisionLink({ label, d, onJump }: { label: string; d: TradeDecisionRef; onJump: (d: TradeDecisionRef) => void }) {
  return (
    <button type="button" className="btn xs" onClick={() => onJump(d)}>
      <span className="num">{label} · {fmtDateTime(d.wakeTime)}</span><ArrowRight className="ic" />
    </button>
  );
}
