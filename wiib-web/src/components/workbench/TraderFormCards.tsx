import { useEffect, useState, type ReactElement } from 'react';
import { BookOpenCheck, Loader2, MessageSquarePlus, Zap, type LucideIcon } from 'lucide-react';
import { traderApi } from '../../api';
import { cn } from '../../lib/utils';
import type { TraderActionPanel, TraderActionResult, TraderFormKind } from '../../types';

/** 三张卡共用一个壳，只有正文分叉：图标/标题/徽章在这儿一次说清 */
const META: Record<TraderFormKind, { icon: LucideIcon; title: string; badge: string }> = {
  note: { icon: MessageSquarePlus, title: '给 trader 留言', badge: '下轮生效' },
  wake: { icon: Zap, title: '手动唤醒', badge: '会真实交易' },
  review: { icon: BookOpenCheck, title: '立即复盘', badge: '烧一次深模型' },
};

const BTN = 'flex-1 py-1.5 rounded-lg text-xs font-bold border border-border flex items-center justify-center gap-1.5 transition-colors disabled:opacity-50';
const BTN_MAIN = `${BTN} text-primary hover:bg-primary/8`;
const BTN_SUB = `${BTN} text-muted-foreground hover:bg-surface-hover`;

/**
 * 相对时间。面板里「下次例行」是未来时刻，全局 fmtRelative 只算过去（未来会被压成"刚刚"），
 * 这儿按绝对差值算，方向只决定后缀是"前"还是"后"。
 */
function relTime(ms: number): string {
  const diff = ms - Date.now();
  const abs = Math.abs(diff);
  if (abs < 60_000) return '刚刚';
  const suffix = diff > 0 ? '后' : '前';
  if (abs < 3_600_000) return `${Math.floor(abs / 60_000)}分钟${suffix}`;
  if (abs < 86_400_000) return `${Math.floor(abs / 3_600_000)}小时${suffix}`;
  return `${Math.floor(abs / 86_400_000)}天${suffix}`;
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
      .catch((err: Error) => setError(err?.message || '操作失败，请稍后再试'))
      .finally(() => setBusy(null));
  };

  const meta = META[form];
  const Icon = meta.icon;
  const submitting = busy !== null;
  const hint = loadFailed ? '读取失败' : '读取中…';
  const hasNote = !!panel?.note;
  const roundsRaw = Math.max(1, Number.parseInt(roundsText, 10) || 1);
  // 上限在前端也钳一道，纯粹为了让"接下来 N 次"这句不说假话；后端一样会钳
  const rounds = panel ? Math.min(roundsRaw, panel.noteMaxRounds) : roundsRaw;
  const overChars = panel != null && note.length > panel.noteMaxChars;

  let body: ReactElement;
  if (done) {
    // 没有 result 的 done 只有一个来路：用户点了取消（宿主直接关卡，不带落地文案）
    body = <p className="text-[10px] font-bold text-muted-foreground">{result || '已取消'}</p>;
  } else if (panel && !panel.hasTrader) {
    body = (
      <>
        <p className="text-xs text-muted-foreground leading-relaxed">你还没有创建 AI Trader</p>
        <div className="flex gap-2">
          <button onClick={onCancel} className={BTN_SUB}>取消</button>
        </div>
      </>
    );
  } else if (form === 'note') {
    body = (
      <>
        {hasNote && (
          <p className="text-[10px] text-muted-foreground truncate">
            还剩 <span className="num">{panel?.noteRounds}</span> 轮 · {panel?.note}
          </p>
        )}
        <div>
          <div className="flex items-baseline justify-between mb-1">
            <span className="microlabel uppercase">留言内容</span>
            <span className={cn('num text-[10px]', overChars ? 'text-loss' : 'text-muted-foreground')}>
              {note.length}/{panel?.noteMaxChars ?? '—'}
            </span>
          </div>
          <textarea
            value={note}
            onChange={e => setNote(e.target.value)}
            placeholder="想让它下一轮注意什么"
            className="min-h-[64px] rounded-md bg-input border border-border px-2.5 py-2 text-xs w-full resize-none"
          />
        </div>
        <div>
          <div className="microlabel uppercase mb-1">生效轮次</div>
          <div className="flex items-center gap-2">
            <input
              type="number"
              min={1}
              max={panel?.noteMaxRounds}
              value={roundsText}
              onChange={e => setRoundsText(e.target.value)}
              className="h-9 w-20 rounded-md bg-input border border-border px-2.5 text-xs num"
            />
            <span className="text-[10px] text-muted-foreground leading-snug">接下来 {rounds} 次唤醒都会带上这句</span>
          </div>
        </div>
        <div className="flex gap-2">
          <button
            disabled={submitting || !panel || overChars || !note.trim()}
            onClick={() => submit('main', () => traderApi.saveNote(note.trim(), rounds))}
            className={BTN_MAIN}
          >
            {busy === 'main' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '保存'}
          </button>
          {/* 撤回只在真有未读留言时露出：没留言可撤，按钮点了也是空操作 */}
          {hasNote && (
            <button
              disabled={submitting}
              onClick={() => submit('clear', () => traderApi.clearNote())}
              className={BTN_SUB}
            >
              {busy === 'clear' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '撤回'}
            </button>
          )}
          <button disabled={submitting} onClick={onCancel} className={BTN_SUB}>取消</button>
        </div>
      </>
    );
  } else if (form === 'wake') {
    body = (
      <>
        <div className="space-y-1">
          <StatLine label="上次唤醒" value={panel ? stamp(panel.lastWakeAt) : hint} />
          <StatLine label="下次例行" value={panel ? stamp(panel.nextWakeAt) : hint} />
        </div>
        <p className="text-[10px] text-muted-foreground leading-snug">会真实执行一次交易决策，可能开仓或平仓</p>
        {panel?.wakeBlockedReason && (
          <p className="text-[10px] text-loss leading-snug">{panel.wakeBlockedReason}</p>
        )}
        <div className="flex gap-2">
          <button
            disabled={submitting || !panel || !!panel.wakeBlockedReason}
            onClick={() => submit('main', () => traderApi.wake())}
            className={BTN_MAIN}
          >
            {busy === 'main' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '立即唤醒'}
          </button>
          <button disabled={submitting} onClick={onCancel} className={BTN_SUB}>取消</button>
        </div>
      </>
    );
  } else {
    const lastReview = panel
      ? (panel.lastReviewAt != null ? relTime(panel.lastReviewAt) : '—')
        + (panel.lastReviewStatus === 'ERROR' ? ' · 上次失败' : '')
      : hint;
    body = (
      <>
        <div className="space-y-1">
          <StatLine label="上次复盘" value={lastReview} />
          <StatLine label="新素材" value={panel ? (panel.hasReviewMaterial ? '有' : '无') : hint} />
        </div>
        {/* 无新素材不禁用按钮：后端会直接跳过，既免费又安全，没必要拦人 */}
        {panel && !panel.hasReviewMaterial && (
          <p className="text-[10px] text-muted-foreground leading-snug">点了会跳过，不消耗模型调用</p>
        )}
        <p className="text-[10px] text-muted-foreground leading-snug">建议一天 1~2 次：复盘吃的是已了结交易，交易没变化时跑了也写不出新东西</p>
        {panel?.reviewBlockedReason && (
          <p className="text-[10px] text-loss leading-snug">{panel.reviewBlockedReason}</p>
        )}
        <div className="flex gap-2">
          <button
            disabled={submitting || !panel || !!panel.reviewBlockedReason}
            onClick={() => submit('main', () => traderApi.review())}
            className={BTN_MAIN}
          >
            {busy === 'main' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : '开始复盘'}
          </button>
          <button disabled={submitting} onClick={onCancel} className={BTN_SUB}>取消</button>
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
        <span className="text-xs font-black">{meta.title}</span>
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary/10 text-primary">{meta.badge}</span>
      </div>
      {body}
      {error && <p className="text-[10px] text-loss leading-snug">{error}</p>}
    </div>
  );
}
