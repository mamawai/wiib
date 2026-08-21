import { useEffect, useState, type ReactElement } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { BookOpenCheck, Loader2, MessageSquarePlus, Zap, type LucideIcon } from 'lucide-react';
import { traderApi } from '../../api';
import i18n from '../../i18n';
import { cn } from '../../lib/utils';
import type { TraderActionPanel, TraderActionResult, TraderFormKind } from '../../types';

/** 三张卡共用一个壳，只有正文分叉：图标/标题/徽章在这儿一次说清（存 key，渲染时翻） */
const META: Record<TraderFormKind, { icon: LucideIcon; titleKey: string; badgeKey: string }> = {
  note: { icon: MessageSquarePlus, titleKey: 'card.noteTitle', badgeKey: 'card.noteBadge' },
  wake: { icon: Zap, titleKey: 'term.manualWake', badgeKey: 'card.wakeBadge' },
  review: { icon: BookOpenCheck, titleKey: 'term.reviewNow', badgeKey: 'card.reviewBadge' },
};

const BTN = 'flex-1 py-1.5 rounded-lg text-xs font-bold border border-border flex items-center justify-center gap-1.5 transition-colors disabled:opacity-50';
const BTN_MAIN = `${BTN} text-primary hover:bg-primary/8`;
const BTN_SUB = `${BTN} text-muted-foreground hover:bg-surface-hover`;

/**
 * 相对时间。面板里「下次例行」是未来时刻，全局 fmtRelative 只算过去（未来会被压成"刚刚"），
 * 这儿按绝对差值算，方向决定用"之后"还是"之前"那组词条。
 * 词表在函数体里现查（不存模块级常量），过去/未来各一组整句——
 * 中文是"N分钟后"、英文是"in N minutes"，前后缀位置不同，拼不出来。
 */
function relTime(ms: number): string {
  const diff = ms - Date.now();
  const abs = Math.abs(diff);
  const future = diff > 0;
  if (abs < 60_000) return i18n.t('ai:card.justNow');
  if (abs < 3_600_000) {
    return i18n.t(future ? 'ai:card.inMinutes' : 'ai:card.agoMinutes', { count: Math.floor(abs / 60_000) });
  }
  if (abs < 86_400_000) {
    return i18n.t(future ? 'ai:card.inHours' : 'ai:card.agoHours', { count: Math.floor(abs / 3_600_000) });
  }
  return i18n.t(future ? 'ai:card.inDays' : 'ai:card.agoDays', { count: Math.floor(abs / 86_400_000) });
}

/** "3分钟前 · 14:23"：相对量判新鲜度，时刻用来对表；null 一律破折号 */
function stamp(ms: number | null): string {
  if (ms == null) return '—';
  const d = new Date(ms);
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${relTime(ms)} · ${hh}:${mm}`;
}

/** 仪表读数行：左微标签、右数值 */
function StatLine({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <span className="microlabel uppercase shrink-0">{label}</span>
      <span className="num text-[10px] font-bold ml-auto truncate">{value}</span>
    </div>
  );
}

interface TraderFormCardProps {
  form: TraderFormKind;
  /** 模型草拟的初值：留言卡取 note(string) 与 rounds(number)，缺什么退回默认 */
  prefill?: Record<string, unknown>;
  status: 'pending' | 'done';
  /** status==='done' 时显示的落地文案 */
  result?: string;
  /** 提交完成后把 message 回传，宿主据此把卡置为 done */
  onSettle: (result: string) => void;
  onCancel: () => void;
}

/**
 * trader 动作表单卡：模型只有开卡的权限，真正执行由用户在卡上点按钮触发。
 * 三张卡（留言/唤醒/复盘）的可点条件全看挂载时拉到的 actionPanel——
 * 面板没拿到就一律禁用主按钮，宁可点不动，也不能瞎发真实交易请求。
 */
export function TraderFormCard({ form, prefill, status, result, onSettle, onCancel }: TraderFormCardProps): ReactElement {
  // 订阅词表：卡里的 relTime 是本地那份相对时间，切语言要跟着刷新
  const { t } = useTranslation(['ai', 'common']);
  const [panel, setPanel] = useState<TraderActionPanel | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  // 哪个按钮在提交：留言卡有"保存"和"撤回"两个入口，转圈要落在按下的那个上
  const [busy, setBusy] = useState<'main' | 'clear' | null>(null);
  /** 这次没办成的原因，就地显示；办成了就落地成 result，不走这儿 */
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState(() => (typeof prefill?.note === 'string' ? prefill.note : ''));
  const [roundsText, setRoundsText] = useState(() => (typeof prefill?.rounds === 'number' ? String(prefill.rounds) : '1'));

  const done = status === 'done';

  // 已落地的卡只是历史留痕，不必再打接口
  useEffect(() => {
    if (done) return;
    let alive = true;
    traderApi.actionPanel()
      .then(p => { if (alive) setPanel(p); })
      .catch(() => { if (alive) setLoadFailed(true); });
    return () => { alive = false; };
  }, [done]);

  /**
   * 提交统一出口：只有真办成了才落地这张卡。
   * 业务拒绝（trader 暂停中、上次复盘还在跑…）和网络失败都就地显示、卡留着可编辑——
   * 落地是单向的，卡一关草稿跟着没，而这些拒绝多半去处理一下就能重试。
   */
  const submit = (tag: 'main' | 'clear', call: () => Promise<TraderActionResult>) => {
    setBusy(tag);
    setError(null);
    call()
      .then(res => (res.ok ? onSettle(res.message) : setError(res.message)))
      .catch((err: Error) => setError(err?.message || t('card.actionFailed')))
      .finally(() => setBusy(null));
  };

  const meta = META[form];
  const Icon = meta.icon;
  const submitting = busy !== null;
  const hint = loadFailed ? t('card.loadFailed') : t('card.loading');
  const hasNote = !!panel?.note;
  const roundsRaw = Math.max(1, Number.parseInt(roundsText, 10) || 1);
  // 上限在前端也钳一道，纯粹为了让"接下来 N 次"这句不说假话；后端一样会钳
  const rounds = panel ? Math.min(roundsRaw, panel.noteMaxRounds) : roundsRaw;
  const overChars = panel != null && note.length > panel.noteMaxChars;

  let body: ReactElement;
  if (done) {
    // 没有 result 的 done 只有一个来路：用户点了取消（宿主直接关卡，不带落地文案）
    body = <p className="text-[10px] font-bold text-muted-foreground">{result || t('card.cancelled')}</p>;
  } else if (panel && !panel.hasTrader) {
    body = (
      <>
        <p className="text-xs text-muted-foreground leading-relaxed">{t('card.noTrader')}</p>
        <div className="flex gap-2">
          <button onClick={onCancel} className={BTN_SUB}>{t('common:cancel')}</button>
        </div>
      </>
    );
  } else if (form === 'note') {
    body = (
      <>
        {hasNote && (
          <p className="text-[10px] text-muted-foreground truncate">
            <Trans ns="ai" i18nKey="card.noteLeft"
                   values={{ rounds: panel?.noteRounds, note: panel?.note }}
                   components={[<span className="num" />]} />
          </p>
        )}
        <div>
          <div className="flex items-baseline justify-between mb-1">
            <span className="microlabel uppercase">{t('card.noteLabel')}</span>
            <span className={cn('num text-[10px]', overChars ? 'text-loss' : 'text-muted-foreground')}>
              {note.length}/{panel?.noteMaxChars ?? '—'}
            </span>
          </div>
          <textarea
            value={note}
            onChange={e => setNote(e.target.value)}
            placeholder={t('card.notePh')}
            className="min-h-[64px] rounded-md bg-input border border-border px-2.5 py-2 text-xs w-full resize-none"
          />
        </div>
        <div>
          <div className="microlabel uppercase mb-1">{t('card.roundsLabel')}</div>
          <div className="flex items-center gap-2">
            <input
              type="number"
              min={1}
              max={panel?.noteMaxRounds}
              value={roundsText}
              onChange={e => setRoundsText(e.target.value)}
              className="h-9 w-20 rounded-md bg-input border border-border px-2.5 text-xs num"
            />
            <span className="text-[10px] text-muted-foreground leading-snug">{t('card.roundsHint', { count: rounds })}</span>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            disabled={submitting || !panel || overChars || !note.trim()}
            onClick={() => submit('main', () => traderApi.saveNote(note.trim(), rounds))}
            className={BTN_MAIN}
          >
            {busy === 'main' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : t('common:save')}
          </button>
          {/* 撤回只在真有未读留言时露出：没留言可撤，按钮点了也是空操作 */}
          {hasNote && (
            <button
              disabled={submitting}
              onClick={() => submit('clear', () => traderApi.clearNote())}
              className={BTN_SUB}
            >
              {busy === 'clear' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : t('card.clear')}
            </button>
          )}
          <button disabled={submitting} onClick={onCancel} className={BTN_SUB}>{t('common:cancel')}</button>
        </div>
      </>
    );
  } else if (form === 'wake') {
    body = (
      <>
        <div className="space-y-1">
          <StatLine label={t('card.lastWake')} value={panel ? stamp(panel.lastWakeAt) : hint} />
          <StatLine label={t('card.nextWake')} value={panel ? stamp(panel.nextWakeAt) : hint} />
        </div>
        <p className="text-[10px] text-muted-foreground leading-snug">{t('card.wakeHint')}</p>
        {panel?.wakeBlockedReason && (
          <p className="text-[10px] text-loss leading-snug">{panel.wakeBlockedReason}</p>
        )}
        <div className="flex gap-2">
          <button
            disabled={submitting || !panel || !!panel.wakeBlockedReason}
            onClick={() => submit('main', () => traderApi.wake())}
            className={BTN_MAIN}
          >
            {busy === 'main' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : t('card.wakeAction')}
          </button>
          <button disabled={submitting} onClick={onCancel} className={BTN_SUB}>{t('common:cancel')}</button>
        </div>
      </>
    );
  } else {
    const lastReview = panel
      ? (panel.lastReviewAt != null ? relTime(panel.lastReviewAt) : '—')
        + (panel.lastReviewStatus === 'ERROR' ? t('card.lastFailed') : '')
      : hint;
    body = (
      <>
        <div className="space-y-1">
          <StatLine label={t('card.lastReview')} value={lastReview} />
          <StatLine label={t('card.material')} value={panel ? (panel.hasReviewMaterial ? t('card.yes') : t('card.no')) : hint} />
        </div>
        {/* 无新素材不禁用按钮：后端会直接跳过，既免费又安全，没必要拦人 */}
        {panel && !panel.hasReviewMaterial && (
          <p className="text-[10px] text-muted-foreground leading-snug">{t('card.skipHint')}</p>
        )}
        <p className="text-[10px] text-muted-foreground leading-snug">{t('card.reviewAdvice')}</p>
        {panel?.reviewBlockedReason && (
          <p className="text-[10px] text-loss leading-snug">{panel.reviewBlockedReason}</p>
        )}
        <div className="flex gap-2">
          <button
            disabled={submitting || !panel || !!panel.reviewBlockedReason}
            onClick={() => submit('main', () => traderApi.review())}
            className={BTN_MAIN}
          >
            {busy === 'main' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : t('card.reviewAction')}
          </button>
          <button disabled={submitting} onClick={onCancel} className={BTN_SUB}>{t('common:cancel')}</button>
        </div>
      </>
    );
  }

  return (
    <div className={cn(
      'rounded-xl border border-border border-l-[3px] p-3.5 space-y-2.5',
      done ? 'border-l-muted-foreground/30 opacity-70' : 'border-l-primary bg-card',
    )}>
      <div className="flex items-center gap-2">
        <Icon className="w-4 h-4 text-primary shrink-0" />
        <span className="text-xs font-black">{t(meta.titleKey)}</span>
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary/10 text-primary">{t(meta.badgeKey)}</span>
      </div>
      {body}
      {error && <p className="text-[10px] text-loss leading-snug">{error}</p>}
    </div>
  );
}
