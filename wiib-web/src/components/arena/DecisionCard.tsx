import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { GraduationCap, MousePointerClick, NotebookPen, Zap } from 'lucide-react';
import i18n from '../../i18n';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../../lib/utils';
import type { AiTraderDecisionView } from '../../types';
import { ReasoningFold } from './ReasoningFold';

/** 徽章存的是词表 key：模块级常量只算一次，存翻好的字面量切了语言也不会变（下面几张表同理） */
const DECISION_STATUS: Record<string, { labelKey: string; tone: string }> = {
  OK: { labelKey: 'decision.ok', tone: 'bg-primary/15 text-primary' },
  ERROR: { labelKey: 'decision.error', tone: 'bg-loss/15 text-loss' },
  SKIPPED: { labelKey: 'decision.skipped', tone: 'bg-muted text-muted-foreground' },
};

/** 工具名人话化：交易动作独立成行展示参数，数据查询弱化成 chip——一眼分清"看了什么"和"做了什么" */
const TOOL_KEY: Record<string, string> = {
  open_position: 'tool.openPosition', close_position: 'tool.closePosition', set_stop_loss: 'tool.setStopLoss',
  set_take_profit: 'tool.setTakeProfit', cancel_order: 'tool.cancelOrder', write_plan: 'tool.writePlan',
  get_account: 'tool.getAccount', klines: 'tool.klines', indicators: 'tool.indicators',
  market_snapshot: 'tool.marketSnapshot', funding_history: 'tool.fundingHistory',
  orderbook_depth: 'tool.orderbookDepth', option_iv: 'tool.optionIv', news_search: 'tool.newsSearch',
};

/** 工具的展示名；表里没有的（后端加了新工具）原样显示 id */
function toolName(tool: string): string {
  const key = TOOL_KEY[tool];
  return key ? i18n.t(`ai:${key}`) : tool;
}
const TRADE_TOOLS = new Set(['open_position', 'close_position', 'set_stop_loss', 'set_take_profit', 'cancel_order', 'write_plan']);

interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
}

/** 交易动作的关键参数一行话（按工具挑重点，不倒整个 JSON）。词表在函数体里现查，切语言即变 */
function tradeArgsSummary(a: ActionRow): string {
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  const tr = (key: string, vars?: Record<string, string>) => i18n.t(`ai:${key}`, vars ?? {});
  switch (a.tool) {
    case 'open_position': {
      const parts = [g('symbol'), g('side') === 'LONG' ? tr('args.long') : g('side') === 'SHORT' ? tr('args.short') : g('side'),
        g('quantity') && tr('args.qty', { n: g('quantity') }), g('leverage') && `${g('leverage')}x`,
        g('stopLossPrice') && tr('args.sl', { p: g('stopLossPrice') }),
        g('takeProfitPrice') && tr('args.tp', { p: g('takeProfitPrice') }),
        g('playType')];
      return parts.filter(Boolean).join(' · ');
    }
    case 'close_position':
      return [tr('args.position', { id: g('positionId') }), g('quantity') && tr('args.qty', { n: g('quantity') }), g('reason')]
        .filter(Boolean).join(' · ');
    case 'set_stop_loss':
      return [`→${g('stopLossPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'set_take_profit':
      return [`→${g('takeProfitPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'write_plan':
      return [g('playType'), g('invalidationCondition') && tr('args.invalidation', { c: g('invalidationCondition') })]
        .filter(Boolean).join(' · ');
    case 'cancel_order':
      return tr('args.order', { id: g('orderId') });
    default:
      return '';
  }
}

/** 单条决策卡：时间/权益 + 数据工具chip + 交易动作行（参数/拒因） + 推理 markdown 折叠——竞技场的观赏核心。 */
export function DecisionCard({ d }: { d: AiTraderDecisionView }) {
  const { t } = useTranslation('ai');
  // 复盘行不是交易决策，徽章与配色单独一套：reviewer 的每日日志，时间线上要一眼认出
  // 学习行同样不交易，但来源不同（复盘看自己、学习看同侪），再分一套色——两种日志行混在时间线上要能一眼分清
  const meta = d.kind === 'REVIEW'
    ? (d.status === 'OK'
        ? { labelKey: 'term.dailyReview', tone: 'bg-violet-500/15 text-violet-500' }
        : { labelKey: 'decision.reviewFailed', tone: 'bg-loss/15 text-loss' })
    : d.kind === 'LEARN'
      ? (d.status === 'OK'
          ? { labelKey: 'term.peerLearn', tone: 'bg-sky-500/15 text-sky-500' }
          : { labelKey: 'decision.learnFailed', tone: 'bg-loss/15 text-loss' })
      : DECISION_STATUS[d.status] ?? DECISION_STATUS.OK;
  const actions = useMemo<ActionRow[]>(() => {
    try {
      return d.actionsJson ? JSON.parse(d.actionsJson) as ActionRow[] : [];
    } catch {
      return [];
    }
  }, [d.actionsJson]);
  const dataCalls = actions.filter(a => !TRADE_TOOLS.has(a.tool));
  const trades = actions.filter(a => TRADE_TOOLS.has(a.tool));

  return (
    <div className={cn('rounded-md border bg-card p-3 space-y-2',
      d.kind === 'REVIEW' ? 'border-violet-500/35 bg-violet-500/[0.04]'
        : d.kind === 'LEARN' ? 'border-sky-500/35 bg-sky-500/[0.04]' : 'border-border')}>
      <div className="flex items-center gap-2 flex-wrap">
        <span className={cn('inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded', meta.tone)}>
          {d.kind === 'REVIEW' && <NotebookPen className="w-3 h-3" />}
          {d.kind === 'LEARN' && <GraduationCap className="w-3 h-3" />}{t(meta.labelKey)}
        </span>
        {/* 警报唤醒凸显：这条不是例行K线节奏，是哨兵在极端波动时叫醒的 */}
        {d.kind === 'ALERT' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">
            <Zap className="w-3 h-3" />{t('decision.alert')}
          </span>
        )}
        {/* 手动唤醒凸显：扳机在主人手里（对话轨 wake_trader），不是例行K线节奏 */}
        {d.kind === 'MANUAL' && (
          <span className="inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded bg-teal-500/15 text-teal-600">
            <MousePointerClick className="w-3 h-3" />{t('term.manualWake')}
          </span>
        )}
        <span className="text-[11px] num text-muted-foreground">{fmtDateTime(d.wakeTime)}</span>
        {d.equity != null && (
          <span className="text-[11px] text-muted-foreground">{t('term.equity')} <span className="num font-bold text-foreground">{fmtNum(d.equity)}</span></span>
        )}
        <span className="ml-auto text-[10px] text-muted-foreground/70 num">
          {[
            d.latencyMs != null && `${(d.latencyMs / 1000).toFixed(1)}s`,
            // 复盘无工具（单次调用）不显示"0次工具"占位；学习是 ReactAgent 有 peer_insights 工具，照常显示
            d.kind !== 'REVIEW' && t('detail.toolCalls', { count: d.toolCalls }),
            d.modelCalls != null && t('detail.modelCalls', { count: d.modelCalls }),
            // token 为 null＝上游端点没报 usage，显示「—」而不是 0：0 会被读成"这轮没花钱"
            d.modelCalls != null && `${d.totalTokens == null ? '—' : fmtTokens(d.totalTokens)} tokens`,
          ].filter(Boolean).join(' · ')}
        </span>
      </div>

      {/* 数据查询：弱化 chip，交代"它看了什么"再决策 */}
      {dataCalls.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {dataCalls.map((a, i) => (
            <span key={i} className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground/80"
                  title={a.error || undefined}>
              {toolName(a.tool)}{a.status === 'error' && ' ✕'}
            </span>
          ))}
        </div>
      )}

      {/* 交易动作：独立成行带参数与拒因——"它做了什么"是时间线的主角 */}
      {trades.length > 0 && (
        <div className="space-y-1">
          {trades.map((a, i) => {
            const failed = a.rejected || a.status === 'error';
            // unknown=重发确认后仍没问到结果，可能成交也可能没有。不单独标出来就跟成交长得一模一样，
            // 而这条时间线是对所有人公开的账面事实
            const unknown = a.status === 'unknown';
            return (
              <div key={i} className={cn('rounded border px-2 py-1.5 text-[11px] leading-relaxed',
                failed ? 'border-loss/40 bg-loss/5'
                  : unknown ? 'border-warning/40 bg-warning/5' : 'border-border bg-card-2/60')}>
                <span className={cn('font-black mr-1.5',
                  failed ? 'text-loss' : unknown ? 'text-warning' : 'text-foreground')}>
                  {toolName(a.tool)}
                  {a.rejected ? t('trade.rejected') : a.status === 'error' ? t('trade.errored') : unknown ? t('trade.unknown') : ''}
                </span>
                <span className="text-muted-foreground">{tradeArgsSummary(a)}</span>
                {a.rejected && <p className="mt-0.5 text-loss">{a.rejected}</p>}
                {a.error && <p className="mt-0.5 text-loss">{a.error}</p>}
              </div>
            );
          })}
        </div>
      )}

      {d.status === 'ERROR' && d.error && (
        <p className="text-[11px] text-loss leading-relaxed">{d.error}</p>
      )}

      <ReasoningFold reasoning={d.reasoning} />
    </div>
  );
}
