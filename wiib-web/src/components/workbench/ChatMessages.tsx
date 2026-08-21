import { useCallback, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Check, ChevronRight, Copy, Loader2, RefreshCw, ShieldQuestion, X } from 'lucide-react';
import { Markdown } from '../Markdown';
import { cn, fmtTime, fmtTokens } from '../../lib/utils';
import { DEFERRED_PREFIX, type ChatItem } from './chatStore';
import { AGENT_LABEL_KEY, HUB_NAME, type RailStep } from './chatView';

/** 用户提问：右侧气泡，下面挂时刻 */
export function UserBubble({ item, onCancelQueued }: {
  item: Extract<ChatItem, { kind: 'user' }>;
  /** 只有排队中的能撤：已经发出去的那条正在烧钱，撤不回来 */
  onCancelQueued?: () => void;
}) {
  const { t } = useTranslation('ai');
  return (
    <div className="flex flex-col items-end gap-0.5">
      <div className={cn(
        'max-w-[85%] rounded-2xl rounded-br-md bg-primary/10 px-3.5 py-2.5',
        'text-sm leading-relaxed whitespace-pre-wrap break-words',
        item.queued && 'opacity-60',
      )}>
        {item.content}
      </div>
      {/* 时刻才挂 .num（等宽数字）；中文套上它会掉到 mono 的回退字体 */}
      {item.queued ? (
        <span className="flex items-center gap-1.5">
          <span className="microlabel">{t('chat.queued')}</span>
          {onCancelQueued && (
            <button
              onClick={onCancelQueued}
              className="text-muted-foreground/60 hover:text-loss transition-colors"
              title={t('chat.cancelQueued')}
              aria-label={t('chat.cancelQueuedAria')}
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </span>
      ) : (
        <span className="microlabel num">{fmtTime(item.at)}</span>
      )}
    </div>
  );
}

/**
 * agent 回答：署名行 + 无框正文 + 脚注读数。
 * <p>
 * 正文不套卡片——同一屏里只有用户提问是气泡，答案铺满可用宽度，读起来才像正文而不是聊天记录。
 * 脚注只在流结束后出现：耗时/token 要等 done 事件才有值，流式期间挂个空壳会让布局在出字过程中跳一下。
 */
export function AssistantAnswer({ item, canRegenerate, onRegenerate }: {
  item: Extract<ChatItem, { kind: 'assistant' }>;
  /** 只有会话最后一条答案能重新生成：回退上下文只回得到末尾那一轮 */
  canRegenerate: boolean;
  onRegenerate: () => void;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const [copied, setCopied] = useState(false);
  const copy = useCallback(() => {
    void navigator.clipboard.writeText(item.content).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }).catch(() => { /* 无剪贴板权限时静默，按钮保持原样 */ });
  }, [item.content]);

  // 补答行对应的提问不在会话末尾，后端回退会误伤中间轮次，所以它没有重新生成
  const deferred = item.content.startsWith(DEFERRED_PREFIX);
  const meta = item.meta;
  // 取不到值＝上游没报用量或这一轮的账不可信，不是 0：整段不显示，不要拿 0 冒充
  const readout = [
    meta?.latencyMs != null ? `${(meta.latencyMs / 1000).toFixed(1)}s` : null,
    meta?.totalTokens != null ? `${fmtTokens(meta.totalTokens)} tok` : null,
    fmtTime(item.at),
  ].filter(Boolean).join(' · ');

  return (
    <div className="min-w-0">
      <div className="flex items-center gap-2 mb-1.5">
        <span className="w-[2px] h-3 rounded-full bg-primary shrink-0" />
        {/* 署名要压得住，不走 .microlabel：那条规则在 index.css 里是无层普通 CSS，
            Tailwind v4 的 utility 都在 layer 里，颜色改不动它 */}
        <span className="text-[10px] tracking-[0.1em] font-bold uppercase shrink-0">{HUB_NAME}</span>
        {meta?.modelLabel && <span className="microlabel num truncate min-w-0">{meta.modelLabel}</span>}
        {item.streaming && (
          <span className="ml-auto flex items-center gap-1.5 shrink-0">
            <span className="led" />
            <span className="microlabel">{t('chat.generating')}</span>
          </span>
        )}
      </div>

      <div className="text-sm">
        <Markdown content={item.content} />
        {item.streaming && item.content && (
          <span className="inline-block w-1.5 h-3.5 bg-primary/80 rounded-[1px] ml-0.5 align-middle animate-pulse" />
        )}
      </div>

      {!item.streaming && (
        <div className="mt-2 pt-1.5 border-t border-border/60 flex items-center gap-2 text-[10px] text-muted-foreground/70">
          <span className="num truncate min-w-0">{readout}</span>
          <div className="ml-auto flex items-center gap-2 shrink-0">
            {canRegenerate && !deferred && (
              <button
                onClick={onRegenerate}
                className="flex items-center gap-1 hover:text-primary transition-colors"
                title={t('chat.regenTitle')}
              >
                <RefreshCw className="w-3 h-3" /> {t('chat.regen')}
              </button>
            )}
            <button
              onClick={copy}
              className="hover:text-primary transition-colors"
              title={copied ? t('common:copied') : t('chat.copyAnswer')}
              aria-label={t('chat.copyAnswer')}
            >
              {copied ? <Check className="w-3 h-3 text-success" /> : <Copy className="w-3 h-3" />}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** 工作过程轨：竖轨+节点。默认展开——收不收由用户点，别在他正看专家分析时自己收起来 */
export function ProcessRail({ steps, active, open, onToggle }: {
  steps: RailStep[]; active: boolean; open: boolean; onToggle: () => void;
}) {
  const { t } = useTranslation('ai');
  // 认得出的 agent 翻成展示名，认不出的（后端加了新 agent）原样显示 id
  const agentName = (id: string) =>
    (id === 'supervisor' ? HUB_NAME : AGENT_LABEL_KEY[id] ? t(AGENT_LABEL_KEY[id]) : null);
  return (
    <div className="max-w-[95%]">
      <button
        onClick={onToggle}
        className="flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground hover:text-foreground py-0.5"
      >
        <ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />
        {t('rail.title', { count: steps.length })}
        {active && <Loader2 className="w-3 h-3 animate-spin text-primary" />}
      </button>
      <div className={cn(
        'grid transition-[grid-template-rows] duration-300 ease-out',
        open ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]',
      )}>
        <div className="overflow-hidden min-h-0">
          <div className="ml-[5px] mt-1.5 border-l-2 border-border pl-3.5 space-y-2.5 py-0.5">
            {steps.map(({ item, index }) => {
              const hot = (item.kind === 'expert' && item.streaming) || (item.kind === 'progress' && item.active);
              return (
                <div key={index} className="relative text-[11.5px] leading-relaxed text-muted-foreground">
                  <span className={cn(
                    'absolute -left-[18.5px] top-[5px] w-[7px] h-[7px] rounded-full border-2',
                    hot ? 'bg-primary border-primary shadow-[0_0_6px_var(--color-primary)]' : 'bg-card border-border',
                  )} />
                  {item.kind === 'agent' && (
                    <span>
                      {/* 整句进词表：中英语序不同，拆成"前半 + 名字 + 后半"必拼出病句 */}
                      <Trans
                        ns="ai"
                        i18nKey="rail.handoff"
                        values={{
                          hub: HUB_NAME,
                          agent: agentName(item.agent) || agentName(item.node) || item.agent || item.node,
                        }}
                        components={[
                          <span className="font-bold text-foreground" />,
                          <span className="font-bold text-primary" />,
                        ]}
                      />
                    </span>
                  )}
                  {item.kind === 'expert' && (
                    <>
                      <Trans
                        ns="ai"
                        i18nKey="rail.expertWorking"
                        values={{ agent: agentName(item.agent) || item.agent }}
                        components={[<span className="font-bold text-foreground" />]}
                      />
                      <div className="mt-1 rounded-lg border border-border bg-card-2 px-2.5 py-2 text-[11px] max-h-44 overflow-y-auto">
                        <Markdown content={item.content} />
                      </div>
                    </>
                  )}
                  {/* keyed=前端自己立的说明行，存的是 key，这里现翻；其余是后端下发的阶段文案，原样显示 */}
                  {item.kind === 'progress' && (
                    <span className={cn(hot && 'text-foreground font-bold')}>
                      {item.keyed ? t(item.text) : item.text}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * HITL 确认卡：深研判 3 次深模型调用是贵操作，人工把关后 agent 才继续。
 * submitting 是这张卡自己的在途状态——面板里可能挂着好几张，别一张在提交把所有卡都禁掉。
 */
export function HitlCard({ item, onDecide, submitting }: {
  item: Extract<ChatItem, { kind: 'hitl' }>;
  onDecide: (approved: boolean) => void;
  submitting: 'approve' | 'reject' | null;
}) {
  const { t } = useTranslation('ai');
  const busy = submitting !== null;
  return (
    <div className={cn(
      'rounded-xl border border-border border-l-[3px] p-3.5 space-y-2.5',
      item.status === 'pending' ? 'border-l-primary bg-card' : 'border-l-muted-foreground/30 opacity-70',
    )}>
      <div className="flex items-center gap-2">
        <ShieldQuestion className="w-4 h-4 text-primary shrink-0" />
        <span className="text-xs font-black">{t('hitl.title')}</span>
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary/10 text-primary">{item.symbol}</span>
      </div>
      <p className="text-xs text-muted-foreground leading-relaxed">{item.reason}</p>
      {item.status === 'pending' ? (
        <div className="flex gap-2">
          <button
            disabled={busy}
            onClick={() => onDecide(true)}
            className="border border-border hover:bg-primary/8 flex-1 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50 transition-colors inline-flex items-center justify-center gap-1.5"
          >
            {submitting === 'approve' && <Loader2 className="w-3 h-3 animate-spin" />}
            {t('hitl.approve')}
          </button>
          <button
            disabled={busy}
            onClick={() => onDecide(false)}
            className="border border-border hover:bg-surface-hover flex-1 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50 transition-colors inline-flex items-center justify-center gap-1.5"
          >
            {submitting === 'reject' && <Loader2 className="w-3 h-3 animate-spin" />}
            {t('term.reject')}
          </button>
        </div>
      ) : (
        <p className="text-[10px] font-bold text-muted-foreground">
          {item.status === 'approved' ? t('hitl.approved') : t('hitl.rejected')}
        </p>
      )}
    </div>
  );
}
