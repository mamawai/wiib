import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronRight, Loader2, MousePointerClick, Timer, Zap, type LucideIcon } from 'lucide-react';
import { cn, fmtDateTime, fmtNum, fmtTokens } from '../../lib/utils';
import type { WakeKind, WakeToolCall, WakeToolResult, WakeTrace } from '../../types';
import { Markdown } from '../Markdown';
import { TRADE_TOOL_SET, toolName, tradeArgsSummary } from './traderTools';

/** 唤醒类型徽章，配色与 DecisionCard 同类徽章一致；存词表 key 不存文案 */
const KIND_META: Record<WakeKind, { labelKey: string; tone: string; icon?: LucideIcon }> = {
  TRADE: { labelKey: 'decision.ok', tone: 'bg-primary/15 text-primary' },
  ALERT: { labelKey: 'decision.alert', tone: 'bg-amber-500/15 text-amber-600', icon: Zap },
  MANUAL: { labelKey: 'term.manualWake', tone: 'bg-teal-500/15 text-teal-600', icon: MousePointerClick },
};

/** 提示词 / 回执预览的等宽块 */
const PRE = 'font-mono text-[10.5px] leading-snug whitespace-pre-wrap break-words';
const PRE_BOX = 'rounded border border-border bg-card-2 px-2.5 py-2 max-h-72 overflow-y-auto text-foreground/85';

/** 数据工具的参数 k=v 一行；对象值压成 JSON */
function kvArgs(args: Record<string, unknown>): string {
  return Object.entries(args)
    .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
    .join(' ');
}

/**
 * 一条工具调用 + 它的回执。交易工具参数用 tradeArgsSummary 摘要，数据工具 k=v 列全；
 * 回执预览默认 3 行，点开全量（后端已截 2000 字）；被拒/出错整行红；跑着还没回执的转圈
 */
function ToolRow({ tc, result, running }: { tc: WakeToolCall; result: WakeToolResult | undefined; running: boolean }) {
  const { t } = useTranslation('ai');
  const [open, setOpen] = useState(false);
  const args = typeof tc.args === 'string' ? tc.args
    : TRADE_TOOL_SET.has(tc.name) ? tradeArgsSummary({ tool: tc.name, args: tc.args }) : kvArgs(tc.args);
  const failed = result != null && result.status !== 'ok';
  return (
    <div className={cn('rounded border px-2 py-1.5 text-[11px] leading-relaxed',
      failed ? 'border-loss/40 bg-loss/5' : 'border-border bg-card-2/60')}>
      <div className="flex items-start gap-1.5">
        <span className={cn('font-black shrink-0', failed ? 'text-loss' : 'text-foreground')}>
          {toolName(tc.name)}
          {result?.status === 'rejected' ? t('trade.rejected') : result?.status === 'error' ? t('trade.errored') : ''}
        </span>
        <span className="text-muted-foreground break-all min-w-0">{args}</span>
        {!result && running && <Loader2 className="ml-auto mt-0.5 w-3 h-3 animate-spin text-primary shrink-0" />}
      </div>
      {result?.preview && (
        <button type="button" onClick={() => setOpen(o => !o)} className="mt-1 w-full text-left">
          <span className="microlabel inline-flex items-center gap-0.5">
            <ChevronRight className={cn('w-2.5 h-2.5 transition-transform', open && 'rotate-90')} />{t('live.result')}
          </span>
          <pre className={cn(PRE, 'mt-0.5 text-muted-foreground', !open && 'line-clamp-3')}>{result.preview}</pre>
        </button>
      )}
    </div>
  );
}

/**
 * 一次唤醒的过程视图：信息行 → 提示词折叠（有才显示，只有主人拿得到）→ 按 call 分节的过程轨 → 收尾行。
 * 只吃 WakeTrace，不管数据来自实时流还是 trace 接口；running=true 时末节点亮、没回执的工具转圈
 */
export function WakeTraceView({ trace, running = false }: { trace: WakeTrace; running?: boolean }) {
  const { t } = useTranslation('ai');
  const [promptOpen, setPromptOpen] = useState(false);
  const kind = KIND_META[trace.kind];
  const KindIcon = kind.icon;
  const last = trace.calls.length;
  return (
    <div className="space-y-2.5">
      <div className="flex items-center gap-2 flex-wrap text-[11px] text-muted-foreground">
        <span className={cn('inline-flex items-center gap-0.5 text-[10px] font-bold px-1.5 py-0.5 rounded', kind.tone)}>
          {KindIcon && <KindIcon className="w-3 h-3" />}{t(kind.labelKey)}
        </span>
        <span className="num">{fmtDateTime(trace.startedAt, true)}</span>
        <span className="num inline-flex items-center gap-0.5"><Timer className="w-3 h-3" />{trace.budgetSeconds}s</span>
        <span>{t('term.equity')} <span className="num font-bold text-foreground">{fmtNum(trace.equity)}</span></span>
      </div>

      {trace.prompt && (
        <div>
          <button type="button" onClick={() => setPromptOpen(o => !o)}
                  className="flex items-center gap-1 text-[10px] font-bold text-muted-foreground hover:text-foreground">
            <ChevronRight className={cn('w-3 h-3 transition-transform', promptOpen && 'rotate-90')} />{t('live.prompt')}
          </button>
          {promptOpen && (
            <div className="mt-1.5 space-y-2">
              <div>
                <div className="microlabel mb-1">{t('live.systemPrompt')}</div>
                <pre className={cn(PRE, PRE_BOX)}>{trace.prompt.system}</pre>
              </div>
              <div>
                <div className="microlabel mb-1">{t('live.instruction')}</div>
                <pre className={cn(PRE, PRE_BOX)}>{trace.prompt.instruction}</pre>
              </div>
            </div>
          )}
        </div>
      )}

      {trace.calls.length > 0 && (
        <div className="ml-[5px] border-l-2 border-border pl-3.5 space-y-3 py-0.5">
          {trace.calls.map(c => {
            // 进行中＝还在跑且是最后一节：模型在出字，或工具在跑等回执
            const hot = running && c.n === last;
            return (
              <div key={c.n} className="relative">
                <span className={cn('absolute -left-[18.5px] top-[5px] w-[7px] h-[7px] rounded-full border-2',
                  hot ? 'bg-primary border-primary shadow-[0_0_6px_var(--color-primary)]' : 'bg-card border-border')} />
                <div className={cn('text-[11px] font-bold', hot ? 'text-foreground' : 'text-muted-foreground')}>
                  {t('live.callN', { n: c.n })}
                </div>
                {c.text && (
                  <div className="mt-1 text-xs leading-relaxed text-foreground/90"><Markdown content={c.text} /></div>
                )}
                {c.toolCalls.length > 0 && (
                  <div className="mt-1.5 space-y-1">
                    {c.toolCalls.map(tc => (
                      <ToolRow key={tc.id} tc={tc} result={c.results.find(r => r.id === tc.id)} running={hot} />
                    ))}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {trace.end && (
        <div className="flex items-center gap-x-3 gap-y-1 flex-wrap text-[11px] text-muted-foreground border-t border-border/60 pt-2">
          <span className={cn('font-bold', trace.end.status === 'OK' ? 'text-primary' : 'text-loss')}>
            {trace.end.status === 'OK' ? t('live.endedOk') : t('live.endedError')}
          </span>
          <span className="num">{(trace.end.latencyMs / 1000).toFixed(1)}s</span>
          <span>{t('detail.modelCalls', { count: trace.end.modelCalls })}</span>
          {/* token null＝上游没报 usage，显示「—」不显示 0 */}
          <span className="num">{trace.end.totalTokens == null ? '—' : fmtTokens(trace.end.totalTokens)} tok</span>
          <span>{t('term.equity')} <span className="num font-bold text-foreground">{fmtNum(trace.end.equity)}</span></span>
          {trace.end.error && <p className="w-full text-loss leading-relaxed">{trace.end.error}</p>}
        </div>
      )}
    </div>
  );
}
