import { ChevronLeft, Loader2, MessageSquareText, Trash2 } from 'lucide-react';
import { cn, fmtDateTime } from '../../lib/utils';
import type { WorkbenchSessionSummary } from '../../types';

/**
 * 历史会话叠层：盖在对话上的右滑面板。常驻挂载才有出入动画，关着时靠 translate-x-full 移出视野。
 */
export function SessionHistory({ open, loading, sessions, currentId, onBack, onOpen, onRemove, onNew }: {
  open: boolean;
  loading: boolean;
  sessions: WorkbenchSessionSummary[];
  currentId: string | null;
  onBack: () => void;
  onOpen: (s: WorkbenchSessionSummary) => void;
  onRemove: (s: WorkbenchSessionSummary) => void;
  onNew: () => void;
}) {
  return (
    <div className={cn(
      'absolute inset-0 z-10 bg-card flex flex-col transition-transform duration-300 ease-[cubic-bezier(.16,1,.3,1)]',
      open ? 'translate-x-0' : 'translate-x-full pointer-events-none',
    )}>
      <div className="flex items-center gap-2 px-3.5 py-2.5 shrink-0">
        <button
          onClick={onBack}
          className="border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
          aria-label="返回对话"
        >
          <ChevronLeft className="w-3.5 h-3.5" />
        </button>
        <span className="text-xs font-black">历史对话</span>
      </div>
      <div className="flex-1 overflow-y-auto px-3.5 pb-2 space-y-2">
        {loading ? (
          <div className="flex items-center justify-center gap-2 py-10 text-xs text-muted-foreground">
            <Loader2 className="w-4 h-4 animate-spin" /> 加载历史会话...
          </div>
        ) : sessions.length === 0 ? (
          <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
            <MessageSquareText className="w-8 h-8 text-muted-foreground/40" />
            <p className="text-xs text-muted-foreground">还没有历史对话</p>
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
                  <span className="text-[9px] font-black tracking-wider text-primary shrink-0">当前</span>
                )}
                <button
                  onClick={e => { e.stopPropagation(); onRemove(s); }}
                  className="shrink-0 w-6 h-6 rounded-md flex items-center justify-center text-muted-foreground/50 hover:text-loss hover:bg-loss/10 transition-colors"
                  title="删除会话"
                  aria-label="删除会话"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
              <div className="text-[10px] text-muted-foreground flex items-center gap-2">
                <span>{fmtDateTime(s.lastAt)}</span>
                <span>· {s.messageCount} 条</span>
              </div>
            </div>
          ))
        )}
      </div>
      <button
        onClick={onNew}
        className="mx-3.5 mb-3 shrink-0 border border-dashed border-border rounded-xl py-2 text-xs font-bold text-primary hover:bg-primary/6 transition-colors"
      >
        + 开新对话
      </button>
    </div>
  );
}
