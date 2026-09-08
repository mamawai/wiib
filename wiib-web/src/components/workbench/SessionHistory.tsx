import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronLeft, Loader2, MessageSquareText, Plus, Trash2 } from 'lucide-react';
import { cn, DAY_MS } from '../../lib/utils';
import type { WorkbenchSessionSummary } from '../../types';

type Group = { key: string; items: WorkbenchSessionSummary[] };

/**
 * 按最后活跃时间分四组：今天 / 昨天 / 近 7 天 / 更早。
 * 界线用本机今天零点算（列表只做粗分组，不必跟站内那套新加坡时区读数对齐）；
 * 会话本身已由后端按倒序给出，这里只装桶不排序。
 */
function groupSessions(sessions: WorkbenchSessionSummary[]): Group[] {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const groups: Group[] = [
    { key: 'groupToday', items: [] },
    { key: 'groupYesterday', items: [] },
    { key: 'groupWeek', items: [] },
    { key: 'groupOlder', items: [] },
  ];
  for (const s of sessions) {
    const at = s.lastAt;
    const bucket = at >= today ? 0 : at >= today - DAY_MS ? 1 : at >= today - 6 * DAY_MS ? 2 : 3;
    groups[bucket].items.push(s);
  }
  return groups.filter(g => g.items.length > 0);
}

/**
 * 历史会话列表，两种形态共用同一份列表渲染。
 * <p>
 * <b>叠层</b>（浮窗 / 手机）：盖在对话上的右滑面板。常驻挂载才有出入动画，关着时靠 translate-x-full 移出视野。
 * <p>
 * <b>侧栏</b>（PC 全屏）：钉在左边跟对话并排，没有"返回"这一步——开合是外层那层宽度过渡在做，
 * 这里只管把自己铺满给定宽度。
 * <p>
 * 每行只有一行标题：时间与条数不上屏，分组标题已经交代了新旧。删除键平时藏着，
 * 悬停才露；触屏没有 hover，常显。
 */
export function SessionHistory({ sidebar = false, open, loading, sessions, currentId, onBack, onOpen, onRemove, onNew, onClearAll }: {
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
  onClearAll: () => void;
}) {
  const { t } = useTranslation('ai');
  const groups = useMemo(() => groupSessions(sessions), [sessions]);

  return (
    <div className={cn(
      'flex flex-col',
      sidebar
        // 宽度钉死不跟随外层：外层收起时宽度会缩到 0，不钉住的话列表先被压成一条竖线再消失，比直接滑走还难看
        ? 'w-64 h-full shrink-0 border-r border-border bg-card-2'
        : cn('absolute inset-0 z-10 bg-card transition-transform duration-300 ease-[cubic-bezier(.16,1,.3,1)]',
          open ? 'translate-x-0' : 'translate-x-full pointer-events-none'),
    )}>
      {/* 侧栏里没有"返回"可言——它跟对话是并排的，收起交给面板头部那个开关 */}
      {!sidebar && (
        <div className="flex items-center gap-2 px-2 py-2 shrink-0">
          <button
            onClick={onBack}
            className="w-8 h-8 flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-surface-hover"
            aria-label={t('history.back')}
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <span className="text-xs font-black">{t('history.title')}</span>
        </div>
      )}

      <button
        onClick={onNew}
        className="mx-2 mt-2 shrink-0 flex items-center gap-2 px-2 py-2 text-xs font-bold hover:bg-surface-hover transition-colors"
      >
        <Plus className="w-3.5 h-3.5 text-primary shrink-0" /> {t('history.new')}
      </button>

      <div className="flex-1 overflow-y-auto px-2 py-2">
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
          groups.map(g => (
            <div key={g.key} className="mb-2 last:mb-0">
              <div className="microlabel uppercase px-2 py-1.5">{t(`history.${g.key}`)}</div>
              {g.items.map(s => (
                <div
                  key={s.sessionId}
                  role="button"
                  tabIndex={0}
                  onClick={() => onOpen(s)}
                  onKeyDown={e => e.key === 'Enter' && onOpen(s)}
                  className={cn(
                    'group relative flex items-center gap-1 pl-2.5 pr-1 py-1.5 cursor-pointer transition-colors',
                    s.sessionId === currentId ? 'bg-surface-hover' : 'hover:bg-surface-hover',
                  )}
                >
                  {/* 当前会话：左侧一条橙杠 */}
                  {s.sessionId === currentId && <span className="absolute left-0 top-0 bottom-0 w-[3px] bg-primary" />}
                  <span className="flex-1 min-w-0 truncate text-xs">{s.title}</span>
                  {/* 触屏没有 hover，删除键常显 */}
                  <button
                    onClick={e => { e.stopPropagation(); onRemove(s); }}
                    className="shrink-0 w-6 h-6 flex items-center justify-center text-muted-foreground/60 hover:text-loss opacity-0 group-hover:opacity-100 focus-visible:opacity-100 [@media(hover:none)]:opacity-100 transition-opacity"
                    title={t('history.delete')}
                    aria-label={t('history.delete')}
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))}
            </div>
          ))
        )}
      </div>

      {sessions.length > 0 && (
        <button
          onClick={onClearAll}
          className="mx-2 mb-2 shrink-0 flex items-center gap-2 px-2 py-2 text-[11px] font-bold text-muted-foreground hover:text-loss hover:bg-surface-hover transition-colors"
        >
          <Trash2 className="w-3.5 h-3.5 shrink-0" /> {t('history.clearAll')}
        </button>
      )}
    </div>
  );
}
