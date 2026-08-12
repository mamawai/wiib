import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bot, KeyRound, Loader2, X } from 'lucide-react';
import { llmConfigApi } from '../../api';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { ChatPanel } from './ChatPanel';

const SIZE_KEY = 'wiib-chatdock-size';
const MIN_W = 320, MAX_W = 760, MIN_H = 420;
type Size = { w: number; h: number };
type Axis = 'x' | 'y' | 'xy';

function loadSize(): Size {
  try {
    const s = JSON.parse(localStorage.getItem(SIZE_KEY) || '') as Size;
    if (typeof s.w === 'number' && typeof s.h === 'number') return s;
  } catch { /* 没存过/存坏了都用默认 */ }
  return { w: 400, h: 672 };
}

/**
 * 全站悬浮对话入口（Layout 挂载，登录后可见）：右下角气泡 + PC 锚定浮窗 / 移动端全屏层。
 * chatStore 是页面无关的单例，SSE 不随面板关闭中断——关掉气泡研判照跑；
 * 面板关着时一轮跑完，气泡亮橙点提醒。PC 浮窗可从左/上边缘拖拽调大小（记忆到 localStorage）。
 */
export function ChatDock() {
  const navigate = useNavigate();
  const { loading } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(false);
  // null=首次还没查回来（面板内转圈）。每次打开都重查：用户去配置页存完回来，不用刷新页面
  const [hasConfig, setHasConfig] = useState<boolean | null>(null);
  // 订阅回调里读不到最新 state，开合状态镜像到 ref（只在事件处理器里写）
  const openRef = useRef(false);

  const [size, setSize] = useState<Size>(loadSize);
  // 拖拽全程的账都记在 ref 里（起点 + 最新值），结束时一次性落 localStorage
  const dragRef = useRef<{ axis: Axis; x: number; y: number; w: number; h: number; cur: Size } | null>(null);

  // 面板关着时一轮研判跑完（loading 真→假）→ 气泡亮橙点
  useEffect(() => {
    let prev = chatStore.getSnapshot().loading;
    return chatStore.subscribe(() => {
      const now = chatStore.getSnapshot().loading;
      if (prev && !now && !openRef.current) setUnread(true);
      prev = now;
    });
  }, []);

  const setOpenBoth = useCallback((v: boolean) => {
    openRef.current = v;
    setOpen(v);
  }, []);

  const toggle = useCallback(() => {
    const next = !openRef.current;
    if (next) {
      setUnread(false);
      llmConfigApi.mine().then(c => setHasConfig(c != null)).catch(() => setHasConfig(false));
    }
    setOpenBoth(next);
  }, [setOpenBoth]);

  const goConfig = useCallback(() => {
    setOpenBoth(false);
    navigate('/ai?tab=config');
  }, [setOpenBoth, navigate]);

  /** 面板锚在右下角，所以往左/往上拖是放大 */
  const resizeStart = (axis: Axis) => (e: React.PointerEvent) => {
    e.preventDefault();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    dragRef.current = { axis, x: e.clientX, y: e.clientY, w: size.w, h: size.h, cur: size };
  };
  const resizeMove = (e: React.PointerEvent) => {
    const d = dragRef.current;
    if (!d) return;
    const next: Size = {
      w: d.axis === 'y' ? d.w
        : Math.min(Math.max(d.w + (d.x - e.clientX), MIN_W), Math.min(MAX_W, window.innerWidth - 32)),
      h: d.axis === 'x' ? d.h
        : Math.min(Math.max(d.h + (d.y - e.clientY), MIN_H), window.innerHeight - 96),
    };
    d.cur = next;
    setSize(next);
  };
  const resizeEnd = () => {
    if (!dragRef.current) return;
    localStorage.setItem(SIZE_KEY, JSON.stringify(dragRef.current.cur));
    dragRef.current = null;
  };

  return (
    <>
      {/* 气泡：bottom-20 避让移动端底部导航（项目既有写法）；z-90 夹在 GuidedTour 之上、toast 之下。
          移动端面板全屏时气泡藏起来，否则会压在输入区上 */}
      <button
        type="button"
        onClick={toggle}
        title={open ? '关闭对话' : '研判对话'}
        aria-label={open ? '关闭对话' : '打开研判对话'}
        className={cn(
          'fixed right-4 bottom-20 md:bottom-6 z-[90] w-12 h-12 rounded-full pt-card',
          'items-center justify-center hover:bg-surface-hover transition-colors',
          open ? 'hidden md:flex' : 'flex',
        )}
      >
        {open ? (
          <X className="w-5 h-5 text-muted-foreground" />
        ) : loading ? (
          <Loader2 className="w-5 h-5 text-primary animate-spin" />
        ) : (
          <Bot className="w-5 h-5 text-primary" />
        )}
        {/* 关着时答案到了：亮橙点。打开即清 */}
        {!open && unread && (
          <span className="absolute top-1 right-1 w-2.5 h-2.5 rounded-full bg-primary animate-pulse" />
        )}
      </button>

      {open && (
        <div
          style={{ '--dock-w': `${size.w}px`, '--dock-h': `${size.h}px` } as React.CSSProperties}
          className={cn(
            'fixed z-[85] pt-card flex flex-col overflow-hidden',
            // 移动端全屏（让开刘海），PC 锚定在气泡上方的浮窗（尺寸可拖拽，CSS 变量只在 md 生效）
            'inset-0 rounded-none pt-[env(safe-area-inset-top)]',
            'md:inset-auto md:right-4 md:bottom-20 md:rounded-xl md:shadow-2xl md:pt-0',
            'md:w-[var(--dock-w)] md:h-[var(--dock-h)]',
            'md:max-w-[calc(100vw-2rem)] md:max-h-[calc(100vh-6.5rem)]',
            'animate-in fade-in zoom-in-95 origin-bottom-right',
          )}
        >
          {/* 拖拽把手（仅 PC）：左边缘调宽、上边缘调高、左上角双向 */}
          <div onPointerDown={resizeStart('x')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
               className="hidden md:block absolute left-0 top-4 bottom-0 w-1.5 cursor-ew-resize z-20" />
          <div onPointerDown={resizeStart('y')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
               className="hidden md:block absolute top-0 left-4 right-0 h-1.5 cursor-ns-resize z-20" />
          <div onPointerDown={resizeStart('xy')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
               className="hidden md:flex absolute left-0 top-0 w-4 h-4 cursor-nwse-resize z-20 items-start justify-start group"
               title="拖拽调整大小">
            <span className="ml-1 mt-1 w-2 h-2 border-l-2 border-t-2 rounded-tl border-muted-foreground/40 group-hover:border-primary transition-colors" />
          </div>

          {hasConfig == null ? (
            <PanelShell onClose={() => setOpenBoth(false)}>
              <div className="flex-1 flex items-center justify-center gap-2 text-xs text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载配置...
              </div>
            </PanelShell>
          ) : hasConfig ? (
            <ChatPanel onClose={() => setOpenBoth(false)} onGoConfig={goConfig} />
          ) : (
            <PanelShell onClose={() => setOpenBoth(false)}>
              <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center px-6">
                <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
                  <KeyRound className="w-6 h-6" />
                </div>
                <div className="text-sm font-bold text-muted-foreground">用你自己的 API Key 开始对话</div>
                <div className="text-[11px] text-muted-foreground/70 max-w-[16rem]">
                  对话走你配置的模型端点，费用记在你自己的账上，平台不经手
                </div>
                <button
                  type="button"
                  onClick={goConfig}
                  className="mt-1 border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover"
                >
                  去配置
                </button>
              </div>
            </PanelShell>
          )}
        </div>
      )}
    </>
  );
}

/** 加载中/引导态的简壳：ChatPanel 自带头部，这两个状态没有，得补一个能关的头 */
function PanelShell({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <>
      <div className="flex items-center gap-2 px-4 py-3 border-b border-border">
        <Bot className="w-4.5 h-4.5 text-primary" />
        <span className="text-sm font-black">研判对话</span>
        <button
          onClick={onClose}
          className="ml-auto border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
          title="关闭"
          aria-label="关闭对话面板"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
      {children}
    </>
  );
}
