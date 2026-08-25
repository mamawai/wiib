import { useTranslation } from 'react-i18next';
import { ChevronLeft, History, Loader2, MessageSquareText, Trash2 } from 'lucide-react';
import { cn, fmtDateTime } from '../../lib/utils';
import type { WorkbenchSessionSummary } from '../../types';

/**
 * 历史会话列表，两种形态共用同一份列表渲染。
 * <p>
 * <b>叠层</b>（浮窗 / 手机）：盖在对话上的右滑面板。常驻挂载才有出入动画，关着时靠 translate-x-full 移出视野。
 * <p>
 * <b>侧栏</b>（PC 全屏）：钉在左边跟对话并排，没有"返回"这一步——开合是外层那层宽度过渡在做，
 * 这里只管把自己铺满给定宽度。
 */
export function SessionHistory({ sidebar = false, open, loading, sessions, currentId, onBack, onOpen, onRemove, onNew }: {
  /** true=全屏时的左侧常驻栏；false=右滑叠层 */
  sidebar?: boolean;
  open: boolean;
  loading: boolean;
  sessions: WorkbenchSessionSummary[];
  currentId: string | null;
  onBack: () => void;
  onOpen: (s: WorkbenchSessionSummary) => void;
  onRemove: (s: WorkbenchSessionSummary) => void;
  onNew: () => void;
}) {
  const { t } = useTranslation('ai');
  return (
    <div className={cn(
      'flex flex-col',
      sidebar
        // 宽度钉死不跟随外层：外层收起时宽度会缩到 0，不钉住的话列表先被压成一条竖线再消失，比直接滑走还难看
        ? 'w-64 h-full shrink-0 border-r border-border bg-card-2'
        : cn('absolute inset-0 z-10 bg-card transition-transform duration-300 ease-[cubic-bezier(.16,1,.3,1)]',
          open ? 'translate-x-0' : 'translate-x-full pointer-events-none'),
    )}>
      <div className="flex items-center gap-2 px-3.5 py-2.5 shrink-0">
        {/* 侧栏里没有"返回"可言——它跟对话是并排的，收起交给面板头部那个开关 */}
        {sidebar ? (
          <History className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        ) : (
          <button
            onClick={onBack}
            className="border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
            aria-label={t('history.back')}
          >
            <ChevronLeft className="w-3.5 h-3.5" />
          </button>
        )}
        <span className="text-xs font-black">{t('history.title')}</span>
      </div>
      <div className="flex-1 overflow-y-auto px-3.5 pb-2 space-y-2">
        {loading ? (
          <div className="flex items-center justify-center gap-2 py-10 text-xs text-muted-foreground">
            <Loader2 className="w-4 h-4 animate-spin" /> {t('history.loading')}
          </div>
        ) : sessions.length === 0 ? (
          <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
            <MessageSquareText className="w-8 h-8 text-muted-foreground/40" />
            <p className="text-xs text-muted-foreground">{t('history.empty')}</p>
          </div>
        ) : (
          sessions.map(s => (
            <div
              key={s.sessionId}
              role="button"
              tabIndex={0}
              onClick={() => onOpen(s)}
              onKeyDown={e => e.key === 'Enter' && onOpen(s)}
              className={cn(
                'w-full text-left rounded-xl border px-3.5 py-2.5 space-y-1 hover:bg-surface-hover transition-colors cursor-pointer',
                s.sessionId === currentId ? 'border-primary/45 bg-card-2' : 'border-border bg-card',
              )}
            >
              <div className="flex items-center gap-2">
                <div className="text-xs font-bold truncate flex-1">{s.title}</div>
                {s.sessionId === currentId && (
                  <span className="text-[9px] font-black tracking-wider text-primary shrink-0">{t('history.current')}</span>
                )}
                <button
                  onClick={e => { e.stopPropagation(); onRemove(s); }}
                  className="shrink-0 w-6 h-6 rounded-md flex items-center justify-center text-muted-foreground/50 hover:text-loss hover:bg-loss/10 transition-colors"
                  title={t('history.delete')}
                  aria-label={t('history.delete')}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
              <div className="text-[10px] text-muted-foreground flex items-center gap-2">
                <span>{fmtDateTime(s.lastAt)}</span>
                <span>· {t('history.msgCount', { count: s.messageCount })}</span>
              </div>
            </div>
          ))
        )}
      </div>
      <button
        onClick={onNew}
        className="mx-3.5 mb-3 shrink-0 border border-dashed border-border rounded-xl py-2 text-xs font-bold text-primary hover:bg-primary/6 transition-colors"
      >
        + {t('history.new')}
      </button>
    </div>
  );
}
